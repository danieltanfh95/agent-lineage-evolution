(ns succession.store.test-helpers
  "Shared helpers for store tests: tmp-dir, fixtures, constructors."
  (:require [clojure.java.io :as io]
            [succession.domain.card :as card]
            [succession.domain.observation :as obs]))

(defn tmp-dir!
  "Create a fresh temp directory under the system tmp dir. Returns the
   absolute path. Caller is responsible for cleanup (or can rely on
   the OS — bb tests don't stress disk)."
  [prefix]
  (let [f (java.io.File/createTempFile prefix "")]
    (.delete f)
    (.mkdirs f)
    (.getAbsolutePath f)))

(defn delete-tree!
  "Recursively delete a directory. Best-effort."
  [path]
  (let [f (io/file path)]
    (when (.exists f)
      (when (.isDirectory f)
        (doseq [child (.listFiles f)]
          (delete-tree! (.getPath ^java.io.File child))))
      (.delete f))))

(defn a-card
  [{:keys [id tier category text tags fingerprint tier-bounds]
    :or {category :strategy
         text     "default text"}}]
  (card/make-card
    {:id id
     :tier tier
     :category category
     :text text
     :tags tags
     :fingerprint fingerprint
     :tier-bounds tier-bounds
     :provenance {:provenance/born-at         #inst "2026-01-01T00:00:00Z"
                  :provenance/born-in-session "s0"
                  :provenance/born-from       :user-correction
                  :provenance/born-context    "ctx"}}))

(defn an-observation
  [{:keys [id at session card-id kind]
    :or {kind :confirmed}}]
  (obs/make-observation
    {:id id
     :at at
     :session session
     :hook :post-tool-use
     :source :judge-verdict
     :card-id card-id
     :kind kind}))
