(ns succession.test-runner
  (:require [clojure.test :as t]
            ;; domain
            [succession.domain.card-test]
            [succession.domain.consult-test]
            [succession.domain.observation-test]
            [succession.domain.queue-test]
            [succession.domain.reconcile-test]
            [succession.domain.render-test]
            [succession.domain.rollup-test]
            [succession.domain.salience-test]
            [succession.domain.tier-test]
            [succession.domain.weight-test]
            ;; store
            [succession.store.archive-test]
            [succession.store.cards-test]
            [succession.store.contradictions-test]
            [succession.store.jobs-test]
            [succession.store.locks-test]
            [succession.store.observations-test]
            [succession.store.sessions-test]
            [succession.store.staging-test]
            ;; cli
            [succession.cli.config-validate-test]
            [succession.cli.consult-test]
            [succession.cli.identity-diff-test]
            [succession.cli.identity-test]
            [succession.cli.import-test]
            [succession.cli.install-test]
            [succession.cli.queue-test]
            [succession.cli.replay-test]
            [succession.cli.show-test]
            ;; hook
            [succession.hook.common-test]
            [succession.hook.post-tool-use-test]
            [succession.hook.pre-compact-test]
            [succession.hook.pre-tool-use-test]
            [succession.hook.session-start-test]
            [succession.hook.stop-test]
            [succession.hook.user-prompt-submit-test]
            ;; llm
            [succession.llm.extract-test]
            [succession.llm.judge-test]
            [succession.llm.reconcile-test]
            ;; worker
            [succession.worker.drain-test]
            ;; integration (guarded by bbin availability)
            [succession.integration-test]))

(def ^:private unit-ns
  '[succession.domain.card-test
    succession.domain.consult-test
    succession.domain.observation-test
    succession.domain.queue-test
    succession.domain.reconcile-test
    succession.domain.render-test
    succession.domain.rollup-test
    succession.domain.salience-test
    succession.domain.tier-test
    succession.domain.weight-test
    succession.store.archive-test
    succession.store.cards-test
    succession.store.contradictions-test
    succession.store.jobs-test
    succession.store.locks-test
    succession.store.observations-test
    succession.store.sessions-test
    succession.store.staging-test
    succession.cli.config-validate-test
    succession.cli.consult-test
    succession.cli.identity-diff-test
    succession.cli.identity-test
    succession.cli.import-test
    succession.cli.install-test
    succession.cli.queue-test
    succession.cli.replay-test
    succession.cli.show-test
    succession.hook.common-test
    succession.hook.post-tool-use-test
    succession.hook.pre-compact-test
    succession.hook.pre-tool-use-test
    succession.hook.session-start-test
    succession.hook.stop-test
    succession.hook.user-prompt-submit-test
    succession.llm.extract-test
    succession.llm.judge-test
    succession.llm.reconcile-test
    succession.worker.drain-test])

(def ^:private int-ns
  '[succession.integration-test])

(defn run-all [& {:keys [unit-only?]}]
  (let [nss (if unit-only? unit-ns (into unit-ns int-ns))]
    (apply t/run-tests nss)))

(defn -main [& args]
  (let [{:keys [fail error]} (run-all :unit-only? (some #{"--unit"} args))]
    (System/exit (if (zero? (+ fail error)) 0 1))))
