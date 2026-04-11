(ns succession.identity.store.cards
  "Read and write identity cards to disk. One markdown file per card,
   YAML frontmatter carrying all card metadata, markdown body carrying
   the card text. Observations are NOT stored on the card — they live in
   `.succession/observations/{sess}/` and are joined at load time.

   Round-trip contract: `(-> card write-card! read-card)` returns a card
   equal to the original except for any store-added fields (file path).

   This namespace is the ONLY place that knows the on-disk file format.
   Everything else treats cards as pure data.

   Reference: `.plans/succession-identity-cycle.md` §Identity card."
  (:require [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [succession.identity.config :as config]
            [succession.identity.domain.card :as card]
            [succession.identity.store.paths :as paths]))

;; ------------------------------------------------------------------
;; Card shape ↔ on-disk representation
;; ------------------------------------------------------------------

(defn- inst->iso
  "Serialize `java.util.Date` (or java.time.Instant) to an ISO-8601
   string so YAML round-trips cleanly. `Date.toString` is locale-
   specific (`Thu Jan 01 08:00:00 SGT 2026`), so we route through
   `Instant` which always formats as ISO-8601."
  [inst]
  (cond
    (instance? java.util.Date inst) (.toString (.toInstant ^java.util.Date inst))
    (instance? java.time.Instant inst) (.toString ^java.time.Instant inst)
    :else (str inst)))

(defn- iso->inst
  "Parse an ISO-8601 string back to `java.util.Date`. Accepts strings
   and inst values (passes through)."
  [x]
  (cond
    (inst? x) x
    (string? x) (java.util.Date/from (java.time.Instant/parse x))
    :else x))

(defn- keyword-or-str
  "YAML may round-trip enumerations as either keyword or string depending
   on the writer. Coerce to keyword for our closed sets."
  [v]
  (cond
    (keyword? v) v
    (string? v)  (keyword v)
    :else        v))

(defn- card->frontmatter
  [card]
  (let [prov (:card/provenance card)]
    (cond-> {:succession/entity-type "card"
             :card/id                (:card/id card)
             :card/tier              (name (:card/tier card))
             :card/category          (name (:card/category card))
             :card/provenance
             {:provenance/born-at         (inst->iso (:provenance/born-at prov))
              :provenance/born-in-session (:provenance/born-in-session prov)
              :provenance/born-from       (name (:provenance/born-from prov))
              :provenance/born-context    (:provenance/born-context prov)}}
      (:card/tags card)        (assoc :card/tags (mapv name (:card/tags card)))
      (:card/fingerprint card) (assoc :card/fingerprint (:card/fingerprint card))
      (:card/rewrites card)    (assoc :card/rewrites (vec (:card/rewrites card))))))

(defn- frontmatter->card
  "Reverse of `card->frontmatter`. Validates via `domain/card/make-card`."
  [fm body file]
  (let [prov (or (:card/provenance fm) {})
        tags (:card/tags fm)
        tier (keyword-or-str (:card/tier fm))
        cat  (keyword-or-str (:card/category fm))]
    (-> (card/make-card
          {:id          (:card/id fm)
           :tier        tier
           :category    cat
           :text        (str/trim (or body ""))
           :tags        (when tags (mapv keyword-or-str tags))
           :fingerprint (:card/fingerprint fm)
           :provenance  {:provenance/born-at         (iso->inst (:provenance/born-at prov))
                         :provenance/born-in-session (:provenance/born-in-session prov)
                         :provenance/born-from       (keyword-or-str
                                                       (:provenance/born-from prov))
                         :provenance/born-context    (:provenance/born-context prov)}})
        (cond-> (:card/rewrites fm) (assoc :card/rewrites (vec (:card/rewrites fm)))
                file                (assoc :card/file file)))))

;; ------------------------------------------------------------------
;; File I/O
;; ------------------------------------------------------------------

(def ^:private delimiter "---")

(defn- split-frontmatter
  "Return `[fm-string body-string]` or nil if the content has no
   well-formed frontmatter."
  [content]
  (when (str/starts-with? content delimiter)
    (let [rest    (subs content (count delimiter))
          matcher (re-matcher #"(?m)^---\s*$" rest)]
      (when (.find matcher)
        [(str/trim (subs rest 0 (.start matcher)))
         (str/trim (subs rest (+ (.start matcher) (count (.group matcher)))))]))))

(defn- render-file [card]
  (let [fm-yaml (yaml/generate-string (card->frontmatter card)
                                      :dumper-options {:flow-style :block})]
    (str delimiter "\n"
         (str/trimr fm-yaml) "\n"
         delimiter "\n\n"
         (str/trim (:card/text card)) "\n")))

(defn read-card
  "Read a single card from a file path. Returns the card map with an
   added `:card/file` field. Throws if the file is not a valid card."
  [file-path]
  (let [content (slurp file-path)
        [fm-str body] (or (split-frontmatter content)
                          (throw (ex-info "no frontmatter" {:file file-path})))
        fm       (yaml/parse-string fm-str)]
    (frontmatter->card fm body file-path)))

(defn write-card!
  "Write a card to the correct tier directory. Idempotent per
   `(tier, id)` — overwrites any existing file. Returns the file path."
  [project-root card]
  (let [dir  (paths/tier-dir project-root (:card/tier card))
        _    (paths/ensure-dir! dir)
        file (paths/card-file project-root (:card/tier card) (:card/id card))]
    (spit file (render-file card))
    file))

(defn list-card-files
  "Return the seq of .md files under `identity/promoted/` for all tiers.
   Returns an empty seq if the directory does not exist yet."
  [project-root]
  (let [root (io/file (paths/promoted-dir project-root))]
    (if-not (.exists root)
      []
      (->> config/valid-tiers
           (mapcat (fn [tier]
                     (let [d (io/file (paths/tier-dir project-root tier))]
                       (when (.exists d)
                         (filter #(and (.isFile ^java.io.File %)
                                       (str/ends-with? (.getName ^java.io.File %) ".md"))
                                 (.listFiles d))))))))))

(defn load-all-cards
  "Read every card from `identity/promoted/`. Returns a vector of
   cards. Skips files that fail to parse (prints to *err* for audit)."
  [project-root]
  (->> (list-card-files project-root)
       (keep (fn [f]
               (try
                 (read-card (.getPath ^java.io.File f))
                 (catch Throwable t
                   (binding [*out* *err*]
                     (println "failed to parse card"
                              (.getPath ^java.io.File f) "-" (.getMessage t)))
                   nil))))
       vec))

;; ------------------------------------------------------------------
;; Materialized promoted.edn snapshot
;; ------------------------------------------------------------------

(defn write-promoted-snapshot!
  "Write the materialized promoted.edn snapshot. `cards` is the seq of
   cards to include. The snapshot is keyed by `:card/id` and also carries
   a `:succession/snapshot-version` so readers can reject mismatches."
  [project-root cards]
  (let [path (paths/promoted-snapshot project-root)
        payload {:succession/snapshot-version 1
                 :succession/snapshot-at      (java.util.Date.)
                 :succession/card-count       (count cards)
                 :succession/cards            (into {} (map (juxt :card/id identity) cards))}]
    (paths/ensure-dir! (paths/root project-root))
    (spit path (pr-str payload))
    path))

(defn read-promoted-snapshot
  "Read the materialized snapshot. Returns nil if no snapshot exists.
   Returns `{:cards [...] :at <Date>}` shape (flat for easy consumption
   by SessionStart)."
  [project-root]
  (let [path (paths/promoted-snapshot project-root)
        f    (io/file path)]
    (when (.exists f)
      (let [payload (read-string (slurp f))]
        {:at    (:succession/snapshot-at payload)
         :cards (vec (vals (:succession/cards payload)))}))))

(defn materialize-promoted!
  "Rescan `identity/promoted/` from disk and rewrite `promoted.edn`.
   Called by PreCompact after applying staging deltas. Returns the
   cards that were written."
  [project-root]
  (let [cards (load-all-cards project-root)]
    (write-promoted-snapshot! project-root cards)
    cards))
