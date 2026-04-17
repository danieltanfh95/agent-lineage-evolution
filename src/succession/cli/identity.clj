(ns succession.cli.identity
  "`succession identity` — inspect and edit identity cards directly.

   Subcommands:

     list
       Tabular view: id, tier, bounds (floor/max if set), category.

     show <card-id>
       Verbose single card: all fields.

     set <card-id> [--tier T] [--tier-floor T] [--tier-max T] [--no-bounds]
       Edit card properties in place. Writes via `write-card!` and calls
       `materialize-promoted!`. Bypasses `promote!` — tier changes survive
       the next compact only if a floor bound is also set (or metrics have
       lifted the card above the exit threshold).

   Exit 0 on success, 1 on unknown card id or invalid tier value.

   Reference: `docs/MANUAL.md` §identity."
  (:require [clojure.string :as str]
            [succession.config :as config]
            [succession.domain.card :as card]
            [succession.store.cards :as store-cards]))

;; ------------------------------------------------------------------
;; Formatting helpers
;; ------------------------------------------------------------------

(defn- format-bounds
  "Render a bounds map as `floor=rule` / `floor=rule,max=principle` / `-`."
  [bounds]
  (if (nil? bounds)
    "-"
    (let [parts (keep identity
                      [(when (:floor bounds) (str "floor=" (name (:floor bounds))))
                       (when (:max bounds)   (str "max="   (name (:max bounds))))])]
      (if (seq parts) (str/join "," parts) "-"))))

(defn- format-friction
  "Render friction as keyword name or `-` if nil."
  [friction]
  (if friction (name friction) "-"))

