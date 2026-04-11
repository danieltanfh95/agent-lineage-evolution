(ns succession.reinject
  "Hybrid reinjection gate — fires on whichever comes first:
   transcript bytes grown past byte-threshold, OR turn-count advanced
   by turn-threshold since the last fire.

   State lives in /tmp/.succession-reinject-state-<sid> as a small JSON
   map {:last-bytes N :last-turn N}.

   The gate is called from both the Stop hook (drumbeat reinject) and
   the PostToolUse hook (same gate, avoids double-firing)."
  (:require [cheshire.core :as json]
            [babashka.fs :as fs]
            [clojure.string :as str]))

(defn- state-file [session-id]
  (str "/tmp/.succession-reinject-state-" session-id))

(defn- read-state [session-id]
  (let [f (state-file session-id)]
    (if (fs/exists? f)
      (try (json/parse-string (slurp f) true)
           (catch Exception _ {:last-bytes 0 :last-turn 0 :fire-count 0 :emit-count 0}))
      {:last-bytes 0 :last-turn 0 :fire-count 0 :emit-count 0})))

(defn- write-state! [session-id state]
  (spit (state-file session-id) (json/generate-string state)))

(def ^:private default-max-fires
  "Safety cap on how many times reinject may fire per session. In headless
   `claude -p` mode, emitting additionalContext from Stop triggers another
   model turn, which fires Stop again — without a human in the loop, the
   only backstop is this counter. Three fires is enough to cover a long
   session while bounding runaway cost."
  3)

(defn emission-allowed?
  "Check the per-session emission cap without mutating state. Used by the
   Stop hook as a final gate on ALL additionalContext emissions, not just
   reinject — correction-detection notification messages flow through the
   same cap to prevent a runaway Stop→emit→new-turn→Stop loop in headless
   mode where the reinjected content itself contains correction keywords
   that re-trigger tier1."
  ([session-id]
   (emission-allowed? session-id default-max-fires))
  ([session-id max-emissions]
   (let [{:keys [emit-count]} (read-state session-id)]
     (< (or emit-count 0) (or max-emissions default-max-fires)))))

(defn note-emission!
  "Record that an additionalContext emission happened in this session.
   Callers must check `emission-allowed?` first. Idempotent write: only
   the emit-count field is touched, other state is preserved."
  [session-id]
  (let [state (read-state session-id)]
    (write-state! session-id
                  (assoc state :emit-count (inc (or (:emit-count state) 0))))))

(defn- transcript-bytes [transcript-path]
  (if (and transcript-path (fs/exists? transcript-path))
    (fs/size transcript-path)
    0))

(defn should-reinject?
  "Decide whether the reinject gate should fire for this session. If it
   does, atomically update the state file so subsequent callers see the
   fresh baseline. Returns a boolean.

   byte-threshold and turn-threshold are the 'max of' trigger — whichever
   threshold is crossed first wins. A per-session fire-count cap prevents
   runaway loops in headless mode where there is no human to terminate
   the Stop-hook → additionalContext → new-turn → Stop-hook cycle."
  ([session-id transcript-path turn-count byte-threshold turn-threshold]
   (should-reinject? session-id transcript-path turn-count
                     byte-threshold turn-threshold default-max-fires))
  ([session-id transcript-path turn-count byte-threshold turn-threshold max-fires]
   (let [{:keys [last-bytes last-turn fire-count] :as _state} (read-state session-id)
         fires (or fire-count 0)
         cur-bytes (transcript-bytes transcript-path)
         bytes-grown (- cur-bytes (or last-bytes 0))
         turns-grown (- turn-count (or last-turn 0))
         under-cap? (< fires (or max-fires default-max-fires))
         fire? (and under-cap?
                    (or (>= bytes-grown (or byte-threshold 204800))
                        (>= turns-grown (or turn-threshold 10))))]
     (when fire?
       (write-state! session-id {:last-bytes cur-bytes
                                 :last-turn turn-count
                                 :fire-count (inc fires)}))
     fire?)))

(defn read-recent-judge-retrospectives
  "Return the last n retrospective strings from .succession/log/judge.jsonl.
   Empty vec if the log does not exist yet."
  [cwd n]
  (let [log-file (str cwd "/.succession/log/judge.jsonl")]
    (if (fs/exists? log-file)
      (->> (str/split-lines (slurp log-file))
           (keep (fn [line]
                   (try (let [entry (json/parse-string line true)]
                          (when (:retrospective entry)
                            entry))
                        (catch Exception _ nil))))
           (take-last n)
           vec)
      [])))

(defn build-reinject-context
  "Assemble the reinject bundle: advisory-summary + active-rules-digest +
   the last n judge retrospectives. Returns a string (possibly empty).

   The bundle is deliberately labeled so the judge can carve itself out
   (see judge-conscience-framing rule)."
  [cwd {:keys [includeJudgeRetrospectives maxRetrospectives]
        :or {includeJudgeRetrospectives true
             maxRetrospectives 5}}]
  (let [compiled-dir (str cwd "/.succession/compiled")
        advisory-file (str compiled-dir "/advisory-summary.md")
        digest-file (str compiled-dir "/active-rules-digest.md")
        parts (atom [])]

    (when (and (fs/exists? advisory-file) (pos? (fs/size advisory-file)))
      (swap! parts conj (slurp advisory-file)))

    (when (and (fs/exists? digest-file) (pos? (fs/size digest-file)))
      (swap! parts conj (str "\n--- SUCCESSION: ACTIVE RULES DIGEST ---\n"
                             (slurp digest-file))))

    (when includeJudgeRetrospectives
      (let [retros (read-recent-judge-retrospectives cwd (or maxRetrospectives 5))]
        (when (seq retros)
          (swap! parts conj
                 (str "\n[Succession conscience] Recent judge retrospectives:\n"
                      (str/join "\n"
                                (for [r retros]
                                  (str "- [" (name (or (some-> r :verdict keyword) :unknown)) "] "
                                       (or (:rule_id r) "?") ": "
                                       (or (:retrospective r) ""))))
                      "\n(These are reflections on your recent tool use. Do not judge them further.)")))))

    (str/join "\n" @parts)))
