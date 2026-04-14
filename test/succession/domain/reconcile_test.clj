(ns succession.domain.reconcile-test
  (:require [clojure.test :refer [deftest is testing]]
            [succession.config :as config]
            [succession.domain.card :as card]
            [succession.domain.observation :as obs]
            [succession.domain.reconcile :as reconcile]))

(def cfg config/default-config)
(defn- date [s] (java.util.Date/from (java.time.Instant/parse s)))

(defn- a-card
  ([id] (a-card id :rule))
  ([id tier]
   (card/make-card
     {:id         id
      :tier       tier
      :category   :strategy
      :text       "test"
      :provenance {:provenance/born-at         #inst "2026-01-01T00:00:00Z"
                   :provenance/born-in-session "s0"
                   :provenance/born-from       :user-correction
                   :provenance/born-context    "ctx"}})))

(defn- an-obs
  [{:keys [id at session kind card-id] :or {kind :confirmed}}]
  (obs/make-observation
    {:id id :at at :session session :hook :post-tool-use
     :source :judge-verdict :card-id card-id :kind kind}))

;; ------------------------------------------------------------------
;; Category 1: self-contradictory
;; ------------------------------------------------------------------
(deftest category-1-self-contradictory-test
  (let [c   (a-card "c1")
        now (date "2026-04-11T12:00:00Z")
        obs [(an-obs {:id "o1" :at (date "2026-03-01T10:00:00Z") :session "sA" :kind :confirmed :card-id "c1"})
             (an-obs {:id "o2" :at (date "2026-03-01T11:00:00Z") :session "sA" :kind :violated :card-id "c1"})]
        contradictions (reconcile/detect-self-contradictory c obs now)]
    (is (= 1 (count contradictions)))
    (is (= :self-contradictory (:contradiction/category (first contradictions))))
    (is (= "sA" (:contradiction/session (first contradictions)))))

  (testing "all-confirmed does not trigger"
    (let [c   (a-card "c1")
          obs [(an-obs {:id "o1" :at (date "2026-03-01T10:00:00Z") :session "sA" :kind :confirmed :card-id "c1"})]]
      (is (empty? (reconcile/detect-self-contradictory c obs (date "2026-04-11T12:00:00Z"))))))

  (testing "violation in a different session from confirmation does not trigger"
    (let [c   (a-card "c1")
          obs [(an-obs {:id "o1" :at (date "2026-03-01T10:00:00Z") :session "sA" :kind :confirmed :card-id "c1"})
               (an-obs {:id "o2" :at (date "2026-03-15T10:00:00Z") :session "sB" :kind :violated  :card-id "c1"})]]
      (is (empty? (reconcile/detect-self-contradictory c obs (date "2026-04-11T12:00:00Z")))))))

;; ------------------------------------------------------------------
;; Category 4: tier violation
;; ------------------------------------------------------------------
(deftest category-4-tier-violation-test
  (testing "card declared :rule but eligible :principle → tier-violation"
    (let [c (a-card "c1" :rule)
          m {:weight 35.0 :violation-rate 0.0 :gap-crossings 5}
          contradictions (reconcile/detect-tier-violation c m (date "2026-04-11T12:00:00Z") cfg)]
      (is (= 1 (count contradictions)))
      (let [cn (first contradictions)]
        (is (= :tier-violation (:contradiction/category cn)))
        (is (= :apply-tier (get-in cn [:contradiction/resolution :kind])))
        (is (= :principle (get-in cn [:contradiction/resolution :new-tier]))))))

  (testing "card already at eligible tier → no contradiction"
    (let [c (a-card "c1" :rule)
          m {:weight 10.0 :violation-rate 0.1 :gap-crossings 1}]
      (is (empty? (reconcile/detect-tier-violation c m (date "2026-04-11T12:00:00Z") cfg))))))

