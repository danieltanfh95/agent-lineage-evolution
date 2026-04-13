(ns succession.hook.post-tool-use
  "PostToolUse hook — the Finding-1 hot path plus the async judge lane.

   Two lanes, per plan §PostToolUse:

   **Sync (budget <1s)**
     1. Read cached promoted.edn + staging snapshot.
     2. Rank salient cards against the completed tool call via
        `domain/salience`.
     3. Render a compact refresh reminder via `domain/render/salient-reminder`.
     4. Honor the refresh gate — integration-gap-turns, byte-threshold,
        cold-start-skip-turns. These are pacing filters, not budgets.
        Tuned against an 18-turn replay (pytest-5103, 18-0).
        `cap-per-session` was removed when the project adopted the
        infinite-context axiom — see the `infinite-context` principle
        card.
     5. Detect deterministic `:invoked`/`:confirmed` observations via
        `card/fingerprint` substring match against the tool descriptor.
        Append the observation to `store/observations`.
     6. Emit the refresh reminder as `additionalContext` + periodic
        consult advisory reminder (every N turns from config).

   **Async (filesystem-queued drain worker)**
     7. Enqueue a `:judge` job to `.succession/staging/jobs/` via
        `common/enqueue-and-ensure-worker!`. A detached `bb succession
        worker drain` process claims the job, runs `llm/judge/judge-
        tool-call`, and writes verdicts as observation files. The
        parent hook returns immediately so Claude Code is never
        blocked by the judge. The worker self-exits after the
        configured idle timeout, so a burst of tool calls reuses the
        same JVM rather than forking per-call.

   This namespace does not implement asyncRewake — that's deferred per
   plan §PostToolUse until the headless continuation loop is
   root-caused."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [succession.domain.card :as card]
            [succession.domain.observation :as dom-obs]
            [succession.domain.render :as render]
            [succession.domain.salience :as salience]
            [succession.hook.common :as common]
            [succession.store.cards :as store-cards]
            [succession.store.observations :as store-obs]
            [succession.transcript :as transcript]))

;; ------------------------------------------------------------------
;; Refresh state
;;
;; The refresh gate state lives in /tmp so it is session-scoped and
;; does not pollute the project tree. The `identity-` in the file name
;; is historical — it dates from the Phase 2 coexistence window when
;; this hook ran alongside the predecessor refresh.clj. The prefix is
;; kept because active sessions have state files under this name;
;; renaming would reset the gate and drop in-flight refresh counters.
;; ------------------------------------------------------------------

(defn- state-file [session-id]
  (str "/tmp/.succession-identity-refresh-" session-id))

(def ^:private initial-state
  {:calls 0 :emits 0 :last-emit-call 0 :last-emit-bytes 0})

(defn read-state
  [session-id]
  (let [f (io/file (state-file session-id))]
    (if (.exists f)
      (try (edn/read-string (slurp f))
           (catch Throwable _ initial-state))
      initial-state)))

(defn- write-state! [session-id state]
  (spit (state-file session-id) (pr-str state)))

(defn- transcript-bytes [transcript-path]
  (if (and transcript-path (fs/exists? transcript-path))
    (fs/size transcript-path)
    0))

(defn should-emit?
  "Gate decision — pure fn of (state, cur-bytes, gate-config).

   Pacing filters only — no hard cap on total emissions per session.
   The infinite-context axiom means drift is the enemy, not token
   budget. `integration-gap-turns` and `byte-threshold` prevent
   back-to-back redundant reminders; beyond that, fire freely."
  [{:keys [calls emits last-emit-call last-emit-bytes]}
   cur-bytes
   {:keys [integration-gap-turns byte-threshold cold-start-skip-turns]
    :or   {integration-gap-turns 2
           byte-threshold        200
           cold-start-skip-turns 1}}]
  (let [first-emit? (and (zero? (or emits 0))
                         (>= calls cold-start-skip-turns))
        calls-since (- calls (or last-emit-call 0))
        bytes-since (- cur-bytes (or last-emit-bytes 0))
        later-emit? (and (pos? (or emits 0))
                         (or (>= calls-since integration-gap-turns)
                             (>= bytes-since byte-threshold)))]
    (or first-emit? later-emit?)))

;; ------------------------------------------------------------------
;; Salient reminder
;; ------------------------------------------------------------------

(defn- tool-descriptor
  "Build a stable string that card fingerprints can substring-match
   against. Format mirrors the existing card fingerprints
   (`tool=Bash,cmd=...`)."
  [tool-name tool-input]
  (str "tool=" (or tool-name "?")
       (when tool-input
         (str ",input=" (pr-str tool-input)))))

