(ns succession.hook.pre-compact
  "PreCompact hook — the ONLY real promotion site.

   This is where intra-session delta logs turn into canonical identity
   cards. Everything else is append-only live state; only PreCompact
   takes the advisory lock and rewrites `identity/promoted/`.

   Steps, per plan §PreCompact:

     1. Acquire promote lock (`store/locks/with-lock`).
     2. Archive current promoted tree to `archive/{ts}/` (snapshot).
     3. Load current cards + staging deltas.
     4. Apply deltas against a working copy of cards.
     5. Recompute metrics + eligible tiers; apply promote/demote.
     6. Clear the old promoted tier dirs, rewrite from the working copy.
     7. Regenerate the `promoted.edn` materialized snapshot.
     8. Clear the session's staging dir.
     9. Release lock.

   PreCompact emits no `additionalContext` — it runs at compaction time
   when the model is not reading hook output anyway."
  (:require [clojure.java.io :as io]
            [succession.config :as config]
            [succession.domain.card :as card]
            [succession.domain.tier :as tier]
            [succession.hook.common :as common]
            [succession.store.archive :as archive]
            [succession.store.cards :as store-cards]
            [succession.store.contradictions :as store-contra]
            [succession.store.locks :as locks]
            [succession.store.observations :as store-obs]
            [succession.store.sessions :as store-sessions]
            [succession.store.staging :as store-staging]))

;; ------------------------------------------------------------------
;; Delta application — per plan §PreCompact step-per-delta list
;; ------------------------------------------------------------------

(defn- apply-create-card
  "Turn a `:create-card` delta payload into a new card and add it to
   the working map. Cards start at `:ethic` unless the payload says
   otherwise; the extract LLM is forbidden from assigning tiers."
  [cards-by-id payload session now]
  (if-let [id (:id payload)]
    (let [new-card
          (card/make-card
            {:id          id
             :tier        (or (:tier payload) :ethic)
             :category    (or (:category payload) :strategy)
             :text        (or (:text payload) "")
             :tags        (:tags payload)
             :fingerprint (:fingerprint payload)
             :provenance  {:provenance/born-at         now
                           :provenance/born-in-session session
                           :provenance/born-from       (or (:source payload) :extract)
                           :provenance/born-context    (or (:provenance-context payload) "")}})]
      (assoc cards-by-id id new-card))
    cards-by-id))

(defn- apply-update-text
  "Rewrite an existing card's text, preserving provenance and appending
   a `:rewrites` backlink via `domain/card/rewrite`."
  [cards-by-id card-id payload]
  (if-let [existing (get cards-by-id card-id)]
    (assoc cards-by-id card-id
           (card/rewrite existing
                         (or (:text payload) (:card/text existing))
                         (or (:prev-hash payload)
                             (str "h-" (hash (:card/text existing))))))
    cards-by-id))

(defn- apply-tier
  [cards-by-id card-id payload]
  (if-let [existing (get cards-by-id card-id)]
    (let [new-tier (:tier payload)]
      (if (contains? config/valid-tiers new-tier)
        (assoc cards-by-id card-id (card/retier existing new-tier))
        cards-by-id))
    cards-by-id))

