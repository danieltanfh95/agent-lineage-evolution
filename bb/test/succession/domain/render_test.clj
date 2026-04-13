(ns succession.domain.render-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [succession.config :as config]
            [succession.domain.card :as card]
            [succession.domain.consult :as consult]
            [succession.domain.render :as render]))

(def cfg config/default-config)

(defn- a-card
  [{:keys [id tier category text tags fingerprint]
    :or {category :strategy
         text     nil}}]
  (card/make-card
    {:id id
     :tier tier
     :category category
     :text (or text (str "first line of " id "\nsecond line"))
     :tags tags
     :fingerprint fingerprint
     :provenance {:provenance/born-at         #inst "2026-01-01T00:00:00Z"
                  :provenance/born-in-session "s0"
                  :provenance/born-from       :user-correction
                  :provenance/born-context    "ctx"}}))

(defn- scored [card w] {:card card :weight w :recency-fraction 0.5})

(deftest identity-tree-groups-and-orders-test
  (testing "tree groups by tier (principle > rule > ethic) and by category"
    (let [cards [(scored (a-card {:id "p1" :tier :principle :category :strategy}) 42.0)
                 (scored (a-card {:id "r1" :tier :rule      :category :failure-inheritance}) 8.5)
                 (scored (a-card {:id "e1" :tier :ethic     :category :meta-cognition}) 1.2)]
          out (render/identity-tree cards {})]
      (is (str/includes? out "Mandatory"))
      (is (str/includes? out "Must"))
      (is (str/includes? out "Preferred"))
      (is (< (.indexOf out "Mandatory") (.indexOf out "Must")))
      (is (< (.indexOf out "Must") (.indexOf out "Preferred")))
      (is (str/includes? out "p1"))
      (is (str/includes? out "weight 42.0")))))

(deftest identity-tree-empty-test
  (testing "empty cards produces a placeholder, not an exception"
    (let [out (render/identity-tree [] {})]
      (is (string? out))
      (is (str/includes? out "No promoted identity cards")))))

(deftest identity-tree-footer-test
  (testing "footer is appended with a separator"
    (let [cards [(scored (a-card {:id "p1" :tier :principle}) 42.0)]
          out (render/identity-tree cards {:footer "consult me via bb succession consult"})]
      (is (str/includes? out "consult me"))
      (is (str/includes? out "---")))))

(deftest salient-reminder-short-test
  (testing "salient reminder is compact and bullet-formatted"
    (let [ranked [{:card (a-card {:id "prefer-edit" :tier :rule})
                   :score 3.0}]
          out (render/salient-reminder ranked "Identity reminder")]
      (is (str/includes? out "Identity reminder"))
      (is (str/includes? out "prefer-edit"))
      ;; no weight in salient reminder (too long)
      (is (not (str/includes? out "weight"))))))

(deftest salient-reminder-empty-test
  (is (= "" (render/salient-reminder [] "Identity reminder"))))

(deftest consult-view-renders-tiers-and-tensions-test
  (testing "consult-view renders tier blocks and a tensions section"
    (let [c1 (a-card {:id "never-force-push-main" :tier :principle
                       :fingerprint "tool=Bash,cmd=git push --force"})
          scored-cards [(scored c1 42.0)]
          situation {:situation/text "about to force-push to main"
                     :situation/tool-descriptor "tool=Bash,cmd=git push --force origin main"}
          result (consult/query scored-cards situation cfg)
          out (render/consult-view result)]
      (is (str/includes? out "# Consult"))
      (is (str/includes? out "about to force-push"))
      (is (str/includes? out "Mandatory"))
      (is (str/includes? out "never-force-push-main"))
      (is (str/includes? out "tensions"))
      (is (str/includes? out "principle-forbids")))))

(deftest consult-view-no-candidates-test
  (testing "a situation with no candidates still renders cleanly"
    (let [result (consult/query [] {:situation/text "hi"} cfg)
          out (render/consult-view result)]
      (is (str/includes? out "# Consult"))
      (is (str/includes? out "No candidate cards")))))
