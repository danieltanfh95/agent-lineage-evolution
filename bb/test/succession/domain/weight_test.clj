(ns succession.domain.weight-test
  "Test battery for the temporal-span weight formula.

   The scenarios come directly from `.plans/succession-identity-cycle.md`
   §Weight formula. Each scenario is a pair of hypothetical cards; the
   assertion is about ordering, not absolute numbers. This lets us tune
   the formula without rewriting the tests."
  (:require [clojure.test :refer [deftest is testing]]
            [succession.config :as config]
            [succession.domain.observation :as obs]
            [succession.domain.rollup :as rollup]
            [succession.domain.weight :as weight]))

(def cfg config/default-config)

(defn- date [s] (java.util.Date/from (java.time.Instant/parse s)))

(defn- o
  "Succinct observation builder. Takes kwargs as a map."
  [{:keys [session at kind] :or {kind :confirmed}}]
  (obs/make-observation
    {:id       (str (random-uuid))
     :at       at
     :session  session
     :hook     :post-tool-use
     :source   :judge-verdict
     :card-id  "any"
     :kind     kind}))

(defn- weight-of
  "Compute weight for a list of observations at a given `now`."
  [observations now]
  (weight/compute (rollup/rollup-by-session observations) now cfg))

;; ------------------------------------------------------------------
;; Scenario 1 — span dominates frequency
;;
;; Card A: 1000 observations, all within 1 week, 0 gap-crossings.
;; Card B: 2 observations, spanning 1 year, 1 gap-crossing.
;; Expected: B > A
;; ------------------------------------------------------------------
(deftest scenario-1-span-dominates-frequency-test
  (let [now    (date "2026-12-31T00:00:00Z")
        card-a (for [i (range 1000)]
                 (o {:session (str "sa-" i)   ; use distinct sessions so this is
                     :at      (date (format "2026-01-%02dT10:00:00Z"          ;; an extreme case
                                            (inc (mod i 7))))}))
        card-b [(o {:session "sb1" :at (date "2026-01-15T10:00:00Z")})
                (o {:session "sb2" :at (date "2026-12-15T10:00:00Z")})]
        wa (weight-of card-a now)
        wb (weight-of card-b now)]
    (testing "year-spanning gap-crossing card outweighs dense week-long card"
      (is (> wb wa)
          (format "expected B(%f) > A(%f)" wb wa)))))

;; ------------------------------------------------------------------
;; Scenario 2 — session-density penalty
;;
;; Card C: 5 observations, all same session.
;; Card D: 3 observations, 3 sessions across 30 days.
;; Expected: D > C
;; ------------------------------------------------------------------
(deftest scenario-2-session-density-penalty-test
  (let [now    (date "2026-04-01T00:00:00Z")
        card-c (for [i (range 5)]
                 (o {:session "sc1"
                     :at (date (format "2026-03-01T10:%02d:00Z" i))}))
        card-d [(o {:session "sd1" :at (date "2026-03-01T10:00:00Z")})
                (o {:session "sd2" :at (date "2026-03-15T10:00:00Z")})
                (o {:session "sd3" :at (date "2026-03-29T10:00:00Z")})]
        wc (weight-of card-c now)
        wd (weight-of card-d now)]
    (testing "multi-session card outweighs single-session dense card"
      (is (> wd wc)
          (format "expected D(%f) > C(%f)" wd wc)))))

;; ------------------------------------------------------------------
;; Scenario 3 — decay penalizes stale cards
;;
;; Card E: high weight long ago, silent for much longer than half-life.
;; Card F: lower weight, recent activity with 2 gap-crossings.
;; Expected: F > E after decay kicks in
;; ------------------------------------------------------------------
(deftest scenario-3-decay-test
  (let [now    (date "2027-01-01T00:00:00Z")
        card-e [(o {:session "se1" :at (date "2025-01-01T10:00:00Z")})
                (o {:session "se2" :at (date "2025-02-01T10:00:00Z")})
                (o {:session "se3" :at (date "2025-03-01T10:00:00Z")})
                (o {:session "se4" :at (date "2025-04-01T10:00:00Z")})]
        card-f [(o {:session "sf1" :at (date "2026-10-01T10:00:00Z")})
                (o {:session "sf2" :at (date "2026-11-15T10:00:00Z")})
                (o {:session "sf3" :at (date "2026-12-20T10:00:00Z")})]
        we (weight-of card-e now)
        wf (weight-of card-f now)]
    (testing "old-and-silent card decays below fresh-and-active card"
      (is (> wf we)
          (format "expected F(%f) > E(%f) — F is active, E is stale" wf we)))))

