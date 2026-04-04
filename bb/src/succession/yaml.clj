(ns succession.yaml
  "Rule file I/O: YAML frontmatter ↔ Clojure maps.
   Rules are stored as markdown files with YAML frontmatter between --- markers."
  (:require [clj-yaml.core :as yaml]
            [clojure.string :as str]))

(def default-effectiveness
  {:times-followed 0
   :times-violated 0
   :times-overridden 0
   :last-evaluated nil})

(defn- split-frontmatter
  "Split a rule file into [frontmatter-string body-string].
   Returns nil if no valid frontmatter found.
   The closing --- must be on its own line (possibly with whitespace)."
  [content]
  (when (str/starts-with? content "---")
    (let [rest (subs content 3)
          ;; Find closing --- that appears at start of a line
          matcher (re-matcher #"(?m)^---\s*$" rest)]
      (when (.find matcher)
        (let [idx (.start matcher)
              end-idx (+ idx (count (.group matcher)))]
          [(str/trim (subs rest 0 idx))
           (str/trim (subs rest end-idx))])))))

(defn parse-rule-file
  "Parse a rule markdown file into a Clojure map.
   Returns map with :id, :scope, :enforcement, :category, :type, :source,
   :overrides, :enabled, :effectiveness, :body, :file keys."
  [file-path]
  (let [content (slurp file-path)
        [fm-str body] (split-frontmatter content)]
    (when fm-str
      (let [fm (yaml/parse-string fm-str)
            ;; Normalize keys from YAML (keywords)
            source (:source fm {})
            effectiveness (:effectiveness fm default-effectiveness)]
        (-> fm
            (assoc :body body
                   :file file-path
                   ;; Defaults
                   :category (or (:category fm) "strategy")
                   :enabled (if (contains? fm :enabled) (:enabled fm) true)
                   :overrides (or (:overrides fm) [])
                   ;; Normalize nested maps
                   :source {:session (get source :session "unknown")
                            :timestamp (get source :timestamp "")
                            :evidence (get source :evidence "")}
                   :effectiveness {:times-followed (get effectiveness :times_followed
                                                     (get effectiveness :times-followed 0))
                                   :times-violated (get effectiveness :times_violated
                                                     (get effectiveness :times-violated 0))
                                   :times-overridden (get effectiveness :times_overridden
                                                       (get effectiveness :times-overridden 0))
                                   :last-evaluated (get effectiveness :last_evaluated
                                                     (get effectiveness :last-evaluated nil))}))))))

(defn write-rule-file
  "Write a rule map back to a markdown file with YAML frontmatter."
  [file-path rule]
  (let [{:keys [id scope enforcement category type source overrides enabled effectiveness body]} rule
        fm-map (array-map
                :id id
                :scope scope
                :enforcement enforcement
                :category (or category "strategy")
                :type type
                :source (array-map
                         :session (:session source "unknown")
                         :timestamp (:timestamp source "")
                         :evidence (:evidence source ""))
                :overrides (or overrides [])
                :enabled (if (nil? enabled) true enabled)
                :effectiveness (array-map
                                :times_followed (get effectiveness :times-followed 0)
                                :times_violated (get effectiveness :times-violated 0)
                                :times_overridden (get effectiveness :times-overridden 0)
                                :last_evaluated (get effectiveness :last-evaluated nil)))
        yaml-str (yaml/generate-string fm-map :dumper-options {:flow-style :block})]
    (spit file-path (str "---\n" yaml-str "---\n\n" (or body "") "\n"))))

(defn parse-directives
  "Extract enforcement directives from a rule body string.
   Returns a seq of maps like {:type :block-bash-pattern :pattern \"...\" :reason \"...\"}."
  [body]
  (when body
    (let [lines (str/split-lines body)
          in-enforcement? (atom false)
          directives (atom [])
          reason (atom nil)]
      (doseq [line lines]
        (cond
          (= (str/trim line) "## Enforcement")
          (reset! in-enforcement? true)

          (and @in-enforcement? (str/starts-with? (str/trim line) "- block_bash_pattern:"))
          (let [pattern (-> line (str/replace #"^.*block_bash_pattern:\s*" "") str/trim
                            (str/replace #"^\"" "") (str/replace #"\"$" ""))]
            (swap! directives conj {:type :block-bash-pattern :pattern pattern}))

          (and @in-enforcement? (str/starts-with? (str/trim line) "- block_tool:"))
          (let [tool (-> line (str/replace #"^.*block_tool:\s*" "") str/trim
                         (str/replace #"^\"" "") (str/replace #"\"$" ""))]
            (swap! directives conj {:type :block-tool :tool tool}))

          (and @in-enforcement? (str/starts-with? (str/trim line) "- require_prior_read"))
          (swap! directives conj {:type :require-prior-read})

          (and @in-enforcement? (str/starts-with? (str/trim line) "- reason:"))
          (reset! reason (-> line (str/replace #"^.*reason:\s*" "") str/trim
                              (str/replace #"^\"" "") (str/replace #"\"$" "")))))
      ;; Attach reason to all directives
      (mapv #(assoc % :reason (or @reason "Blocked by rule")) @directives))))
