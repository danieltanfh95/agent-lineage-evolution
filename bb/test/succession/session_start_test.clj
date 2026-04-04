(ns succession.session-start-test
  (:require [clojure.test :refer [deftest is testing]]
            [succession.hooks.session-start :as ss]
            [babashka.fs :as fs]
            [clojure.string :as str]))

(deftest load-skills-from-test
  (let [tmp-dir (str (fs/create-temp-dir {:prefix "ss-skills-"}))
        skills-dir (str tmp-dir "/skills")
        cwd (str tmp-dir "/project")]
    (try
      (fs/create-dirs (str skills-dir "/my-skill"))
      (spit (str skills-dir "/my-skill/SKILL.md") "# My Skill\nDo stuff")
      (fs/create-dirs (str cwd "/.claude/skills"))

      (testing "copies skill to .claude/skills"
        (let [loaded (ss/load-skills-from #{} skills-dir cwd)]
          (is (contains? loaded "my-skill"))
          (is (fs/exists? (str cwd "/.claude/skills/my-skill/SKILL.md")))
          (is (= "# My Skill\nDo stuff"
                  (slurp (str cwd "/.claude/skills/my-skill/SKILL.md"))))))

      (testing "deduplicates with loaded set"
        (let [loaded (ss/load-skills-from #{"my-skill"} skills-dir cwd)]
          ;; Should still contain it (from passed-in set) but not re-copy
          (is (contains? loaded "my-skill"))))

      (testing "handles nonexistent directory"
        (let [loaded (ss/load-skills-from #{} "/nonexistent/skills" cwd)]
          (is (empty? loaded))))

      (testing "skips directories without SKILL.md"
        (fs/create-dirs (str skills-dir "/empty-skill"))
        (let [loaded (ss/load-skills-from #{} skills-dir cwd)]
          (is (contains? loaded "my-skill"))
          (is (not (contains? loaded "empty-skill")))))

      (finally
        (fs/delete-tree tmp-dir)))))
