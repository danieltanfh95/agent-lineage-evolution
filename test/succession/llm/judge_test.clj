(ns succession.llm.judge-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [succession.domain.card :as card]
            [succession.llm.judge :as judge]))

(defn- a-card
  [{:keys [id tier category fingerprint]
    :or {category :strategy}}]
  (card/make-card
    {:id id
     :tier tier
     :category category
     :text (str "first line of " id "\nmore body")
     :fingerprint fingerprint
     :provenance {:provenance/born-at         #inst "2026-01-01T00:00:00Z"
                  :provenance/born-in-session "s0"
                  :provenance/born-from       :user-correction
                  :provenance/born-context    "ctx"}}))

(deftest tool-prompt-includes-cards-test
  (testing "the tool prompt surfaces every active card in the digest"
    (let [cards [(a-card {:id "prefer-edit" :tier :rule})
                 (a-card {:id "never-force-push" :tier :principle})]
          prompt (judge/build-tool-prompt
                   {:tool-name "Bash"
                    :tool-input {:command "git push --force"}
                    :tool-response "ok"
                    :cards cards})]
      (is (str/includes? prompt "prefer-edit"))
      (is (str/includes? prompt "never-force-push"))
      (is (str/includes? prompt "[rule]"))
      (is (str/includes? prompt "[principle]"))
      (is (str/includes? prompt "git push --force")))))

(deftest tool-prompt-handles-map-shaped-response-test
  (testing "Claude Code passes structured maps as tool-response; the prompt
            builder must not throw ClassCastException on them"
    (let [cards  [(a-card {:id "prefer-edit" :tier :rule})]
          prompt (judge/build-tool-prompt
                   {:tool-name "Read"
                    :tool-input {:file_path "/x/y.clj"}
                    :tool-response {:type "text"
                                    :file {:filePath "/x/y.clj"
                                           :content "(ns foo)"}}
                    :cards cards})]
      (is (string? prompt))
      (is (str/includes? prompt "prefer-edit"))
      (is (str/includes? prompt "/x/y.clj"))
      (is (str/includes? prompt "response (truncated)"))))
  (testing "tool-input as a map is also coerced safely"
    (let [prompt (judge/build-tool-prompt
                   {:tool-name "Bash"
                    :tool-input {:command "ls -la"
                                 :description "list files"}
                    :tool-response nil
                    :cards []})]
      (is (string? prompt))
      (is (str/includes? prompt "ls -la")))))

(deftest turn-prompt-handles-map-shaped-inputs-test
  (let [prompt (judge/build-turn-prompt
                 {:tool-uses [{:tool-name "Edit"
                               :tool-input {:file_path "foo.clj"
                                            :old_string "a" :new_string "b"}}
                              {:tool-name "Bash"
                               :tool-input {:command "ls"}}]
                  :cards [(a-card {:id "c1" :tier :rule})]})]
    (is (string? prompt))
    (is (str/includes? prompt "foo.clj"))
    (is (str/includes? prompt "ls"))))

(deftest turn-prompt-lists-tool-uses-test
  (let [prompt (judge/build-turn-prompt
                 {:tool-uses [{:tool-name "Edit" :tool-input {:file_path "foo.clj"}}
                              {:tool-name "Bash" :tool-input {:command "ls"}}]
                  :cards [(a-card {:id "c1" :tier :rule})]})]
    (is (str/includes? prompt "Edit"))
    (is (str/includes? prompt "Bash"))
    (is (str/includes? prompt "2 calls"))))

(deftest parse-verdict-confirmed-test
  (let [v (judge/parse-verdict {:card_id "prefer-edit"
                                 :kind "confirmed"
                                 :rationale "used Edit on existing file"
                                 :confidence 0.9
                                 :escalate false})]
    (is (= "prefer-edit" (:card-id v)))
    (is (= :confirmed (:kind v)))
    (is (= 0.9 (:confidence v)))
    (is (false? (:escalate? v)))))

(deftest parse-verdict-coerces-unknown-kind-test
  (let [v (judge/parse-verdict {:card_id "c1" :kind "unknowable" :confidence 0.5})]
    (is (= :ambiguous (:kind v)))))

(deftest parse-response-single-and-array-test
  (testing "single object"
    (let [parsed (judge/parse-response
                   "{\"card_id\":\"c1\",\"kind\":\"confirmed\",\"confidence\":0.8}")]
      (is (= 1 (count parsed)))
      (is (= "c1" (:card-id (first parsed))))
      (is (= :confirmed (:kind (first parsed))))))
  (testing "array of objects"
    (let [parsed (judge/parse-response
                   "[{\"card_id\":\"c1\",\"kind\":\"confirmed\",\"confidence\":0.8},
                     {\"card_id\":\"c2\",\"kind\":\"violated\",\"confidence\":0.7}]")]
      (is (= 2 (count parsed)))
      (is (= #{"c1" "c2"} (set (map :card-id parsed)))))))

(deftest verdicts-to-observations-drops-not-applicable-test
  (let [verdicts [{:card-id "c1" :kind :confirmed :confidence 0.9
                   :rationale "..." :escalate? false}
                  {:card-id "none" :kind :not-applicable :confidence 0.5
                   :rationale "no match" :escalate? false}
                  {:card-id "c3" :kind :ambiguous :confidence 0.3
                   :rationale "unclear" :escalate? false}
                  {:card-id "c4" :kind :violated :confidence 0.85
                   :rationale "force push" :escalate? false}]
        obs (judge/verdicts->observations
              verdicts
              {:session "s1"
               :at      #inst "2026-04-11T12:00:00Z"
               :hook    :post-tool-use
               :id-fn   (let [n (atom 0)] #(str "obs-" (swap! n inc)))
               :judge-model "claude-sonnet-4-6"})]
    (is (= 2 (count obs)))
    (is (= #{"c1" "c4"} (set (map :observation/card-id obs))))
    (is (every? #(= :judge-verdict (:observation/source %)) obs))
    (let [obs-by-card (into {} (map (juxt :observation/card-id identity) obs))]
      (is (= :confirmed (:observation/kind (obs-by-card "c1"))))
      (is (= :violated  (:observation/kind (obs-by-card "c4"))))
      (is (= "claude-sonnet-4-6" (:observation/judge-model (obs-by-card "c1")))))))
