(ns succession.domain.queue-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [succession.domain.queue :as queue])
  (:import (java.util Date)))

(deftest sort-jobs-is-filename-lex-test
  (testing "sort-jobs orders by :job/filename lex (== chrono with ISO-basic prefix)"
    (let [unsorted [{:job/filename "20260411T000003000Z-c.json"}
                    {:job/filename "20260411T000001000Z-a.json"}
                    {:job/filename "20260411T000002000Z-b.json"}]
          sorted (queue/sort-jobs unsorted)]
      (is (= ["20260411T000001000Z-a.json"
              "20260411T000002000Z-b.json"
              "20260411T000003000Z-c.json"]
             (mapv :job/filename sorted))))))

(deftest idle?-requires-empty-queue-test
  (testing "not idle while anything is pending or inflight"
    (let [now (Date.)
          old (Date. (- (.getTime now) 60000))]
      (is (not (queue/idle? {:queue/pending-count  1
                             :queue/inflight-count 0
                             :queue/last-activity-at old
                             :queue/now            now}
                            {:idle-timeout-seconds 10})))
      (is (not (queue/idle? {:queue/pending-count  0
                             :queue/inflight-count 1
                             :queue/last-activity-at old
                             :queue/now            now}
                            {:idle-timeout-seconds 10}))))))

(deftest idle?-requires-grace-window-test
  (testing "queue empty but within grace window is not idle"
    (let [now (Date.)
          recent (Date. (- (.getTime now) 1000))]
      (is (not (queue/idle? {:queue/pending-count  0
                             :queue/inflight-count 0
                             :queue/last-activity-at recent
                             :queue/now            now}
                            {:idle-timeout-seconds 10})))))
  (testing "queue empty past grace window is idle"
    (let [now (Date.)
          old (Date. (- (.getTime now) 20000))]
      (is (queue/idle? {:queue/pending-count  0
                        :queue/inflight-count 0
                        :queue/last-activity-at old
                        :queue/now            now}
                       {:idle-timeout-seconds 10})))))

(deftest job->result-success-shape-test
  (let [started  (Date.)
        finished (Date.)
        r (queue/job->result {:job/id "j1" :job/filename "f.json"}
                             nil started finished [{:kind :ok}])]
    (is (= :ok (queue/classify-result r)))
    (is (= "j1" (:result/job-id r)))
    (is (= "f.json" (:result/job-filename r)))
    (is (nil? (:result/error r)))
    (is (= [{:kind :ok}] (:result/side-effects r)))))

(deftest job->result-error-shape-test
  (let [started  (Date.)
        finished (Date.)
        ex       (ex-info "boom" {:why :test})
        r (queue/job->result {:job/id "j1" :job/filename "f.json"}
                             ex started finished nil)]
    (is (= :error (queue/classify-result r)))
    (is (= "boom" (get-in r [:result/error :message])))
    (is (some? (get-in r [:result/error :class])))
    (testing "the error map carries a non-empty stack trace so silent
              dead-letters cannot recur"
      (let [trace (get-in r [:result/error :trace])]
        (is (string? trace))
        (is (pos? (count trace)))
        (is (.contains ^String trace "boom"))))))

(deftest format-throwable-trace-nil-safe-test
  (is (nil? (queue/format-throwable-trace nil))))

(deftest format-throwable-trace-non-nil-test
  (let [ex    (ex-info "boom" {})
        trace (queue/format-throwable-trace ex)]
    (is (string? trace))
    (is (not (str/blank? trace)))
    (is (.contains ^String trace "boom"))))
