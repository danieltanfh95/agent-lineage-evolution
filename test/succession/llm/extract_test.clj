(ns succession.llm.extract-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [succession.llm.extract :as extract]))

(deftest build-prompt-includes-transcript-and-existing-test
  (let [prompt (extract/build-prompt
                 {:transcript-text "user: use Edit, not Write\nassistant: ok"
                  :existing-card-ids ["prefer-edit" "no-silent-catch"]})]
    (is (str/includes? prompt "user: use Edit"))
    (is (str/includes? prompt "prefer-edit"))
    (is (str/includes? prompt "no-silent-catch"))
    (is (str/includes? prompt "strategy"))
    (is (str/includes? prompt "failure-inheritance"))))

(deftest build-prompt-none-existing-test
  (let [prompt (extract/build-prompt
                 {:transcript-text "..."
                  :existing-card-ids []})]
    (is (str/includes? prompt "(none)"))))

(deftest parse-card-valid-test
  (let [c (extract/parse-card
            {:id "prefer-edit"
             :category "strategy"
             :text "use Edit on existing files"
             :fingerprint "tool=Edit"
             :tags ["file-editing"]})]
    (is (= "prefer-edit" (:id c)))
    (is (= :strategy     (:category c)))
    (is (= "tool=Edit"   (:fingerprint c)))
    (is (= [:file-editing] (:tags c)))))

(deftest parse-card-rejects-invalid-category-test
  (is (nil? (extract/parse-card
              {:id "x" :category "nonsense" :text "..."}))))

(deftest parse-card-rejects-missing-fields-test
  (is (nil? (extract/parse-card {:id "x"})))
  (is (nil? (extract/parse-card {:text "..."}))))

(deftest parse-observation-valid-test
  (let [o (extract/parse-observation
            {:card_id "c1" :kind "confirmed" :context "..."})]
    (is (= "c1" (:card-id o)))
    (is (= :confirmed (:kind o)))))

(deftest parse-observation-drops-invalid-kind-test
  (is (nil? (extract/parse-observation
              {:card_id "c1" :kind "bogus"})))
  (is (nil? (extract/parse-observation
              {:card_id "c1" :kind "not-applicable"}))))

(deftest parse-response-full-test
  (let [text "{\"cards\":[
                 {\"id\":\"prefer-edit\",\"category\":\"strategy\",\"text\":\"use Edit\"},
                 {\"id\":\"bad\",\"category\":\"nonsense\",\"text\":\"...\"}
               ],
               \"observations\":[
                 {\"card_id\":\"prefer-edit\",\"kind\":\"confirmed\",\"context\":\"\"},
                 {\"card_id\":\"x\",\"kind\":\"not-applicable\"}
               ]}"
        parsed (extract/parse-response text)]
    (is (= 1 (count (:cards parsed))) "bad card filtered")
    (is (= 1 (count (:observations parsed))) "not-applicable obs filtered")
    (is (= "prefer-edit" (:id (first (:cards parsed)))))))

(deftest parse-response-empty-test
  (let [parsed (extract/parse-response "{\"cards\":[],\"observations\":[]}")]
    (is (= [] (:cards parsed)))
    (is (= [] (:observations parsed)))))

(deftest parse-response-garbage-test
  (is (nil? (extract/parse-response "not json")))
  (is (nil? (extract/parse-response nil))))