(defn- apply-merge
  "Merge two cards into a single surviving card with the merged text.
   The payload carries `:source-ids` (a two-element seq) and optionally
   `:survivor-id` (default: first id). The loser is dropped from the
   working map; its observation history remains on disk and still
   contributes to the survivor via rollup (card-id is not rewritten)."
  [cards-by-id payload]
  (let [[a-id b-id] (:source-ids payload)
        survivor-id (or (:survivor-id payload) a-id)
        loser-id    (first (remove #(= % survivor-id) [a-id b-id]))]
    (if-let [survivor (get cards-by-id survivor-id)]
      (-> cards-by-id
          (assoc survivor-id
                 (card/rewrite survivor
                               (or (:text payload) (:card/text survivor))
                               (str "merge-" (hash [a-id b-id]))))
          (cond-> loser-id (dissoc loser-id)))
      cards-by-id)))

(defn apply-delta
  "Pure step: fold one delta over the current working map of cards.
   `:observe-card` and `:mark-contradiction` are no-ops at card-rewrite
   time — observations already live in their own files, and
   contradictions are written through `store/contradictions` at
   detection time."
  [cards-by-id delta session now]
  (let [k       (:delta/kind delta)
        id      (:delta/card-id delta)
        payload (:delta/payload delta)]
    (case k
      :observe-card         cards-by-id
      :create-card          (apply-create-card cards-by-id payload session now)
      :update-card-text     (apply-update-text cards-by-id id payload)
      :propose-tier         (apply-tier cards-by-id id payload)
      :propose-merge        (apply-merge cards-by-id payload)
      :mark-contradiction   cards-by-id
      cards-by-id)))

;; ------------------------------------------------------------------
;; Retier by metrics
;; ------------------------------------------------------------------

(defn retier-by-metrics
  "After deltas are applied, walk every card and ask `domain/tier`
   whether its declared tier still matches the eligible tier under
   current metrics. Applies promotions and demotions — hysteresis is
   enforced inside `eligible-tier`, so a card near a threshold will
   not flicker. If the card carries `:card/tier-bounds`, the result
   is clamped to those bounds before writing."
  [cards-by-id observations-by-card now config]
  (into {}
        (map (fn [[id c]]
               (let [obs   (get observations-by-card id [])
                     m     (common/metrics-for obs now config)
                     tr    (tier/propose-transition c m config)
                     raw   (case (:kind tr)
                             (:promote :demote) (:to tr)
                             (:card/tier c))
                     final (tier/apply-bounds raw (:card/tier-bounds c))]
                 [id (card/retier c final)])))
        cards-by-id))

;; ------------------------------------------------------------------
;; LLM rewrite application
;; ------------------------------------------------------------------

(defn- apply-pending-llm-rewrites
  "After retier, apply any LLM-proposed :rewrite or :scope-qualify
   resolutions that haven't been applied yet (:applied-at is nil).
   Returns updated cards-by-id."
  [cards-by-id project-root now]
  (let [pending (->> (store-contra/load-all-contradictions project-root)
                     (filter (fn [c]
                               (and (= :llm-reconcile (:contradiction/resolved-by c))
                                    (contains? #{:rewrite :scope-qualify}
                                               (get-in c [:contradiction/resolution :kind]))
                                    (nil? (get-in c [:contradiction/resolution :applied-at]))))))]
    (reduce (fn [acc c]
              (let [card-id  (get-in c [:contradiction/between 0 :card/id])
                    new-text (get-in c [:contradiction/resolution :new-text])
                    card     (get acc card-id)]
                (if (and card new-text)
                  (do (store-contra/mark-rewrite-applied! project-root (:contradiction/id c) now)
                      (assoc acc card-id
                             (card/rewrite card new-text
                                           (str "llm-" (:contradiction/id c)))))
                  acc)))
            cards-by-id pending)))

;; ------------------------------------------------------------------
;; Filesystem operations
;; ------------------------------------------------------------------

(defn- clear-tier-files!
  "Delete every `.md` file under `identity/promoted/{tier}/` so we can
   rewrite the tree from the working map. Safe because we took an
   archive snapshot first."
  [project-root]
  (doseq [^java.io.File f (store-cards/list-card-files project-root)]
    (.delete f)))

(defn- write-all-cards!
  [project-root cards-by-id]
  (doseq [c (vals cards-by-id)]
    (store-cards/write-card! project-root c)))

;; ------------------------------------------------------------------
;; Public entry — called via core dispatcher
;; ------------------------------------------------------------------

(defn promote!
  "Core promotion logic, extracted from `run` so tests can drive it
   with a synthetic (project-root, session, now) triple without having
   to fake stdin. Returns the new cards-by-id map."
  [project-root session now config]
  (locks/with-lock [_handle project-root]
    (archive/snapshot! project-root now)
    (let [initial     (into {} (map (juxt :card/id identity))
                            (store-cards/load-all-cards project-root))
          deltas      (store-staging/load-deltas project-root session)
          after-apply (reduce (fn [acc d] (apply-delta acc d session now))
                              initial deltas)
          obs-by-card (store-obs/observations-by-card
                        (store-obs/load-observations-for-cards project-root (set (keys after-apply))))
          after-retier (retier-by-metrics after-apply obs-by-card now config)]
      (let [after-llm-rewrites (apply-pending-llm-rewrites after-retier project-root now)]
        (clear-tier-files! project-root)
        (write-all-cards! project-root after-llm-rewrites))
      (store-cards/materialize-promoted! project-root)
      ;; Fix 3: resolve all open tier-violation contradictions — retier just ran,
      ;; so any remaining :tier-violation is stale by invariant.
      (let [open-tier-violations (->> (store-contra/load-all-contradictions project-root)
                                      (filter #(and (= :tier-violation (:contradiction/category %))
                                                    (nil? (:contradiction/resolved-at %)))))]
        (doseq [c open-tier-violations]
          (store-contra/mark-resolved! project-root (:contradiction/id c) :pre-compact now)))
      ;; Dedup: for each (card, category) pair, keep only the most recent open contradiction.
      ;; Card lives in [:contradiction/between 0 :card/id]; sort oldest-first by :contradiction/at.
      (let [by-card-cat (->> (store-contra/load-all-contradictions project-root)
                             (remove :contradiction/resolved-at)
                             (group-by (juxt #(get-in % [:contradiction/between 0 :card/id])
                                             :contradiction/category)))
            to-resolve  (mapcat (fn [[_ group]]
                                  (when (> (count group) 1)
                                    (butlast (sort-by :contradiction/at group))))
                                by-card-cat)]
        (doseq [c to-resolve]
          (store-contra/mark-resolved! project-root (:contradiction/id c) :pre-compact-dedup now)))
      (store-staging/clear-session! project-root session)
      ;; Fix 1: clear orphan staging dirs accumulated from prior sessions.
      (doseq [orphan-id (store-sessions/orphan-staging project-root session)]
        (store-staging/clear-session! project-root orphan-id))
      after-retier)))

(defn run
  "Hook entry. Reads stdin, promotes, silent on success. Errors are
   logged to *err* but never thrown — a failing PreCompact must not
   block compaction."
  []
  (try
    (let [input        (common/read-input)
          project-root (common/project-root input)
          session      (or (:session_id input) "unknown")
          now          (java.util.Date.)
          cfg          (common/load-config input)]
      (promote! project-root session now cfg))
    (catch Throwable t
      (binding [*out* *err*]
        (println "succession pre-compact error:" (.getMessage t)))))
  nil)