;; ------------------------------------------------------------------
;; Scenario 4 — violation penalty (base-matched)
;;
;; To isolate the violation penalty, both cards must have the *same*
;; base inputs (freq, span, gap-crossings). We arrange this by using
;; the same four sessions for both:
;;   Card G: each session has 1 confirmed + 1 violated → rate 0.5
;;   Card H: each session has 1 confirmed only          → rate 0.0
;; Base is identical; H should strictly dominate G.
;; ------------------------------------------------------------------
(deftest scenario-4-violation-penalty-test
  (let [now      (date "2026-06-01T00:00:00Z")
        sessions ["2026-01-01T10:00:00Z"
                  "2026-02-01T10:00:00Z"
                  "2026-03-01T10:00:00Z"
                  "2026-04-01T10:00:00Z"]
        card-g (mapcat
                 (fn [ts]
                   (let [session-id (str "s-" ts)
                         at         (date ts)]
                     [(o {:session session-id :at at :kind :confirmed})
                      (o {:session session-id :at at :kind :violated})]))
                 sessions)
        card-h (map
                 (fn [ts]
                   (o {:session (str "s-" ts)
                       :at      (date ts)
                       :kind    :confirmed}))
                 sessions)
        wg (weight-of card-g now)
        wh (weight-of card-h now)]
    (testing "violation-free card strictly dominates base-matched violated card"
      (is (> wh wg)
          (format "expected H(%f) > G(%f) at equal base inputs" wh wg)))
    (testing "the penalty is proportional to the violation-penalty-rate config"
      ;; With default :weight/violation-penalty-rate 0.5 and rate 0.5,
      ;; penalty = base * 0.25, so G = 0.75 * H.
      (is (< (Math/abs (- wg (* 0.75 wh))) 0.001)
          (format "expected G ≈ 0.75 * H: got wg=%f, 0.75*wh=%f" wg (* 0.75 wh))))))

;; ------------------------------------------------------------------
;; Scenario 5 — single observation
;;
;; Card I: one observation, span 0, gap_crossings 0. Weight should be
;; small — it enters the ethic floor, not principle.
;; ------------------------------------------------------------------
(deftest scenario-5-single-observation-test
  (let [now   (date "2026-04-01T00:00:00Z")
        card-i [(o {:session "si1" :at (date "2026-03-31T10:00:00Z")})]
        wi (weight-of card-i now)]
    (testing "single fresh observation has small weight"
      (is (pos? wi) "should not be zero — it's an observation")
      (is (< wi 5.0)
          (format "single observation weight %f should not qualify for :rule tier" wi)))))

;; ------------------------------------------------------------------
;; Empty-rollup guard — card with no observations has zero weight
;; ------------------------------------------------------------------
(deftest empty-rollup-is-zero-test
  (is (= 0.0 (weight-of [] (date "2026-04-01T00:00:00Z")))))

;; ------------------------------------------------------------------
;; Consulted-only guard — a card that has only been consulted (never
;; confirmed/violated/invoked) has zero contributing weight.
;; This is the anti-gaming rule: consult doesn't reinforce.
;; ------------------------------------------------------------------
(deftest consulted-only-is-zero-test
  (let [now (date "2026-04-01T00:00:00Z")
        obs [(o {:session "c1" :at (date "2026-03-01T10:00:00Z") :kind :consulted})
             (o {:session "c2" :at (date "2026-03-15T10:00:00Z") :kind :consulted})
             (o {:session "c3" :at (date "2026-03-29T10:00:00Z") :kind :consulted})]]
    (is (= 0.0 (weight-of obs now))
        "consulted-only observations should not contribute to weight")))
