(ns succession.cli.consult
  "`succession consult \"<situation>\"` — agent-invoked identity
   consultation.

   This is NOT a hook. The agent runs this via Bash when it wants a
   reflective second opinion on an upcoming action. Shape:

     1. Read promoted.edn + active staging snapshot + config.
     2. domain/consult (pure) → candidate set + tensions.
     3. domain/render/consult-view → prompt body.
     4. Wrap with framing text and call llm/claude.
     5. Print the LLM reflection to stdout.
     6. Append :consulted observations (weight-neutral) via
        store/observations for audit.

   Reference: `.plans/succession-identity-cycle.md` §Consultation."
  (:require [clojure.string :as str]
            [succession.config :as config]
            [succession.domain.consult :as consult]
            [succession.domain.render :as render]
            [succession.domain.rollup :as rollup]
            [succession.domain.weight :as weight]
            [succession.llm.transport :as transport]
            [succession.domain.observation :as dom-obs]
            [succession.store.cards :as store-cards]
            [succession.store.staging :as store-staging]
            [succession.store.observations :as store-obs]
            [succession.store.contradictions :as store-contra]))

;; ------------------------------------------------------------------
;; Arg parsing
;; ------------------------------------------------------------------

(def ^:private usage-text
  "Usage: succession consult [options] <situation>

Options:
  --intent <text>          What the agent was trying to accomplish
  --tool-name <name>       Tool being called (for hook context)
  --tool-input <json>      Tool input JSON (for hook context)
  --recent-context <text>  Recent conversation snippet
  --category <kw>          Filter to a single card category
  --tier <kw>              Filter to a single tier (core|active|dormant)
  --exclude <c1,c2,...>    Comma-separated categories to exclude
  --session <path>         Path to a JSONL session transcript
  --dry-run                Print the prompt without calling the LLM")

(defn parse-args
  "Parse the CLI flag list into an opts map. The positional argument
   is the situation text."
  [args]
  (loop [args (seq args)
         opts {}]
    (if-not args
      opts
      (let [[a & rest] args]
        (case a
          "--intent"         (recur (next rest) (assoc opts :intent (first rest)))
          "--tool-name"      (recur (next rest) (assoc opts :tool-name (first rest)))
          "--tool-input"     (recur (next rest) (assoc opts :tool-input (first rest)))
          "--recent-context" (recur (next rest) (assoc opts :recent-context (first rest)))
          "--category"       (recur (next rest) (assoc opts :category (keyword (first rest))))
          "--tier"           (recur (next rest) (assoc opts :tier (keyword (first rest))))
          "--exclude"        (recur (next rest) (assoc opts :exclude
                                                       (set (str/split (first rest) #","))))
          "--session"        (recur (next rest) (assoc opts :session (first rest)))
          "--dry-run"        (recur rest        (assoc opts :dry-run? true))
          ("--help" "-h")    (do (println usage-text)
                                 (System/exit 0))
          ;; positional
          (recur rest (update opts :situation-parts (fnil conj []) a)))))))

(defn- situation-text [opts]
  (str/join " " (:situation-parts opts [])))

;; ------------------------------------------------------------------
;; Building the situation + candidate pool
;; ------------------------------------------------------------------

(defn- exclude-ids [cards excluded]
  (if (seq excluded)
    (remove #(contains? excluded (:card/id %)) cards)
    cards))

(defn- filter-by-tier [cards tier]
  (if tier (filter #(= tier (:card/tier %)) cards) cards))

(defn- filter-by-category [cards cat]
  (if cat (filter #(= cat (:card/category %)) cards) cards))

(defn- build-tool-descriptor [opts]
  (let [tool (:tool-name opts)
        input (:tool-input opts)]
    (when (or tool input)
      (str "tool=" (or tool "?") (when input (str ",input=" input))))))

(defn- recency-fraction
  "Map the card's most-recent observation to a 0..1 fraction. 1 = just
   observed, 0 = older than the decay half-life. This is the input
   salience wants — the weight formula already bakes decay in, so
   recency here is a separate signal for ranking freshness."
  [rollup-map now half-life-days]
  (if (empty? rollup-map)
    0.0
    (let [last-at (reduce #(if (pos? (compare %1 %2)) %1 %2)
                          (map :session/last-at (vals rollup-map)))
          age-ms  (- (.getTime ^java.util.Date now)
                     (.getTime ^java.util.Date last-at))
          age-days (max 0.0 (/ age-ms 86400000.0))]
      (max 0.0 (min 1.0 (- 1.0 (/ age-days (double half-life-days))))))))

(defn cards-to-scored
  "Fold observation rollups into the `{:card :weight :recency-fraction}`
   shape salience/consult consume. Reads observations from disk once
   (all sessions) and groups by card id. Cards with no observations
   get weight 0 / recency 0 — they still appear in the candidate pool
   at tier-baseline score, which is the right behavior for newly born
   cards that have not yet accumulated evidence."
  [project-root cards config now]
  (let [all-obs   (store-obs/load-all-observations project-root)
        by-card   (store-obs/observations-by-card all-obs)
        half-life (or (:weight/decay-half-life-days config) 180)]
    (mapv (fn [c]
            (let [obs    (get by-card (:card/id c) [])
                  rollup (rollup/rollup-by-session obs)
                  w      (weight/compute rollup now config)
                  r      (recency-fraction rollup now half-life)]
              {:card c :weight w :recency-fraction r}))
          cards)))

;; ------------------------------------------------------------------
;; Prompt framing
;; ------------------------------------------------------------------

(defn build-framed-prompt
  "Wrap the consult-view markdown with framing text that tells
   `claude -p` who it is and what to produce. The output format the
   consult skill teaches the agent to expect is four sections:
   Principle / Rule / Ethic / tensions / reflection."
  [consult-view-md intent]
  (str
    "You are the reflective voice of an agent's own identity. The\n"
    "agent is about to act and has asked you — its identity — what\n"
    "is relevant and what tensions apply.\n\n"

    "Produce your answer as markdown with these sections, in this\n"
    "exact order:\n\n"
    "## Principle · inviolable\n"
    "## Rule · default behavior\n"
    "## Ethic · character\n"
    "## tensions\n"
    "## reflection\n\n"

    "In the tier sections, list the relevant card ids with weight\n"
    "and a short one-liner. In `tensions`, list each tension from\n"
    "the snapshot using its bracketed label exactly as shown (e.g.\n"
    "`[principle-forbids]`, `[tier-split]`). In `reflection`,\n"
    "write 2-4 sentences as the agent's second opinion on itself.\n"
    "Do NOT apologise, do NOT explain that you are an AI.\n\n"

    (when intent
      (str "The agent's stated intent: " intent "\n\n"))

    "IDENTITY SNAPSHOT + SITUATION:\n"
    "----------------------------------------------------------\n"
    consult-view-md "\n"
    "----------------------------------------------------------\n"))

;; ------------------------------------------------------------------
;; Observation logging
;; ------------------------------------------------------------------

(defn- consulted-observation
  "Construct a :consulted observation for one card. Weight-neutral
   per plan — it still gets written for audit."
  [card {:keys [session at]}]
  (dom-obs/make-observation
    {:id      (str "obs-consulted-" (random-uuid))
     :at      at
     :session session
     :hook    :post-tool-use    ; closest fit; consult is agent-triggered mid-PostToolUse
     :source  :consult
     :card-id (:card/id card)
     :kind    :consulted
     :context "agent consulted via succession consult"}))

(defn- log-consulted-cards!
  [project-root session at candidates]
  (doseq [c candidates]
    (store-obs/write-observation!
      project-root
      (consulted-observation (:card c) {:session session :at at}))))

;; ------------------------------------------------------------------
;; Main entry
;; ------------------------------------------------------------------

(defn run
  "Execute a consult query. `project-root` is the directory that
   contains `.succession/`. `args` is the raw arg vector.

   Returns a map describing what happened; the CLI wrapper prints the
   reflection text to stdout."
  [project-root args]
  (let [opts      (parse-args args)
        situation (situation-text opts)
        _         (when (str/blank? situation)
                    (throw (ex-info "consult requires a situation string"
                                    {:opts opts})))
        config-map (config/load-config project-root)
        session    (or (:session opts) (str (random-uuid)))
        now        (java.util.Date.)

        ;; Load identity
        snapshot   (store-cards/read-promoted-snapshot project-root)
        all-cards  (or (:cards snapshot) [])
        staged     (when (:session opts)
                     (store-staging/read-snapshot project-root (:session opts)))

        ;; Filter pool
        filtered   (-> all-cards
                       (exclude-ids    (:exclude opts))
                       (filter-by-tier (:tier opts))
                       (filter-by-category (:category opts)))

        situation-map {:situation/text              situation
                       :situation/tool-descriptor   (build-tool-descriptor opts)
                       :situation/contradictions    (store-contra/open-contradictions project-root)}

        consult-result (consult/query (cards-to-scored project-root filtered config-map now)
                                      situation-map
                                      config-map)
        view-md   (render/consult-view consult-result)
        prompt    (build-framed-prompt view-md (:intent opts))

        llm-cfg   (:consult/llm config-map)
        model     (or (:model llm-cfg) "claude-sonnet-4-6")
        timeout   (or (:timeout-seconds llm-cfg) 60)

        result    (if (:dry-run? opts)
                    {:ok? true :text view-md :cost-usd 0.0 :latency-ms 0}
                    (transport/call prompt {:model-id     model
                                            :timeout-secs timeout
                                            :input-toks   1500
                                            :output-toks  400}))

        reflection (if (:ok? result)
                     (:text result)
                     (str "[consult error] " (or (:error result) "unknown")))]

    ;; Audit log — even on LLM failure, the consult was attempted
    (when (seq (:consult/candidates consult-result))
      (log-consulted-cards!
        project-root session now (:consult/candidates consult-result)))

    {:ok?              (boolean (:ok? result))
     :reflection       reflection
     :candidate-count  (count (:consult/candidates consult-result))
     :tension-count    (count (:consult/tensions consult-result))
     :cost-usd         (or (:cost-usd result) 0.0)
     :latency-ms       (or (:latency-ms result) 0)
     :session          session}))

(defn run-from-args
  "Dispatch-friendly entry that takes only the args vector and
   derives project-root from user.dir. Prints reflection to stdout,
   exits 0 on success."
  [args]
  (let [project-root (or (System/getProperty "user.dir") ".")
        result (try (run project-root args)
                    (catch Throwable t
                      (binding [*out* *err*]
                        (println "consult failed:" (.getMessage t)))
                      nil))]
    (if (and result (:ok? result))
      (do (println (:reflection result))
          (System/exit 0))
      (System/exit 1))))

(defn -main
  "Thin shell entry."
  [& args]
  (run-from-args args))
