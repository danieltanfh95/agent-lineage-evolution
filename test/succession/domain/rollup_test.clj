(ns succession.domain.rollup-test
  (:require [clojure.test :refer [deftest is testing]]
            [succession.domain.observation :as obs]
            [succession.domain.rollup :as rollup]))

(defn- obs-fixture
  [{:keys [id at session kind] :or {kind :confirmed}}]
  (obs/make-observation
    {:id       id
     :at       at
     :session  session
     :hook     :post-tool-use
     :source   :judge-verdict
     :card-id  "c1"
     :kind     kind}))

(deftest empty-rollup-test
  (let [r (rollup/rollup-by-session [])]
    (is (= {} r))
    (is (= 0 (rollup/distinct-sessions r)))
    (is (= 0.0 (rollup/violation-rate r)))
    (is (= 0 (rollup/total-confirmed r)))))

(deftest single-observation-test
  (let [r (rollup/rollup-by-session
            [(obs-fixture {:id "o1" :at #inst "2026-01-15T10:00:00Z" :session "s1"})])]
    (is (= 1 (rollup/distinct-sessions r)))
    (is (= 1 (rollup/total-confirmed r)))
    (let [bucket (get r "s1")]
      (is (= 1 (:session/confirmed bucket)))
      (is (= #inst "2026-01-15T10:00:00Z" (:session/first-at bucket)))
      (is (= #inst "2026-01-15T10:00:00Z" (:session/last-at bucket))))))

(defn- date
  "Construct a `java.util.Date` from an ISO-8601 string. Uses
   `java.time.Instant/parse` internally but yields a Date, matching what
   `#inst` reader literals produce, so assertions using `#inst` equate."
  [s]
  (java.util.Date/from (java.time.Instant/parse s)))

(deftest within-session-collapse-test
  (testing "50 confirmations in the same session collapse to one bucket"
    (let [observations (for [i (range 50)]
                         (obs-fixture {:id (str "o" i)
                                       :at (date (format "2026-01-15T10:%02d:00Z" i))
                                       :session "s1"
                                       :kind :confirmed}))
          r (rollup/rollup-by-session observations)]
      (is (= 1 (rollup/distinct-sessions r))
          "50 within-session observations → freq of 1 session")
      (is (= 50 (rollup/total-confirmed r))
          "raw count is preserved, it's `distinct-sessions` that dedups")
      (let [bucket (get r "s1")]
        (is (= 50 (:session/confirmed bucket)))
        (is (= #inst "2026-01-15T10:00:00Z" (:session/first-at bucket)))
        (is (= #inst "2026-01-15T10:49:00Z" (:session/last-at bucket)))))))

(deftest cross-session-freq-test
  (testing "observations across 3 sessions → freq of 3"
    (let [r (rollup/rollup-by-session
              [(obs-fixture {:id "o1" :at #inst "2026-01-15T10:00:00Z" :session "s1"})
               (obs-fixture {:id "o2" :at #inst "2026-02-20T10:00:00Z" :session "s2"})
               (obs-fixture {:id "o3" :at #inst "2026-03-25T10:00:00Z" :session "s3"})])]
      (is (= 3 (rollup/distinct-sessions r)))
      (is (= 3 (rollup/total-confirmed r))))))

(deftest violation-rate-test
  (testing "all confirmations = 0.0"
    (let [r (rollup/rollup-by-session
              [(obs-fixture {:id "o1" :at #inst "2026-01-15T10:00:00Z" :session "s1"})])]
      (is (= 0.0 (rollup/violation-rate r)))))

  (testing "mix of confirmed/violated gives expected rate"
    (let [r (rollup/rollup-by-session
              [(obs-fixture {:id "o1" :at #inst "2026-01-15T10:00:00Z" :session "s1" :kind :confirmed})
               (obs-fixture {:id "o2" :at #inst "2026-01-15T11:00:00Z" :session "s1" :kind :violated})
               (obs-fixture {:id "o3" :at #inst "2026-01-15T12:00:00Z" :session "s1" :kind :violated})])]
      ;; 2 violated, 1 confirmed → 2 / (1+2+0 invoked) = 2/3
      (is (= (/ 2.0 3.0) (rollup/violation-rate r)))))

  (testing "consulted observations don't affect violation rate"
    (let [r (rollup/rollup-by-session
              [(obs-fixture {:id "o1" :at #inst "2026-01-15T10:00:00Z" :session "s1" :kind :confirmed})
               (obs-fixture {:id "o2" :at #inst "2026-01-15T11:00:00Z" :session "s1" :kind :consulted})])]
      (is (= 0.0 (rollup/violation-rate r))))))

(deftest sessions-ordered-test
  (testing "sessions returned in first-at order regardless of input order"
    (let [r (rollup/rollup-by-session
              [(obs-fixture {:id "o3" :at #inst "2026-03-25T10:00:00Z" :session "s3"})
               (obs-fixture {:id "o1" :at #inst "2026-01-15T10:00:00Z" :session "s1"})
               (obs-fixture {:id "o2" :at #inst "2026-02-20T10:00:00Z" :session "s2"})])
          ordered (rollup/sessions-ordered r)]
      (is (= [#inst "2026-01-15T10:00:00Z"
              #inst "2026-02-20T10:00:00Z"
              #inst "2026-03-25T10:00:00Z"]
             (map :session/first-at ordered))))))
