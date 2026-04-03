(ns succession.effectiveness
  "Meta-cognition tracking: append-only JSONL event log and effectiveness
   counter materialization."
  (:require [cheshire.core :as json]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [succession.yaml :as yaml]))

(defn global-log-dir []
  (str (System/getProperty "user.home") "/.succession/log"))

(defn meta-cognition-log-path []
  (str (global-log-dir) "/meta-cognition.jsonl"))

(defn log-event!
  "Append a meta-cognition event to the JSONL log."
  [event-type attrs]
  (let [log-file (meta-cognition-log-path)]
    (fs/create-dirs (fs/parent log-file))
    (spit log-file
          (str (json/generate-string
                (merge {:ts (str (java.time.Instant/now))
                        :event event-type
                        :session (or (:session attrs) "unknown")}
                       (dissoc attrs :session)))
               "\n")
          :append true)))

(defn read-events
  "Read all events from the meta-cognition log. Returns a seq of maps."
  []
  (let [log-file (meta-cognition-log-path)]
    (when (fs/exists? log-file)
      (->> (str/split-lines (slurp log-file))
           (remove str/blank?)
           (map #(json/parse-string % true))))))

(defn compute-counters
  "Compute effectiveness counters from events grouped by rule_id.
   Returns {\"rule-id\" {:followed N :violated N}}."
  [events]
  (->> events
       (filter #(#{"rule_followed" "rule_violated"} (:event %)))
       (group-by :rule_id)
       (into {}
             (map (fn [[rule-id evts]]
                    [rule-id
                     {:followed (count (filter #(= "rule_followed" (:event %)) evts))
                      :violated (count (filter #(= "rule_violated" (:event %)) evts))}])))))

(defn update-effectiveness-counters!
  "Read events from meta-cognition.jsonl, update effectiveness counters
   in rule file frontmatter for each rule directory."
  [& rule-dirs]
  (let [events (read-events)
        counters (compute-counters events)]
    (doseq [[rule-id {:keys [followed violated]}] counters
            dir rule-dirs
            :let [rule-file (str dir "/" rule-id ".md")]
            :when (fs/exists? rule-file)]
      (let [rule (yaml/parse-rule-file rule-file)
            eff (:effectiveness rule)
            updated (assoc rule :effectiveness
                          {:times-followed (+ (get eff :times-followed 0) followed)
                           :times-violated (+ (get eff :times-violated 0) violated)
                           :times-overridden (get eff :times-overridden 0)
                           :last-evaluated (str (java.time.Instant/now))})]
        (yaml/write-rule-file rule-file updated)))))
