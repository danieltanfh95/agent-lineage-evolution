(ns succession.identity.hook.post-tool-use
  "PostToolUse hook — the Finding-1 hot path plus the async judge lane.

   Two lanes, per plan §PostToolUse:

   **Sync (budget <1s)**
     1. Read cached promoted.edn + staging snapshot.
     2. Rank salient cards against the completed tool call via
        `domain/salience`.
     3. Render a compact refresh reminder via `domain/render/salient-reminder`.
     4. Honor the refresh gate ported from `succession.refresh` —
        integration-gap-turns, cap-per-session, byte-threshold,
        cold-start-skip-turns. These values are tuned and load-bearing
        per Finding 1; do NOT re-derive.
     5. Detect deterministic `:invoked`/`:confirmed` observations via
        `card/fingerprint` substring match against the tool descriptor.
        Append the observation to `store/observations`.
     6. Emit the refresh reminder as `additionalContext` + periodic
        consult advisory reminder (every N turns from config).

   **Async (detached subprocess)**
     7. Spawn a detached `claude -p` judge child that reads the same
        tool call, judges it against current promoted+staging, writes
        its verdicts as observation files. The parent returns stdout
        immediately so Claude Code is never blocked by the judge.

   This namespace does not implement asyncRewake — that's deferred per
   plan §PostToolUse until the headless continuation loop is
   root-caused."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [babashka.process :as process]
            [succession.identity.domain.card :as card]
            [succession.identity.domain.observation :as dom-obs]
            [succession.identity.domain.render :as render]
            [succession.identity.domain.salience :as salience]
            [succession.identity.hook.common :as common]
            [succession.identity.store.cards :as store-cards]
            [succession.identity.store.observations :as store-obs]))

;; ------------------------------------------------------------------
;; Refresh state — port of `succession.refresh`
;;
;; The refresh gate state lives in /tmp so it is session-scoped and
;; does not pollute the project tree. The file key includes an
;; `identity-` prefix so the old `succession.refresh` state (from the
;; current in-production refresh.clj) cannot be accidentally read.
;; Both systems can safely run side-by-side until Phase 3 cutover.
;; ------------------------------------------------------------------

(defn- state-file [session-id]
  (str "/tmp/.succession-identity-refresh-" session-id))

(def ^:private initial-state
  {:calls 0 :emits 0 :last-emit-call 0 :last-emit-bytes 0})

(defn read-state
  [session-id]
  (let [f (io/file (state-file session-id))]
    (if (.exists f)
      (try (read-string (slurp f))
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

   Ported from `succession.refresh/should-emit?` so Finding 1's tuned
   values carry over 1:1. The field names are renamed to match the
   plan's `:refresh/gate` key names."
  [{:keys [calls emits last-emit-call last-emit-bytes]}
   cur-bytes
   {:keys [integration-gap-turns cap-per-session byte-threshold cold-start-skip-turns]
    :or   {integration-gap-turns 2
           cap-per-session       5
           byte-threshold        200
           cold-start-skip-turns 1}}]
  (let [under-cap?  (or (nil? cap-per-session) (< (or emits 0) cap-per-session))
        first-emit? (and (zero? (or emits 0))
                         (>= calls cold-start-skip-turns))
        calls-since (- calls (or last-emit-call 0))
        bytes-since (- cur-bytes (or last-emit-bytes 0))
        later-emit? (and (pos? (or emits 0))
                         (or (>= calls-since integration-gap-turns)
                             (>= bytes-since byte-threshold)))]
    (and under-cap? (or first-emit? later-emit?))))

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
;; Async judge lane — detached subprocess
;;
;; The judge reads the same hook payload from stdin and runs
;; `llm/judge/judge-tool-call`, writing its verdicts as observation
;; files. Critically the subprocess inherits
;; SUCCESSION_JUDGE_SUBPROCESS=1 so the child's own tool calls do not
;; re-trigger this hook and cause infinite recursion.
;; ------------------------------------------------------------------

(defn- src-root
  "Best-effort location of `bb/src` for the -cp flag. Most project
   layouts have the src tree at `<root>/bb/src`; fall back to the raw
   user.dir for development."
  []
  (let [here (System/getProperty "user.dir")
        candidate (io/file here "bb" "src")]
    (if (.exists candidate)
      (.getPath candidate)
      (or (System/getenv "SUCCESSION_BB_SRC") here))))

(defn spawn-judge!
  "Fork a detached `bb` subprocess that runs
   `hook.post-tool-use/run-judge-from-stdin!`. Returns immediately
   without blocking on the child. Never throws — if spawn fails, the
   sync lane still emitted its refresh reminder, so the session is
   degraded but operational."
  [raw-input]
  (try
    (let [env   (assoc (into {} (System/getenv))
                       "SUCCESSION_JUDGE_SUBPROCESS" "1")
          child "(require 'succession.identity.hook.post-tool-use) (succession.identity.hook.post-tool-use/run-judge-from-stdin!)"]
      (process/process
        {:in        raw-input
         :out       "/tmp/.succession-identity-judge-async.log"
         :err       "/tmp/.succession-identity-judge-async.log"
         :extra-env env
         :shutdown  nil}
        "bb" "-cp" (src-root) "-e" child))
    (catch Throwable _ nil)))

(defn run-judge-from-stdin!
  "Child-process entrypoint. Reads the same payload the parent got,
   calls the judge LLM, and writes observation files. Never emits
   stdout — the parent already returned to the harness long before
   this finishes."
  []
  (try
    (let [input        (common/read-input)
          project-root (common/project-root input)
          session      (or (:session_id input) "unknown")
          now          (java.util.Date.)
          cfg          (common/load-config input)
          cards        (or (:cards (store-cards/read-promoted-snapshot project-root)) [])
          ctx          {:tool-name     (:tool_name input)
                        :tool-input    (:tool_input input)
                        :tool-response (:tool_response input)
                        :cards         cards
                        :session       session
                        :at            now
                        :hook          :post-tool-use
                        :id-fn         #(str "obs-judge-" (random-uuid))}
          judge-ns     (requiring-resolve 'succession.identity.llm.judge/judge-tool-call)
          result       (when judge-ns (judge-ns ctx cfg))]
      (doseq [o (:observations result)]
        (store-obs/write-observation! project-root o)))
    (catch Throwable _ nil)))

;; ------------------------------------------------------------------
;; Public entry
;; ------------------------------------------------------------------

(defn- subprocess?
  "Detect the SUCCESSION_JUDGE_SUBPROCESS=1 marker set by spawn-judge!.
   When we are the child we must not recurse into the parent's hook
   flow — we only run the judge lane."
  []
  (= "1" (System/getenv "SUCCESSION_JUDGE_SUBPROCESS")))

(defn run
  "PostToolUse hook entry. Runs the sync refresh lane, then tries to
   spawn the async judge lane. Never throws — any error is logged to
   stderr and swallowed. Claude Code sees at most one JSON blob on
   stdout from the sync lane."
  []
  (when-not (subprocess?)
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

        ;; Async judge lane (best-effort spawn, never blocks)
        (spawn-judge! raw-stdin))
      (catch Throwable t
        (binding [*out* *err*]
          (println "succession.identity post-tool-use error:" (.getMessage t))))))
  nil)
