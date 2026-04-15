(ns succession.store.util
  "Shared utilities for the store.* layer."
  (:require [clojure.string :as str]))

(defn safe-ts-string
  "Convert an inst (Date or Instant) into a filename-safe ISO-like
   string with no colons or dots: e.g. `2026-04-11T12-34-56-789Z`.
   Falls back to Instant/now if inst is nil or unrecognised."
  [inst]
  (-> (cond
        (instance? java.util.Date inst)    (.toInstant ^java.util.Date inst)
        (instance? java.time.Instant inst) inst
        :else (java.time.Instant/now))
      .toString
      (str/replace ":" "-")
      (str/replace "." "-")))
