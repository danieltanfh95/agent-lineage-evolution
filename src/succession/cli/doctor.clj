(ns succession.cli.doctor
  "`succession doctor [--fix]` — diagnose and repair incomplete installations.

   Runs 9 checks against the succession store and Claude Code wiring,
   reports what's ok/missing/invalid, and optionally repairs fixable
   issues by delegating to the idempotent `install/*` functions.

   Each check produces:
     {:check    keyword          ; e.g. :config, :identity-dirs, :hooks
      :status   :ok|:missing|:invalid|:fixed
      :path     string           ; display path
      :detail   string-or-nil    ; human context when not :ok
      :fixable  boolean}"
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [succession.cli.install :as install]
            [succession.config :as config]
            [succession.store.cards :as store-cards]
            [succession.store.paths :as paths]))

;; ------------------------------------------------------------------
;; Individual checks — pure fns returning result maps
;; ------------------------------------------------------------------

(defn- check-root-dir [project-root]
  {:check   :root-dir
   :status  (if (.exists (io/file (paths/root project-root))) :ok :missing)
   :path    ".succession/"
   :fixable true})

(defn- check-config [project-root]
  (let [f (io/file (paths/root project-root) "config.edn")]
    (cond
      (not (.exists f))
      {:check :config :status :missing :path ".succession/config.edn" :fixable true}

      (try (edn/read-string (slurp f)) true (catch Throwable _ false))
      {:check :config :status :ok :path ".succession/config.edn" :fixable false}

      :else
      {:check :config :status :invalid :path ".succession/config.edn"
       :detail "not parseable as EDN" :fixable false})))

(defn- check-config-valid [project-root]
  (try
    (let [problems (config/validate (config/load-config project-root))]
      (if (empty? problems)
        {:check :config-valid :status :ok :path ".succession/config.edn" :fixable false}
        {:check   :config-valid
         :status  :invalid
         :path    ".succession/config.edn"
         :detail  (str (count problems) " validation error(s)")
         :fixable false}))
    (catch Throwable _
      {:check :config-valid :status :invalid :path ".succession/config.edn"
       :detail "config not loadable" :fixable false})))

