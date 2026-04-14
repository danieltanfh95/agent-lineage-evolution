(ns succession.transcript
  "Read recent conversation context from a Claude Code transcript JSONL
   file. Used by the PostToolUse async judge lane so the conscience judge
   can see what the user asked for — not just the bare tool call.

   Pure-ish: reads a file but touches no succession state. Sits in the
   utility layer alongside `llm/*`."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- extract-text
  "Pull readable text from a single transcript entry's message.
   - user with string content   -> use directly
   - user with vector content   -> find first tool_result block's :content,
                                   or join text blocks
   - assistant                  -> join all {:type \"text\"} blocks (skip tool_use)"
  [entry]
  (let [role    (get-in entry [:message :role])
        content (get-in entry [:message :content])]
    (cond
      (string? content)
      content

      (sequential? content)
      (case role
        "user"
        (or (some (fn [block]
                    (when (and (map? block)
                               (= "tool_result" (:type block))
                               (string? (:content block)))
                      (:content block)))
                  content)
            (str/join "\n"
                      (keep (fn [block]
                              (when (and (map? block) (= "text" (:type block)))
                                (:text block)))
                            content)))

        "assistant"
        (str/join "\n"
                  (keep (fn [block]
                          (when (and (map? block) (= "text" (:type block)))
                            (:text block)))
                        content))

        ;; fallback
        (str content))

      :else
      (str content))))

(defn- truncate [^String s max-chars]
  (if (<= (count s) max-chars)
    s
    (str (subs s 0 max-chars) "...")))

(defn- format-entry [entry max-chars]
  (let [role (get-in entry [:message :role])
        text (extract-text entry)]
    (when (and role (not (str/blank? text)))
      (str "[" role "]: " (truncate (str/trim text) max-chars)))))

(defn recent-context
  "Read the last `n` user/assistant messages from a Claude Code transcript
   JSONL file. Returns a formatted string or nil on any error.
   Each message is truncated to `max-chars`."
  [transcript-path {:keys [n max-chars] :or {n 3 max-chars 600}}]
  (try
    (when (and transcript-path
               (.exists (io/file transcript-path)))
      (let [lines     (with-open [rdr (io/reader transcript-path)]
                        (vec (take-last 80 (line-seq rdr))))
            entries   (->> lines
                           (keep (fn [line]
                                   (try (json/parse-string line true)
                                        (catch Throwable _ nil))))
                           (filter (fn [entry]
                                     (#{"user" "assistant"} (:type entry)))))
            recent    (take-last n entries)
            formatted (keep #(format-entry % max-chars) recent)]
        (when (seq formatted)
          (str (str/join "\n" formatted) "\n"))))
    (catch Throwable _ nil)))
