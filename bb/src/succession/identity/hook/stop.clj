(ns succession.identity.hook.stop
  "Stop hook — end-of-turn reconcile pass.

   Runs when the user's turn ends (the `Stop` hook event). Per plan
   §Stop, this hook is the session-end reconcile site:

     1. Load all observations for the current session.
     2. Group by card id.
     3. Run `domain/reconcile/detect-all` — the pure pass covers
        categories 1, 4, 5, 6. Each contradiction is written to the
        canonical contradictions store and staged as a
        `:mark-contradiction` delta so PreCompact can resolve at the
        next promotion.
     4. Rematerialize the staging snapshot so consult's hot-path view
        reflects what reconcile just saw.
     5. Spawn an async `bb` subprocess for the LLM reconcile lane
        (categories 2 + 3-at-principle). Subprocess writes its own
        contradictions + resolutions; parent returns immediately.

   Critically, Stop does NOT emit `systemMessage` or
   `additionalContext` — plan §PostToolUse and the headless-
   continuation-loop investigation established that emitting reminders
   at Stop is wasted work (the agent is done for the turn).

   The subprocess recursion guard uses the same
   SUCCESSION_JUDGE_SUBPROCESS=1 env var as post-tool-use, because the
   same guard applies: if the reconcile child spawns its own tool
   calls they must not re-enter the hook pipeline."
  (:require [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [succession.identity.domain.reconcile :as reconcile]
            [succession.identity.hook.common :as common]
            [succession.identity.store.cards :as store-cards]
            [succession.identity.store.contradictions :as store-contra]
            [succession.identity.store.observations :as store-obs]
            [succession.identity.store.staging :as store-staging]))

;; ------------------------------------------------------------------
;; Pure reconcile pass
;; ------------------------------------------------------------------

(defn- metrics-by-card
  "Build the `card-id → {:weight :violation-rate :gap-crossings}` map
   expected by `domain/reconcile/detect-all`. This mirrors
   `hook/common/metrics-for` but runs across every card at once."
  [cards obs-by-card now config]
  (into {}
        (map (fn [c]
               (let [obs (get obs-by-card (:card/id c) [])]
                 [(:card/id c) (common/metrics-for obs now config)])))
        cards))

(defn run-pure-reconcile!
  "Core pure pass, extracted for tests. Loads observations, runs
   detectors, writes contradictions + staging deltas. Returns the seq
   of contradictions found."
  [project-root session now config]
  (let [cards       (store-cards/load-all-cards project-root)
        all-obs     (store-obs/load-all-observations project-root)
        obs-by-card (store-obs/observations-by-card all-obs)
        metrics     (metrics-by-card cards obs-by-card now config)
        found       (reconcile/detect-all cards obs-by-card metrics now config)]
    (doseq [c found]
      (store-contra/write-contradiction! project-root c)
      (store-contra/append-to-session-log! project-root session c)
      (store-staging/append-delta!
        project-root session
        (store-staging/make-delta
          {:id      (str "d-reconcile-" (:contradiction/id c))
           :at      now
           :kind    :mark-contradiction
           :card-id (get-in c [:contradiction/between 0 :card/id])
           :payload {:contradiction/id (:contradiction/id c)
                     :contradiction/category (:contradiction/category c)}
           :source  :reconcile})))
    (store-staging/rematerialize! project-root session)
    found))

;; ------------------------------------------------------------------
;; Async LLM reconcile lane
;; ------------------------------------------------------------------

(defn- src-root []
  (let [here      (System/getProperty "user.dir")
        candidate (io/file here "bb" "src")]
    (if (.exists candidate)
      (.getPath candidate)
      (or (System/getenv "SUCCESSION_BB_SRC") here))))

(defn spawn-llm-reconcile!
  "Fork a detached `bb` subprocess that runs
   `hook.stop/run-llm-reconcile-from-stdin!`. Returns immediately.
   Never throws — failure just leaves categories 2 + 3-at-principle
   unresolved until the next Stop."
  [raw-input]
  (try
    (let [env   (assoc (into {} (System/getenv))
                       "SUCCESSION_JUDGE_SUBPROCESS" "1")
          child "(require 'succession.identity.hook.stop) (succession.identity.hook.stop/run-llm-reconcile-from-stdin!)"]
      (process/process
        {:in        raw-input
         :out       "/tmp/.succession-identity-reconcile-async.log"
         :err       "/tmp/.succession-identity-reconcile-async.log"
         :extra-env env
         :shutdown  nil}
        "bb" "-cp" (src-root) "-e" child))
    (catch Throwable _ nil)))

(defn run-llm-reconcile-from-stdin!
  "Child-process entrypoint. Loads the parent's pure contradictions +
   open contradictions and hands off to `llm/reconcile`. Each
   auto-applicable resolution is marked resolved via
   `store/contradictions/mark-resolved!`. Non-auto resolutions are
   left for user escalation (future work — see plan §Reconcile
   pipeline)."
  []
  (try
    (let [input         (common/read-input)
          project-root  (common/project-root input)
          session       (or (:session_id input) "unknown")
          now           (java.util.Date.)
          cfg           (common/load-config input)
          open          (store-contra/open-contradictions project-root)
          llm-ns        (requiring-resolve 'succession.identity.llm.reconcile/resolve-category-2)
          llm-p-ns      (requiring-resolve 'succession.identity.llm.reconcile/resolve-category-3-principle)
          auto-ns       (requiring-resolve 'succession.identity.llm.reconcile/auto-applicable?)]
      (doseq [c open]
        (let [cat    (:contradiction/category c)
              result (cond
                       (= cat :semantic-opposition)
                       (when llm-ns (llm-ns {:contradiction c} cfg))
                       (= cat :principle-violated)
                       (when llm-p-ns (llm-p-ns {:contradiction c} cfg))
                       :else nil)]
          (when (and result (:ok? result) auto-ns
                     (auto-ns (:resolution result) cfg))
            (store-contra/mark-resolved! project-root
                                         (:contradiction/id c)
                                         :llm-reconcile
                                         now)))))
    (catch Throwable _ nil)))

;; ------------------------------------------------------------------
;; Public entry
;; ------------------------------------------------------------------

(defn- subprocess? []
  (= "1" (System/getenv "SUCCESSION_JUDGE_SUBPROCESS")))

(defn run
  "Stop hook entry. Pure reconcile pass + async LLM lane spawn.
   Emits nothing on stdout — per plan, Stop is a background pass."
  []
  (when-not (subprocess?)
    (try
      (let [raw-stdin    (try (slurp *in*) (catch Throwable _ ""))
            input        (try (if (str/blank? raw-stdin) {}
                                  (json/parse-string raw-stdin true))
                              (catch Throwable _ {}))
            project-root (common/project-root input)
            session      (or (:session_id input) "unknown")
            now          (java.util.Date.)
            cfg          (common/load-config input)]
        (run-pure-reconcile! project-root session now cfg)
        (spawn-llm-reconcile! raw-stdin))
      (catch Throwable t
        (binding [*out* *err*]
          (println "succession.identity stop error:" (.getMessage t))))))
  nil)
