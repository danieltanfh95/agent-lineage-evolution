(ns succession.cli.config-validate
  "`succession config <subcmd>` — config tools.

   Subcommands:

     validate   - read .succession/config.edn (if present), deep-merge
                  with defaults, and report problems found by
                  `config/validate`.
     show       - pretty-print the effective config (defaults + overlay).
     init       - write a starter .succession/config.edn if none exists.
                  Idempotent — will refuse to overwrite an existing file.

   Reference: `.plans/succession-identity-cycle.md` §Config."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [succession.config :as config]
            [succession.store.paths :as paths]))

(defn- template-config
  "Starter config written by `config init`. Rendered from
   `succession.config/default-config` so the printed example can
   never drift from the schema the code actually reads — past drift
   caused `:integration-gap-turns` and `:every-n-turns` examples that
   were silently ignored."
  []
  (str
    ";; .succession/config.edn — Succession identity-cycle config\n"
    ";;\n"
    ";; Every tunable in the system lives here. Values are deep-merged\n"
    ";; over the defaults in `succession.config/default-config`\n"
    ";; so you only need to override the ones you disagree with. This\n"
    ";; starter file is the defaults themselves; delete anything you\n"
    ";; are happy with and edit the rest.\n"
    ";;\n"
    ";; Reference: `.plans/succession-identity-cycle.md` §Config.\n"
    "\n"
    (with-out-str (pprint/pprint config/default-config))))

(defn- check-hook-paths [project-root]
  (let [settings-file (str project-root "/.claude/settings.local.json")]
    (when (.exists (io/file settings-file))
      (let [settings (json/parse-string (slurp settings-file) true)
            hooks    (mapcat val (:hooks settings))
            commands (mapcat #(or (:commands %) []) hooks)
            cp-args  (keep #(second (re-find #"-cp\s+(\S+)" %)) commands)]
        (doseq [path cp-args]
          (if (.exists (io/file path))
            (println (format "  [ok] hook classpath   %s" path))
            (println (format "  [WARN] hook classpath missing: %s" path))))))))

(defn validate!
  "Validate the effective config for `project-root`. Returns 0 on
   success, 1 on validation errors, and prints a report."
  [project-root]
  (let [cfg (config/load-config project-root)
        problems (config/validate cfg)]
    (if (empty? problems)
      (do (println "config valid: " (str project-root "/.succession/config.edn"))
          (println "effective values merged over defaults.")
          (check-hook-paths project-root)
          0)
      (do (println "config INVALID:" (str project-root "/.succession/config.edn"))
          (doseq [p problems]
            (println (format "  - %s: %s"
                             (pr-str (:path p)) (:problem p))))
          (check-hook-paths project-root)
          1))))

(defn show!
  "Pretty-print the effective config."
  [project-root]
  (let [cfg (config/load-config project-root)]
    (pprint/pprint cfg)
    0))

(defn init!
  "Write a starter config file. Refuses to overwrite an existing one."
  [project-root]
  (let [dir  (paths/root project-root)
        path (str dir "/config.edn")
        f    (io/file path)]
    (paths/ensure-dir! dir)
    (if (.exists f)
      (do (binding [*out* *err*]
            (println "config already exists:" path)
            (println "refusing to overwrite — edit directly or delete first."))
          1)
      (do (spit path (template-config))
          (println "wrote starter config:" path)
          0))))

(defn run
  "Dispatch entry called from `core/-main`."
  [project-root args]
  (let [sub (first args)
        rc  (case sub
              "validate" (validate! project-root)
              "show"     (show! project-root)
              "init"     (init! project-root)
              (do (binding [*out* *err*]
                    (println "usage: succession config <validate|show|init>")
                    (when sub (println "unknown subcommand:" sub)))
                  2))]
    (System/exit (or rc 0))))
