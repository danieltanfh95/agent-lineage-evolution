(ns succession.hooks.common
  "Shared hook helpers. Every new hook that might spawn a judge
   subprocess calls require-not-judge-subprocess! as its first line so
   recursion is impossible even if a regex filter ever misses.")

(def judge-subprocess-env-var "SUCCESSION_JUDGE_SUBPROCESS")

(defn judge-subprocess?
  "True when this process is (directly or transitively) a judge
   subprocess spawned by another Succession hook. Used to short-circuit
   all hook behavior in the child so it cannot recurse into itself."
  []
  (= "1" (System/getenv judge-subprocess-env-var)))

(defn require-not-judge-subprocess!
  "Early exit for hooks invoked from inside a judge subprocess. Prints
   a no-op marker on stderr for debuggability and exits 0 so the parent
   Claude Code harness treats it as success."
  []
  (when (judge-subprocess?)
    (binding [*out* *err*]
      (println "Succession: hook no-op (judge subprocess)"))
    (System/exit 0)))
