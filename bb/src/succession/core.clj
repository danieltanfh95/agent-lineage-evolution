(ns succession.core
  "CLI dispatch entry point for succession commands.
   Usage: bb -m succession.core <command> [args...]"
  (:require [succession.resolve :as resolve]
            [succession.effectiveness :as eff]
            [succession.extract :as extract]
            [succession.skill :as skill]
            [cheshire.core :as json]))

(defn -main [& args]
  (let [cmd (first args)]
    (case cmd
      "resolve"
      (let [project-dir (or (second args) ".")]
        (let [result (resolve/resolve-and-compile! project-dir)]
          (println (json/generate-string result {:pretty true}))))

      "effectiveness"
      (let [project-dir (or (second args) ".")
            rules-dir (str project-dir "/.succession/rules")
            global-dir (str (System/getProperty "user.home") "/.succession/rules")
            candidates-file (str project-dir "/.succession/compiled/review-candidates.json")]
        (println "=== Effectiveness Report ===\n")
        (when (babashka.fs/exists? candidates-file)
          (let [candidates (json/parse-string (slurp candidates-file) true)]
            (when (seq candidates)
              (println "Review candidates:")
              (doseq [c candidates]
                (println (str "  " (:id c) " — " (:action c)
                               " (follow rate: " (:follow_rate c)
                               ", evaluations: " (:evaluations c) ")")))
              (println))))
        (println "Loading rules...")
        (let [rules (concat (resolve/load-rules-from-dir global-dir "global")
                            (resolve/load-rules-from-dir rules-dir "project"))
              by-category (group-by :category rules)]
          (doseq [[cat rules] (sort-by key by-category)]
            (println (str "\n[" cat "] (" (count rules) " rules)"))
            (doseq [r rules]
              (let [eff (:effectiveness r)
                    total (+ (get eff :times-followed 0) (get eff :times-violated 0))]
                (println (str "  " (:id r) " [" (:enforcement r) "]"
                               (when (pos? total)
                                 (str " — followed:" (get eff :times-followed 0)
                                      " violated:" (get eff :times-violated 0))))))))))

      "extract"
      (apply extract/-main (rest args))

      "skill-extract"
      (apply skill/-main (rest args))

      (do
        (println "Usage: bb -m succession.core <command>")
        (println "Commands:")
        (println "  resolve [project-dir]        — Run cascade resolution")
        (println "  effectiveness [project-dir]  — Show effectiveness report")
        (println "  extract [options] [path]     — Retrospective rule extraction")
        (println "  skill-extract [options] [path] — Skill bundle extraction")
        (System/exit 1)))))