(defn- consult-advisory
  "Periodic consult reminder — rides inside the normal refresh
   reminder. Fires on every `:every-n-turns` matched call."
  [calls every-n-turns]
  (when (and every-n-turns (pos? every-n-turns)
             (zero? (mod calls every-n-turns)))
    (str "\n\n_Consult your identity when uncertain: "
         "`bb succession consult \"<situation>\"`._")))

(defn build-reminder
  "Pure composition step — given the ranked candidates + calls-count +
   config, produce the refresh reminder text. Extracted so tests can
   drive it without stdin / disk."
  [ranked calls config]
  (let [header   "**Identity reminder**"
        base     (render/salient-reminder ranked header)
        advisory (consult-advisory calls
                                   (get-in config [:consult/advisory :every-n-turns]))]
    (str base (or advisory ""))))

;; ------------------------------------------------------------------
;; Deterministic fingerprint observation
;; ------------------------------------------------------------------

(defn- fingerprint-invocation
  "Scan cards for a fingerprint substring-match against the tool
   descriptor. Returns the first matched card or nil. The first-match
   strategy is intentional — multiple matches mean the card catalog has
   overly-generic fingerprints and should be tightened, not that the
   observation should be written N times."
  [cards descriptor]
  (first
    (filter (fn [c]
              (when-let [fp (:card/fingerprint c)]
                (str/includes? descriptor fp)))
            cards)))

(defn- write-invoked-observation!
  [project-root card session at]
  (let [obs (dom-obs/make-observation
              {:id      (str "obs-self-" (random-uuid))
               :at      at
               :session session
               :hook    :post-tool-use
               :source  :self-detect
               :card-id (:card/id card)
               :kind    :invoked
               :context (str "fingerprint match: " (:card/fingerprint card))})]
    (store-obs/write-observation! project-root obs)
    obs))

;; ------------------------------------------------------------------
;; Public entry
;; ------------------------------------------------------------------

(defn run
  "PostToolUse hook entry. Runs the sync refresh lane, then enqueues
   a :judge job for the async drain worker. Never throws — any error
   is logged to stderr and swallowed. Claude Code sees at most one
   JSON blob on stdout from the sync lane."
  []
  (try
    (let [raw-stdin    (try (slurp *in*) (catch Throwable _ ""))
          input        (try (if (str/blank? raw-stdin) {}
                                (json/parse-string raw-stdin true))
                            (catch Throwable _ {}))
          project-root (common/project-root input)
          session      (or (:session_id input) "unknown")
          tool-name    (:tool_name input)
          tool-input   (:tool_input input)
          transcript   (:transcript_path input)
          now          (java.util.Date.)
          cfg          (common/load-config input)
          cards        (or (:cards (store-cards/read-promoted-snapshot project-root)) [])
          descriptor   (tool-descriptor tool-name tool-input)

          ;; Update refresh state first (matches port semantics).
          prev-state   (read-state session)
          state'       (-> prev-state (update :calls (fnil inc 0)))
          cur-bytes    (transcript-bytes transcript)
          emit?        (should-emit? state' cur-bytes (:refresh/gate cfg))

          scored       (common/score-cards project-root cards cfg now)
          situation    {:situation/text "after tool call"
                        :situation/tool-descriptor descriptor}
          ranked       (salience/rank scored situation cfg)]

      ;; Deterministic fingerprint observation — always runs, gate-independent
      (when-let [matched (fingerprint-invocation cards descriptor)]
        (write-invoked-observation! project-root matched session now))

      ;; Refresh emission path
      (if emit?
        (let [reminder (build-reminder ranked (:calls state') cfg)]
          (write-state! session
                        (assoc state'
                               :emits (inc (or (:emits state') 0))
                               :last-emit-call (:calls state')
                               :last-emit-bytes cur-bytes))
          (common/emit-additional-context! "PostToolUse" reminder))
        (write-state! session state'))

      ;; Async judge lane — enqueue a job and kick the drain worker.
      ;; Read recent transcript context so the judge can see what the
      ;; user asked for, not just the bare tool call.
      (let [ctx-window (get-in cfg [:judge/llm :context-window])
            recent     (transcript/recent-context transcript ctx-window)]
        (common/enqueue-and-ensure-worker!
          project-root cfg
          {:type    :judge
           :session session
           :payload {:tool-name      tool-name
                     :tool-input     tool-input
                     :tool-response  (:tool_response input)
                     :recent-context recent}})))
    (catch Throwable t
      (binding [*out* *err*]
        (println "succession post-tool-use error:" (.getMessage t)))))
  nil)
