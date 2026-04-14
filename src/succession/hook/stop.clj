(ns succession.hook.stop
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
     5. Enqueue an `:llm-reconcile` job so the async drain worker
        handles categories 2 + 3-at-principle. The worker writes its
        own contradictions + resolutions; this hook returns
        immediately.

   Critically, Stop does NOT emit `systemMessage` or
   `additionalContext` — plan §PostToolUse and the headless-
   continuation-loop investigation established that emitting reminders
   at Stop is wasted work (the agent is done for the turn)."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [succession.domain.reconcile :as reconcile]
            [succession.hook.common :as common]
            [succession.store.cards :as store-cards]
            [succession.store.contradictions :as store-contra]
            [succession.store.observations :as store-obs]
            [succession.store.staging :as store-staging]))

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
   of truly-new contradictions written (existing open (card-id, category)
   pairs are skipped to prevent unbounded accumulation)."
  [project-root session now config]
  (let [cards         (store-cards/load-all-cards project-root)
        all-obs       (store-obs/load-all-observations project-root)
        obs-by-card   (store-obs/observations-by-card all-obs)
        metrics       (metrics-by-card cards obs-by-card now config)
        open-existing (store-contra/open-contradictions project-root)
        open-key      (fn [c] [(get-in c [:contradiction/between 0 :card/id])
                                (:contradiction/category c)])
        open-keys     (into #{} (map open-key) open-existing)
        found         (reconcile/detect-all cards obs-by-card metrics now config)
        truly-new     (remove #(open-keys (open-key %)) found)]
    (doseq [c truly-new]
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
    truly-new))

;; ------------------------------------------------------------------
;; Public entry
;; ------------------------------------------------------------------

(defn run
  "Stop hook entry. Pure reconcile pass + enqueue an async
   :llm-reconcile job. Emits nothing on stdout — per plan, Stop is a
   background pass."
  []
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
      (common/enqueue-and-ensure-worker!
        project-root cfg
        {:type    :llm-reconcile
         :session session
         :payload {:triggered-at now}}))
    (catch Throwable t
      (binding [*out* *err*]
        (println "succession stop error:" (.getMessage t)))))
  nil)
