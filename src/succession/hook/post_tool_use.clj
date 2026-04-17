(ns succession.hook.post-tool-use
  "PostToolUse hook — the Finding-1 hot path plus the async judge lane.

   Two lanes, per plan §PostToolUse:

   **Sync (budget <1s)**
     1. Read cached promoted.edn + staging snapshot.
     2. Rank salient cards against the completed tool call via
        `domain/salience`.
     3. Render a compact refresh reminder via `domain/render/salient-reminder`.
     4. Honor the refresh gate — byte-delta only (byte-threshold,
        cold-start-skip-bytes). No call counting, no wall-clock. The
        gate state is shared with PreToolUse, so parallel tool batches
        deduplicate naturally at the byte-count level.
     5. Detect deterministic `:invoked`/`:confirmed` observations via
        `card/fingerprint` substring match against the tool descriptor.
        Append the observation to `store/observations`.
     6. Emit the refresh reminder as `additionalContext` + periodic
        consult advisory reminder (every N turns from config).

   **Async (filesystem-queued drain worker)**
     7. Enqueue a `:judge` job to `.succession/staging/jobs/` via
        `common/enqueue-and-ensure-worker!`. A detached `succession
        worker drain` process claims the job, runs `llm/judge/judge-
        tool-call`, and writes verdicts as observation files. The
        parent hook returns immediately so Claude Code is never
        blocked by the judge. The worker self-exits after the
        configured idle timeout, so a burst of tool calls reuses the
        same JVM rather than forking per-call.

   This namespace does not implement asyncRewake — that's deferred per
   plan §PostToolUse until the headless continuation loop is
   root-caused."
  (:require [clojure.string :as str]
            [succession.domain.card :as card]
            [succession.domain.observation :as dom-obs]
            [succession.domain.render :as render]
            [succession.domain.salience :as salience]
            [succession.hook.common :as common]
            [succession.store.cards :as store-cards]
            [succession.store.observations :as store-obs]
            [succession.transcript :as transcript]))

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
   reminder. Fires on every `:every-n-emits` matched emit."
  [emits every-n-emits]
  (when (and every-n-emits (pos? every-n-emits) (pos? emits)
             (zero? (mod emits every-n-emits)))
    (str "\n\n_Consult your identity when uncertain: "
         "`succession consult \"<situation>\"`._")))

(defn build-reminder
  "Pure composition step — given the ranked candidates + emits-count +
   config, produce the refresh reminder text. Extracted so tests can
   drive it without stdin / disk."
  [ranked emits config]
  (let [header   "**Identity reminder**"
        base     (render/salient-reminder ranked header)
        advisory (consult-advisory emits
                                   (get-in config [:consult/advisory :every-n-emits]))]
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
;; Consult re-emit
;; ------------------------------------------------------------------

(defn- succession-consult-call?
  "Returns true when the tool call was `succession consult [...]`.
   `tool-input` is the parsed hook payload map — coercing to str is
   sufficient because the :command value will contain the substring."
  [tool-name tool-input]
  (and (= "Bash" tool-name)
       (some-> tool-input str (str/includes? "succession consult"))))

;; ------------------------------------------------------------------
;; Public entry
;; ------------------------------------------------------------------

(defn run
  "PostToolUse hook entry. Runs the sync refresh lane, then enqueues
   a :judge job for the async drain worker. Never throws — any error
   is logged to stderr and swallowed. Normally emits one JSON blob on
   stdout; emits a second when the tool call was `succession consult`
   (the consult re-emit path)."
  []
  (try
    (let [input        (common/read-input)
          project-root (common/project-root input)
          session      (or (:session_id input) "unknown")
          tool-name    (:tool_name input)
          tool-input   (:tool_input input)
          transcript   (:transcript_path input)
          now          (java.util.Date.)
          cfg          (common/load-config input)
          cards        (or (:cards (store-cards/read-promoted-snapshot project-root)) [])
          descriptor   (tool-descriptor tool-name tool-input)

          ;; Refresh gate — context-size only, no call counting
          prev-state   (common/read-refresh-state session)
          cur-bytes    (common/transcript-bytes transcript)
          emit?        (common/should-emit? prev-state cur-bytes (:refresh/gate cfg))

          ;; Read recent-context once — used for both salience and judge
          ctx-window   (get-in cfg [:judge/llm :context-window])
          recent       (transcript/recent-context transcript ctx-window)

          scored       (common/score-cards project-root cards cfg now)
          situation    {:situation/text "after tool call"
                        :situation/tool-descriptor descriptor
                        :situation/recent-context recent}
          ranked       (salience/rank scored situation cfg)]

      ;; Deterministic fingerprint observation — always runs, gate-independent
      (when-let [matched (fingerprint-invocation cards descriptor)]
        (write-invoked-observation! project-root matched session now))

      ;; Refresh emission path
      (when emit?
        (let [new-emits (inc (or (:emits prev-state) 0))
              reminder  (build-reminder ranked new-emits cfg)]
          (common/write-refresh-state! session
                                       {:emits           new-emits
                                        :last-emit-bytes cur-bytes})
          (common/emit-additional-context! "PostToolUse" reminder)))

      ;; Re-emit consult output as additionalContext so it surfaces in the
      ;; conversation rather than sitting in a collapsed Bash tool result.
      (when (succession-consult-call? tool-name tool-input)
        (when-let [response (not-empty (str/trim (str (:tool_response input))))]
          (common/emit-additional-context!
            "PostToolUse"
            (str "**[Succession consult]**\n" response))))

      ;; Async judge lane — enqueue a job and kick the drain worker.
      ;; recent-context already read above for salience ranking.
      (common/enqueue-and-ensure-worker!
        project-root cfg
        {:type    :judge
         :session session
         :payload {:tool-name      tool-name
                   :tool-input     tool-input
                   :tool-response  (:tool_response input)
                   :recent-context recent}}))
    (catch Throwable t
      (binding [*out* *err*]
        (println "succession post-tool-use error:" (.getMessage t)))))
  nil)