;; ------------------------------------------------------------------
;; Category 5: provenance conflict
;; ------------------------------------------------------------------
(deftest category-5-provenance-conflict-test
  (let [c1 (card/make-card
             {:id "c1" :tier :rule :category :strategy :text "..."
              :provenance {:provenance/born-at         #inst "2026-01-01T00:00:00Z"
                           :provenance/born-in-session "same-sess"
                           :provenance/born-from       :user-correction
                           :provenance/born-context    "same ctx"}})
        c2 (card/make-card
             {:id "c2" :tier :rule :category :strategy :text "..."
              :provenance {:provenance/born-at         #inst "2026-01-01T00:00:00Z"
                           :provenance/born-in-session "same-sess"
                           :provenance/born-from       :user-correction
                           :provenance/born-context    "same ctx"}})
        c3 (card/make-card
             {:id "c3" :tier :rule :category :strategy :text "..."
              :provenance {:provenance/born-at         #inst "2026-01-01T00:00:00Z"
                           :provenance/born-in-session "other-sess"
                           :provenance/born-from       :user-correction
                           :provenance/born-context    "other ctx"}})
        now (date "2026-04-11T12:00:00Z")
        contradictions (reconcile/detect-provenance-conflicts [c1 c2 c3] now)]
    (is (= 1 (count contradictions)))
    (let [cn (first contradictions)]
      (is (= :provenance-conflict (:contradiction/category cn)))
      (is (= #{"c1" "c2"} (set (map :card/id (:contradiction/between cn)))))
      (is (= :merge-candidates (get-in cn [:contradiction/resolution :kind]))))))

;; ------------------------------------------------------------------
;; Category 6: contextual override
;; ------------------------------------------------------------------
(deftest category-6-contextual-override-test
  (testing "violated then sustained confirmations → override candidate"
    (let [c   (a-card "c1")
          obs [(an-obs {:id "o1" :at (date "2026-01-01T10:00:00Z") :session "s1" :kind :confirmed :card-id "c1"})
               (an-obs {:id "o2" :at (date "2026-02-01T10:00:00Z") :session "s2" :kind :violated  :card-id "c1"})
               (an-obs {:id "o3" :at (date "2026-03-01T10:00:00Z") :session "s3" :kind :confirmed :card-id "c1"})
               (an-obs {:id "o4" :at (date "2026-04-01T10:00:00Z") :session "s4" :kind :confirmed :card-id "c1"})]
          contradictions (reconcile/detect-contextual-override c obs (date "2026-04-11T12:00:00Z"))]
      (is (= 1 (count contradictions)))
      (is (= :contextual-override (:contradiction/category (first contradictions))))))

  (testing "violation that continues being violated → NOT an override"
    (let [c   (a-card "c1")
          obs [(an-obs {:id "o1" :at (date "2026-01-01T10:00:00Z") :session "s1" :kind :confirmed :card-id "c1"})
               (an-obs {:id "o2" :at (date "2026-02-01T10:00:00Z") :session "s2" :kind :violated  :card-id "c1"})
               (an-obs {:id "o3" :at (date "2026-03-01T10:00:00Z") :session "s3" :kind :violated  :card-id "c1"})]]
      (is (empty? (reconcile/detect-contextual-override c obs (date "2026-04-11T12:00:00Z"))))))

  (testing "single session with violation → no override (need a later session)"
    (let [c   (a-card "c1")
          obs [(an-obs {:id "o1" :at (date "2026-01-01T10:00:00Z") :session "s1" :kind :confirmed :card-id "c1"})
               (an-obs {:id "o2" :at (date "2026-01-01T11:00:00Z") :session "s1" :kind :violated  :card-id "c1"})]]
      (is (empty? (reconcile/detect-contextual-override c obs (date "2026-04-11T12:00:00Z")))))))

;; ------------------------------------------------------------------
;; Combined pass
;; ------------------------------------------------------------------
(deftest detect-all-test
  (testing "combined pass finds contradictions across categories"
    (let [c1 (a-card "c1" :rule)
          obs-by-card {"c1" [(an-obs {:id "o1" :at (date "2026-03-01T10:00:00Z") :session "sA" :kind :confirmed :card-id "c1"})
                             (an-obs {:id "o2" :at (date "2026-03-01T11:00:00Z") :session "sA" :kind :violated  :card-id "c1"})]}
          metrics-by-card {"c1" {:weight 35.0 :violation-rate 0.0 :gap-crossings 5}}
          now (date "2026-04-11T12:00:00Z")
          all (reconcile/detect-all [c1] obs-by-card metrics-by-card now cfg)
          categories (set (map :contradiction/category all))]
      (is (contains? categories :self-contradictory))
      (is (contains? categories :tier-violation)))))