(defn- dir-group-result
  "Check whether every path in `dir-paths` exists. Returns a result map
   with `:_missing-count` metadata for the fix detail."
  [check-key dir-paths rel-fn]
  (let [missing (filterv #(not (.exists (io/file %))) dir-paths)]
    (if (empty? missing)
      {:check check-key :status :ok :fixable false
       :path (let [first-rel (rel-fn (first dir-paths))]
               (if (> (count dir-paths) 1)
                 (str first-rel "  (+ " (dec (count dir-paths)) " more)")
                 first-rel))}
      {:check check-key :status :missing :fixable true
       :path (let [first-rel (rel-fn (first missing))]
               (if (> (count missing) 1)
                 (str first-rel "  (+ " (dec (count missing)) " more)")
                 first-rel))
       :_missing-count (count missing)})))

(defn- rel-dir
  "Convert an absolute dir path to a relative display path with trailing slash."
  [project-root path]
  (str (str/replace-first path (str project-root "/") "") "/"))

(defn- check-identity-dirs [project-root]
  (dir-group-result :identity-dirs
    [(paths/tier-dir project-root :principle)
     (paths/tier-dir project-root :rule)
     (paths/tier-dir project-root :ethic)]
    #(rel-dir project-root %)))

(defn- check-store-dirs [project-root]
  (dir-group-result :store-dirs
    [(paths/observations-dir project-root)
     (paths/staging-dir project-root)
     (paths/contradictions-dir project-root)
     (paths/archive-dir project-root)
     (paths/judge-dir project-root)]
    #(rel-dir project-root %)))

(defn- check-promoted-snapshot [project-root]
  {:check   :promoted-snapshot
   :status  (if (.exists (io/file (paths/promoted-snapshot project-root))) :ok :missing)
   :path    ".succession/promoted.edn"
   :fixable true})

(defn- check-identity-cards [project-root]
  (let [n (count (store-cards/list-card-files project-root))]
    (if (pos? n)
      {:check :identity-cards :status :ok
       :path (str n " card(s) found") :fixable false}
      {:check :identity-cards :status :missing
       :path "0 cards found" :fixable true})))

(defn- check-hooks [project-root]
  (let [settings-path (str project-root "/.claude/settings.local.json")
        f             (io/file settings-path)
        expected-keys (keys (install/build-hook-entries))]
    (if-not (.exists f)
      {:check :hooks :status :missing :path ".claude/settings.local.json"
       :fixable true}
      (let [settings (try (json/parse-string (slurp f) true)
                          (catch Throwable _ nil))]
        (if (nil? settings)
          {:check :hooks :status :invalid :path ".claude/settings.local.json"
           :detail "not parseable as JSON" :fixable false}
          (let [hooks (or (:hooks settings) {})
                wired (count
                        (filter (fn [event-kw]
                                  (some (fn [entry]
                                          (some (fn [h]
                                                  (and (:command h)
                                                       (str/includes?
                                                         (:command h)
                                                         "succession hook")))
                                                (:hooks entry)))
                                        (get hooks event-kw)))
                                expected-keys))]
            (if (= wired (count expected-keys))
              {:check :hooks :status :ok :fixable false
               :path (str ".claude/settings.local.json  (" wired " events wired)")}
              {:check :hooks :status :missing :fixable true
               :path ".claude/settings.local.json"
               :detail (str wired "/" (count expected-keys) " events wired")})))))))

(defn- check-skill [project-root]
  (let [path (str project-root "/.claude/skills/succession-consult/SKILL.md")]
    {:check   :skill
     :status  (if (.exists (io/file path)) :ok :missing)
     :path    ".claude/skills/succession-consult/SKILL.md"
     :fixable true}))

;; ------------------------------------------------------------------
;; Fix — delegates to idempotent install/* functions
;; ------------------------------------------------------------------

(defn- fix! [project-root result]
  (case (:check result)
    :root-dir
    (do (paths/ensure-dir! (paths/root project-root))
        (assoc result :status :fixed :detail "created .succession/"))

    :config
    (do (install/install-config! project-root)
        (assoc result :status :fixed :detail "wrote .succession/config.edn"))

    :identity-dirs
    (do (install/install-store-dirs! project-root)
        (assoc result :status :fixed
               :detail (str "created " (or (:_missing-count result) 3) " directories")))

    :store-dirs
    (do (install/install-store-dirs! project-root)
        (assoc result :status :fixed
               :detail (str "created " (or (:_missing-count result) 5) " directories")))

    :promoted-snapshot
    (do (store-cards/materialize-promoted! project-root)
        (assoc result :status :fixed :detail "wrote .succession/promoted.edn"))

    :identity-cards
    (do (install/install-starter-pack! project-root)
        (store-cards/materialize-promoted! project-root)
        (let [n (count (store-cards/list-card-files project-root))]
          (assoc result :status :fixed :detail (str "installed " n " starter cards"))))

    :hooks
    (let [settings-path (str project-root "/.claude/settings.local.json")]
      (install/install-settings! settings-path)
      (assoc result :status :fixed :detail "wired succession hooks"))

    :skill
    (do (install/install-skill! project-root)
        (assoc result :status :fixed :detail "wrote SKILL.md"))

    ;; non-fixable checks fall through unchanged
    result))

;; ------------------------------------------------------------------
;; Runner
;; ------------------------------------------------------------------

(def ^:private check-fns
  "Ordered check functions — run sequentially so earlier fixes
   (e.g. root-dir) are visible to later checks (e.g. config)."
  [check-root-dir check-config check-config-valid
   check-identity-dirs check-store-dirs check-promoted-snapshot
   check-identity-cards check-hooks check-skill])

(defn run-checks
  "Run all 9 checks in order. If `fix?`, attempt repairs for fixable failures.
   Returns a vec of result maps."
  [project-root fix?]
  (mapv (fn [check-fn]
          (let [result (check-fn project-root)]
            (if (and fix?
                     (:fixable result)
                     (contains? #{:missing :invalid} (:status result)))
              (fix! project-root result)
              result)))
        check-fns))

;; ------------------------------------------------------------------
;; Output
;; ------------------------------------------------------------------

(defn- print-report! [results project-root fix?]
  (println (str "succession doctor" (when fix? " --fix") " (" project-root ")"))
  (println)
  (doseq [r results]
    (let [tag     (case (:status r)
                    :ok      "ok"
                    :missing "MISSING"
                    :invalid "INVALID"
                    :fixed   "FIXED")
          display (if (= :fixed (:status r))
                    (:detail r)
                    (:path r))
          extra   (when (and (not= :fixed (:status r))
                             (not= :ok (:status r))
                             (:detail r))
                    (str "  (" (:detail r) ")"))]
      (println (format "  %-9s %-18s %s%s"
                       (str "[" tag "]")
                       (name (:check r))
                       (or display "")
                       (or extra "")))))
  (println)
  (let [issues (filterv #(contains? #{:missing :invalid} (:status %)) results)
        fixed  (filterv #(= :fixed (:status %)) results)]
    (cond
      (and (seq fixed) (empty? issues))
      (println (str (count fixed) " issues fixed. Succession is healthy."))

      (and (seq fixed) (seq issues))
      (println (str (count fixed) " issues fixed, "
                    (count issues) " remaining (not auto-fixable)."))

      (seq issues)
      (let [fixable (count (filter :fixable issues))]
        (println (str (count issues) " issues found"
                      (when (pos? fixable) (str " (" fixable " fixable)"))
                      ". Run 'succession doctor --fix' to repair.")))

      :else
      (println "All checks passed. Succession is healthy."))))

;; ------------------------------------------------------------------
;; CLI entry point
;; ------------------------------------------------------------------

(defn run
  "Dispatch entry called from `core/-main`.
   Returns exit code: 0 if all checks pass, 1 if issues remain."
  [project-root args]
  (let [fix?    (boolean (some #{"--fix"} args))
        results (run-checks project-root fix?)
        issues  (filterv #(contains? #{:missing :invalid} (:status %)) results)]
    (print-report! results project-root fix?)
    (if (seq issues) 1 0)))
