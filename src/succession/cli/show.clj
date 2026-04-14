(ns succession.cli.show
  "`succession show` — print the currently promoted identity
   cards as a markdown behavior tree.

   Motivation: until now the only way to see the active rules was to
   spawn a new Claude Code session and read the SessionStart
   additionalContext, which is awkward and requires an LLM in the
   loop. `show` surfaces the exact same view on demand from the
   terminal so the user (and the agent) can answer 'what are my
   currently activated rules?' without a round trip.

   Reuses `store/cards/read-promoted-snapshot`, `hook/common/score-cards`,
   and `domain/render/identity-tree` verbatim — the rendering pipeline
   is the same one SessionStart already uses, minus the orphan-staging
   footer and consult-skill hint.

   Flags:
     --format markdown  (default) — the behavior tree
     --format edn       — raw card EDN, one map per card, for piping

   Non-zero exit only on hard errors. An empty promoted store prints
   the renderer's own empty-state string ('_No promoted identity cards
   yet._') and exits 0."
  (:require [clojure.string :as str]
            [succession.config :as config]
            [succession.domain.render :as render]
            [succession.hook.common :as common]
            [succession.store.cards :as store-cards]))

(defn- parse-args
  "Tiny `--format <x>` parser. Unknown flags are ignored rather than
   fatal — this CLI is meant to be forgiving so the user can type it
   without reaching for docs."
  [args]
  (let [args (vec args)]
    (loop [i 0
           acc {:format :markdown}]
      (if (>= i (count args))
        acc
        (let [a (get args i)]
          (cond
            (= a "--format")
            (recur (+ i 2) (assoc acc :format (keyword (get args (inc i) "markdown"))))
            :else
            (recur (inc i) acc)))))))

(defn render-markdown
  "Render the scored cards as the markdown tree. Extracted for tests
   so we can feed fake data without touching disk."
  [scored]
  (render/identity-tree scored {:footer nil}))

(defn render-edn
  "Render raw card EDN — one `pr-str` map per line for easy piping
   into `jet`, `bb -e`, or `cat | head`. Observations and weights are
   dropped on purpose; this is the canonical on-disk card, not the
   scored view."
  [scored]
  (str/join "\n"
            (map (fn [{:keys [card]}] (pr-str card))
                 scored)))

(defn run
  [project-root args]
  (try
    (let [{:keys [format]} (parse-args args)
          cfg      (config/load-config project-root)
          snapshot (store-cards/read-promoted-snapshot project-root)
          cards    (or (:cards snapshot) [])
          scored   (common/score-cards project-root cards cfg (java.util.Date.))
          out      (case format
                     :edn      (render-edn scored)
                     :markdown (render-markdown scored)
                     (render-markdown scored))]
      (println out)
      0)
    (catch Throwable t
      (binding [*out* *err*]
        (println "succession show error:" (.getMessage t)))
      1)))
