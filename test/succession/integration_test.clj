(ns succession.integration-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- bbin-available? []
  (some-> (System/getenv "PATH")
          (str/split #":")
          (->> (some #(.exists (io/file % "succession"))))))

(defn- run-cmd [cmd & {:keys [cwd]}]
  (let [pb  (ProcessBuilder. (into-array String ["sh" "-c" cmd]))
        _   (when cwd (.directory pb (io/file cwd)))
        _   (.redirectErrorStream pb true)
        p   (.start pb)
        out (slurp (.getInputStream p))
        rc  (.waitFor p)]
    {:out out :exit rc}))

(deftest help-output-test
  (if-not (bbin-available?)
    (is false "succession binary not in PATH — install via bbin to run integration tests")
    (testing "succession --help prints HELP.md content"
      (let [{:keys [out exit]} (run-cmd "succession --help")]
        (is (zero? exit))
        (is (str/includes? out "# succession"))
        (is (str/includes? out "consult"))))))

(deftest project-root-from-subdir-test
  (if-not (bbin-available?)
    (is false "succession binary not in PATH — install via bbin to run integration tests")
    (testing "succession identity-diff --list resolves to git root, not bb/"
      (let [bb-dir (str (System/getProperty "user.dir"))
            {:keys [out exit]} (run-cmd "succession identity-diff --list" :cwd bb-dir)]
        (is (zero? exit))
        (is (not (str/includes? out "bb/.succession")))))))

(deftest install-skill-test
  (if-not (bbin-available?)
    (is false "succession binary not in PATH — install via bbin to run integration tests")
    (testing "succession --install-skill writes file with YAML frontmatter"
      (let [tmp-path (str (System/getProperty "java.io.tmpdir") "/succession-skill-test.md")
            {:keys [exit]} (run-cmd (str "succession --install-skill --path " tmp-path))
            f (io/file tmp-path)]
        (is (zero? exit))
        (is (.exists f))
        (is (str/starts-with? (slurp f) "---"))
        (.delete f)))))
