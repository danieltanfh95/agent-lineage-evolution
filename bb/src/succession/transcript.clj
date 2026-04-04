(ns succession.transcript
  "Shared transcript finding and reading logic for CLI tools.
   Used by extract.clj and skill.clj."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn- claude-projects-dir []
  (str (System/getProperty "user.home") "/.claude/projects"))

(defn find-transcript-by-session
  "Find a transcript JSONL by session ID in ~/.claude/projects/.
   Returns the file path or nil."
  [session-id]
  (let [projects-dir (claude-projects-dir)]
    (when (fs/exists? projects-dir)
      (->> (fs/glob projects-dir "**/*.jsonl")
           (map str)
           (some (fn [f]
                   (try
                     (let [content (slurp f)]
                       (when (str/includes? content (str "\"session_id\":\"" session-id "\""))
                         f))
                     (catch Exception _ nil))))))))

(defn find-latest-transcript
  "Find the most recently modified transcript for the given CWD.
   Scans ~/.claude/projects/*/ directories, checks if any transcript
   references the CWD, returns the most recent .jsonl in that dir.
   Falls back to most recent .jsonl globally."
  [cwd]
  (let [projects-dir (claude-projects-dir)]
    (when (fs/exists? projects-dir)
      (let [;; Find project dir matching CWD
            project-dirs (->> (fs/list-dir projects-dir)
                              (filter fs/directory?))
            matching-dir (some
                          (fn [dir]
                            (let [jsonls (->> (fs/glob dir "*.jsonl")
                                             (map str)
                                             seq)]
                              (when jsonls
                                (let [sample (first jsonls)]
                                  (try
                                    (let [first-line (first (str/split-lines (slurp sample)))]
                                      (when first-line
                                        (let [entry (json/parse-string first-line true)]
                                          (when (= cwd (:cwd entry))
                                            dir))))
                                    (catch Exception _ nil))))))
                          project-dirs)
            search-dir (or matching-dir projects-dir)
            pattern (if matching-dir "*.jsonl" "**/*.jsonl")]
        (->> (fs/glob search-dir pattern)
             (map str)
             (sort-by #(fs/last-modified-time %) #(compare %2 %1))
             first)))))

(defn read-transcript-text
  "Read a transcript JSONL and format as USER:/ASSISTANT: lines.
   Options:
     :from-turn - skip first N message lines (0 = all)
     :cap-bytes - max output size (default 200000)"
  [transcript-path & {:keys [from-turn cap-bytes]
                       :or {from-turn 0 cap-bytes 200000}}]
  (when (and transcript-path (fs/exists? transcript-path))
    (let [lines (str/split-lines (slurp transcript-path))
          messages (->> lines
                        (keep (fn [line]
                                (try
                                  (let [entry (json/parse-string line true)]
                                    (when (#{"human" "assistant"} (:type entry))
                                      (let [content-val (get-in entry [:message :content])
                                            text (if (vector? content-val)
                                                   (str/join " " (keep #(when (= "text" (:type %))
                                                                          (:text %))
                                                                       content-val))
                                                   (str content-val))]
                                        (str (if (= "human" (:type entry)) "USER: " "ASSISTANT: ")
                                             text))))
                                  (catch Exception _ nil))))
                        vec)
          ;; Apply from-turn skip
          skipped (if (pos? from-turn)
                    (subvec messages (min from-turn (count messages)))
                    messages)
          joined (str/join "\n" skipped)]
      (subs joined 0 (min (count joined) cap-bytes)))))

(defn count-turns
  "Count the number of human + assistant messages in a transcript."
  [transcript-path]
  (when (and transcript-path (fs/exists? transcript-path))
    (->> (str/split-lines (slurp transcript-path))
         (keep (fn [line]
                 (try
                   (let [entry (json/parse-string line true)]
                     (when (#{"human" "assistant"} (:type entry))
                       true))
                   (catch Exception _ nil))))
         count)))
