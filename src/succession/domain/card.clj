(ns succession.domain.card
  "Pure data shape + predicates for identity cards.

   A card is the atomic unit of identity: a single claim about who the
   agent is, carrying provenance and enough metadata that weight and tier
   can be computed from the observation log.

   Cards are data, not code. This namespace deliberately has no I/O —
   `store/cards` handles disk. `domain/render` handles serialization to
   markdown. This namespace is purely about shape and value transforms.

   Shape (namespaced keys throughout):

     {:succession/entity-type :card
      :card/id         \"prefer-edit-over-write\"   ; stable slug
      :card/tier       :principle                    ; :principle | :rule | :ethic
      :card/category   :strategy                     ; see config/valid-categories
      :card/text       \"Prefer Edit over Write ...\"
      :card/tags       [:file-editing :tooling]
      :card/fingerprint \"tool=Edit,...\"            ; optional, for pure invocation detection
      :card/provenance {:provenance/born-at          #inst \"...\"
                        :provenance/born-in-session  \"abc123\"
                        :provenance/born-from        :user-correction
                        :provenance/born-context     \"...\"}
      :card/rewrites   [\"old-card-hash\" ...]        ; optional, rewrite backlinks
      }

   Weight and tier-eligibility are NOT stored on the card. They are
   computed from the card's text/tier and its observations (which live
   in a separate log) via `domain/weight` and `domain/tier`."
  (:require [succession.config :as config]))

(defn card?
  "Predicate: `x` is a well-formed identity card."
  [x]
  (and (map? x)
       (= :card (:succession/entity-type x))
       (string? (:card/id x))
       (contains? config/valid-tiers (:card/tier x))
       (contains? config/valid-categories (:card/category x))
       (string? (:card/text x))
       (let [b (:card/tier-bounds x)]
         (or (nil? b)
             (and (or (nil? (:floor b)) (contains? config/valid-tiers (:floor b)))
                  (or (nil? (:max b))   (contains? config/valid-tiers (:max b))))))))

(defn make-card
  "Constructor with required fields + optional metadata merged in.
   Does not touch disk. Does not generate timestamps — caller provides
   provenance. This keeps the function pure and replayable."
  [{:keys [id tier category text tags fingerprint provenance tier-bounds]}]
  {:pre [(string? id)
         (contains? config/valid-tiers tier)
         (contains? config/valid-categories category)
         (string? text)
         (map? provenance)]}
  (cond-> {:succession/entity-type :card
           :card/id                id
           :card/tier              tier
           :card/category          category
           :card/text              text
           :card/provenance        provenance}
    tags        (assoc :card/tags (vec tags))
    fingerprint (assoc :card/fingerprint fingerprint)
    tier-bounds (assoc :card/tier-bounds tier-bounds)))

(defn rewrite
  "Produce a new card with updated text, preserving provenance and
   recording the rewrite in :card/rewrites. `prev-hash` is whatever the
   caller uses to identify the previous version (commonly a sha of the
   old text)."
  [card new-text prev-hash]
  {:pre [(card? card) (string? new-text) (string? prev-hash)]}
  (-> card
      (assoc :card/text new-text)
      (update :card/rewrites (fnil conj []) prev-hash)))

(defn retier
  "Produce a new card with a new tier. Used by promotion/demotion. Does
   not validate hysteresis — that's `domain/tier`'s job. This is just the
   value transform."
  [card new-tier]
  {:pre [(card? card) (contains? config/valid-tiers new-tier)]}
  (assoc card :card/tier new-tier))

(defn has-fingerprint?
  "Does this card support pure invocation detection via fingerprint?"
  [card]
  (boolean (:card/fingerprint card)))
