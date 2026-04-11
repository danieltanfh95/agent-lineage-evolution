(ns succession.identity.cli.import
  "`bb -m succession.identity.core import <old-rules-dir>` — one-shot
   migration from the old `.succession/rules/*.md` YAML-frontmatter
   rule files into the new card store.

   The old format (see `bb/src/succession/yaml.clj`) had per-rule YAML
   frontmatter like:

     ---
     id: verify-via-repl
     category: strategy
     type: preference
     enforcement: advisory
     source: {session: seed, timestamp: ..., evidence: \"...\"}
     effectiveness: {times_followed: 0, times_violated: 0, ...}
     enabled: true
     ---

     <body text>

   We convert each rule into a `:create-card` delta in a one-off
   migration session, then run `pre-compact/promote!` to fold them into
   the live promoted tree.

     - Card id stays the same.
     - Category stays the same if valid, else `:strategy`.
     - Tier starts at `:ethic` (the standard landing tier) — migrated
       rules must earn their way up through real observations.
     - Provenance carries `:born-from :imported` so reconcile knows not
       to auto-rewrite.
     - `effectiveness` counters are discarded: the new system derives
       weight from observations, not hand-maintained counters.

   Idempotent at the delta level: running twice creates two deltas per
   rule, but the second promotion just overwrites the same card id with
   the same text, so the net effect is a no-op.

   Reference: `.plans/succession-identity-cycle.md` §CLI surface and
   Open Question 10 (seeding identity from existing rule files)."
  (:require [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [succession.identity.config :as config]
            [succession.identity.hook.pre-compact :as pre-compact]
            [succession.identity.store.staging :as store-staging]))

(def ^:private fm-delim "---")

(defn parse-rule-file
  "Pure: parse the YAML frontmatter + body of one old rule file.
   Returns `{:id :category :text :raw-frontmatter}` or nil on parse
   failure."
  [file-content]
  (try
    (when (str/starts-with? file-content fm-delim)
      (let [[_ fm-text body] (str/split file-content #"(?m)^---\s*$" 3)
            fm               (when fm-text (yaml/parse-string (str/trim fm-text)))]
        (when (and fm (:id fm))
          {:id              (name (:id fm))
           :category        (let [c (some-> (:category fm) name keyword)]
                              (if (contains? config/valid-categories c)
                                c
                                :strategy))
           :text            (str/trim (or body ""))
           :raw-frontmatter fm})))
    (catch Throwable _ nil)))

(defn scan-rules-dir
  "Read every `.md` file in the given dir. Returns a seq of parsed rule
   maps. Silently skips files that fail to parse."
  [rules-dir]
  (let [d (io/file rules-dir)]
    (if-not (.exists d)
      []
      (->> (.listFiles d)
           (filter (fn [^java.io.File f]
                     (and (.isFile f)
                          (str/ends-with? (.getName f) ".md"))))
           (keep (fn [^java.io.File f]
                   (parse-rule-file (slurp f))))
           vec))))

(defn stage-imports!
  "Write one `:create-card` delta per parsed rule into a one-off
   migration staging session. Returns the session id so pre-compact
   can promote it."
  [project-root parsed now]
  (let [session (str "import-" (.getTime ^java.util.Date now))]
    (doseq [r parsed]
      (store-staging/append-delta!
        project-root session
        (store-staging/make-delta
          {:id      (str "d-import-" (:id r))
           :at      now
           :kind    :create-card
           :payload {:id                 (:id r)
                     :category           (:category r)
                     :text               (:text r)
                     :tier               :ethic
                     :provenance-context (str "imported from " (:id r))}
           :source  :extract})))
    session))

(defn run
  [project-root args]
  (let [rules-dir (first args)
        now       (java.util.Date.)
        cfg       (config/load-config project-root)]
    (cond
      (str/blank? rules-dir)
      (do (binding [*out* *err*]
            (println "usage: bb -m succession.identity.core import <old-rules-dir>"))
          (System/exit 2))

      (not (.exists (io/file rules-dir)))
      (do (binding [*out* *err*]
            (println "rules directory not found:" rules-dir))
          (System/exit 1))

      :else
      (let [parsed (scan-rules-dir rules-dir)]
        (if (empty? parsed)
          (do (println "no rule files parsed under" rules-dir)
              (System/exit 1))
          (let [session (stage-imports! project-root parsed now)]
            (println (format "staged %d import deltas in session %s"
                             (count parsed) session))
            (println "promoting …")
            (pre-compact/promote! project-root session now cfg)
            (println "done. imported cards are now in .succession/identity/promoted/ethic/")
            (System/exit 0)))))))
