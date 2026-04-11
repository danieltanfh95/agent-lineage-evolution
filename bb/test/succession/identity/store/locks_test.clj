(ns succession.identity.store.locks-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [succession.identity.store.locks :as locks]
            [succession.identity.store.test-helpers :as h]))

(def ^:dynamic *root* nil)

(defn with-tmp-root [t]
  (let [root (h/tmp-dir! "succession-store-locks")]
    (binding [*root* root]
      (try (t)
           (finally (h/delete-tree! root))))))

(use-fixtures :each with-tmp-root)

(deftest try-lock-acquires-and-releases-test
  (let [h1 (locks/try-lock *root*)]
    (is (some? h1))
    (is (nil? (locks/try-lock *root*)) "a second try while held returns nil")
    (locks/release! h1)
    (let [h2 (locks/try-lock *root*)]
      (is (some? h2) "released lock can be re-acquired")
      (locks/release! h2))))

(deftest with-lock-macro-test
  (testing "with-lock body runs and releases"
    (let [ran? (atom false)]
      (locks/with-lock [_h *root*]
        (reset! ran? true))
      (is @ran?)
      (let [h (locks/try-lock *root*)]
        (is (some? h) "lock was released after with-lock body")
        (locks/release! h))))

  (testing "with-lock throws when already held"
    (let [held (locks/try-lock *root*)]
      (try
        (is (thrown? Exception
                     (locks/with-lock [_h *root*]
                       :unreachable)))
        (finally (locks/release! held))))))
