(ns succession.hook.pre-tool-use-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [succession.hook.pre-tool-use :as ptu]
            [succession.store.test-helpers :as h]))

(deftest build-reminder-shape-test
  (testing "non-empty ranked produces a reminder with the header"
    (let [ranked [{:card  (h/a-card {:id "p1" :tier :principle :text "always verify"})
                   :score 9.9}]
          out    (ptu/build-reminder ranked)]
      (is (str/includes? out "Salient identity"))
      (is (str/includes? out "p1")))))