(defn- count-human-sections
  "Count human-authored sections in a card."
  [card]
  (count (filter #(= :human (:source %)) (:card/sections card))))

;; ------------------------------------------------------------------
;; list
;; ------------------------------------------------------------------

(defn- run-list [project-root]
  (let [cards (sort-by :card/id (store-cards/load-all-cards project-root))]
    (println (format "%-30s  %-9s  %-8s  %-16s  %s"
                     "id" "tier" "friction" "bounds" "category"))
    (println (format "%-30s  %-9s  %-8s  %-16s  %s"
                     (apply str (repeat 30 "-"))
                     (apply str (repeat 9 "-"))
                     (apply str (repeat 8 "-"))
                     (apply str (repeat 16 "-"))
                     (apply str (repeat 9 "-"))))
    (doseq [c cards]
      (println (format "%-30s  %-9s  %-8s  %-16s  %s"
                       (:card/id c)
                       (name (:card/tier c))
                       (format-friction (:card/friction c))
                       (format-bounds (:card/tier-bounds c))
                       (name (:card/category c)))))
    0))

;; ------------------------------------------------------------------
;; show
;; ------------------------------------------------------------------

(defn- run-show [project-root card-id]
  (let [cards (store-cards/load-all-cards project-root)
        c     (first (filter #(= card-id (:card/id %)) cards))]
    (if-not c
      (do (binding [*out* *err*]
            (println (str "unknown card id: " card-id)))
          1)
      (let [prov     (:card/provenance c)
            sections (:card/sections c)
            human-n  (count-human-sections c)]
        (println (str "id:       " (:card/id c)))
        (println (str "tier:     " (name (:card/tier c))))
        (println (str "friction: " (format-friction (:card/friction c))))
        (println (str "bounds:   " (format-bounds (:card/tier-bounds c))))
        (println (str "category: " (name (:card/category c))))
        (when sections
          (println (str "sections: " (count sections) " total, " human-n " human")))
        (when (:card/tags c)
          (println (str "tags:     " (str/join ", " (map name (:card/tags c))))))
        (when (:card/fingerprint c)
          (println (str "fingerprint: " (:card/fingerprint c))))
        (println (str "born-at:  " (:provenance/born-at prov)))
        (println (str "born-from: " (name (:provenance/born-from prov))))
        (println)
        (println (:card/text c))
        0))))

;; ------------------------------------------------------------------
;; set — flag parsing
;; ------------------------------------------------------------------

(defn- flag-val
  "Return the value after `flag` in `args-v`, or nil."
  [args-v flag]
  (let [i (.indexOf ^clojure.lang.PersistentVector args-v flag)]
    (when (and (>= i 0) (< (inc i) (count args-v)))
      (get args-v (inc i)))))

(defn- parse-tier-flag
  "Parse a tier string to keyword. Returns [kw nil] on success,
   [nil err-msg] on invalid value."
  [s flag-name]
  (if (nil? s)
    [nil nil]
    (let [kw (keyword s)]
      (if (contains? config/valid-tiers kw)
        [kw nil]
        [nil (str "invalid value for " flag-name ": " s
                  " (principle|rule|ethic)")]))))

(defn- parse-friction-flag
  "Parse a friction string to keyword. Returns [kw nil] on success,
   [nil err-msg] on invalid value."
  [s]
  (if (nil? s)
    [nil nil]
    (let [kw (keyword s)]
      (if (contains? config/valid-frictions kw)
        [kw nil]
        [nil (str "invalid value for --friction: " s
                  " (open|soft|firm|locked)")]))))

(defn- run-set [project-root card-id args]
  (let [args-v       (vec args)
        tier-str     (flag-val args-v "--tier")
        floor-str    (flag-val args-v "--tier-floor")
        max-str      (flag-val args-v "--tier-max")
        friction-str (flag-val args-v "--friction")
        no-bounds?   (boolean (some #{"--no-bounds"} args-v))
        no-friction? (boolean (some #{"--no-friction"} args-v))
        [new-tier    tier-err]     (parse-tier-flag tier-str  "--tier")
        [floor-tier  floor-err]    (parse-tier-flag floor-str "--tier-floor")
        [max-tier    max-err]      (parse-tier-flag max-str   "--tier-max")
        [new-friction friction-err] (parse-friction-flag friction-str)]
    (if-let [err (or tier-err floor-err max-err friction-err)]
      (do (binding [*out* *err*] (println err))
          1)
      (let [cards (store-cards/load-all-cards project-root)
            c     (first (filter #(= card-id (:card/id %)) cards))]
        (if-not c
          (do (binding [*out* *err*]
                (println (str "unknown card id: " card-id)))
              1)
          (let [old-tier     (:card/tier c)
                old-bounds   (:card/tier-bounds c)
                old-friction (:card/friction c)
                old-file     (:card/file c)
                tier         (or new-tier old-tier)
                bounds       (cond
                               no-bounds?
                               nil

                               (or new-tier floor-tier max-tier)
                               (let [base   (if new-tier {:floor tier} (or old-bounds {}))
                                     merged (cond-> base
                                              floor-tier (assoc :floor floor-tier)
                                              max-tier   (assoc :max max-tier))]
                                 (when (seq merged) merged))

                               :else
                               old-bounds)
                friction     (cond
                               no-friction? nil
                               new-friction new-friction
                               :else        old-friction)
                updated      (-> c
                                 (dissoc :card/file)
                                 (assoc :card/tier tier)
                                 (cond-> (some? bounds)   (assoc :card/tier-bounds bounds)
                                         (nil? bounds)    (dissoc :card/tier-bounds)
                                         (some? friction) (assoc :card/friction friction)
                                         (nil? friction)  (dissoc :card/friction)))]
            ;; Delete old file if tier changed (write-card! writes to the new tier dir
            ;; but does not remove the old location).
            (when (and old-file (not= tier old-tier))
              (.delete (java.io.File. ^String old-file)))
            (store-cards/write-card! project-root updated)
            (store-cards/materialize-promoted! project-root)
            (println (str "updated " card-id
                          " → tier=" (name tier)
                          " friction=" (format-friction friction)
                          " bounds=" (format-bounds bounds)))
            0))))))

;; ------------------------------------------------------------------
;; Dispatch
;; ------------------------------------------------------------------

(defn run
  [project-root args]
  (let [[subcommand card-id & rest-args] args]
    (case subcommand
      "list" (run-list project-root)
      "show" (if card-id
               (run-show project-root card-id)
               (do (binding [*out* *err*]
                     (println "usage: succession identity show <card-id>"))
                   1))
      "set"  (if card-id
               (run-set project-root card-id rest-args)
               (do (binding [*out* *err*]
                     (println "usage: succession identity set <card-id> [--tier T] [--friction F] [--tier-floor T] [--tier-max T] [--no-bounds] [--no-friction]"))
                   1))
      (do (binding [*out* *err*]
            (println (str "unknown identity subcommand: " subcommand))
            (println "  list | show <id> | set <id> [--tier T] [--friction F] [--tier-floor T] [--tier-max T] [--no-bounds] [--no-friction]"))
          1))))
