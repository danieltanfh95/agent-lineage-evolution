(ns succession.identity.cli.replay
  "`bb -m succession.identity.core replay <transcript>` — dry-run
   harness that walks a recorded Claude Code session JSONL through
   the identity cycle against a sandbox directory.

   Purpose: catch shape regressions across the store/domain/LLM
   boundary *without* actually calling the judge/extract LLM. The
   harness substitutes synthetic verdicts (one :confirmed per tool
   call) so the replay is deterministic and cheap.

   What the harness does, per plan §Replay harness:

     1. Initialise a sandbox `.succession-next/` under `project-root`.
     2. Parse the transcript JSONL. Extract tool_use entries + their
        sessionId.
     3. For each tool use: append a synthetic :confirmed observation
        against a placeholder card-id derived from the tool name.
     4. At the end: materialise the staging snapshot, run pure
        reconcile detectors, and print a stats summary.
     5. Optionally snapshot the sandbox state and assert shape
        invariants.

   Not a full hook simulation — the hook layer is not implemented yet.
   This harness exists to exercise the data layers end-to-end and
   produce golden-output diffs once shapes stabilise.

   Reference: `.plans/succession-identity-cycle.md` §Replay harness."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [succession.identity.config :as config]
            [succession.identity.domain.observation :as dom-obs]
            [succession.identity.domain.reconcile :as reconcile]
            [succession.identity.store.cards :as store-cards]
            [succession.identity.store.observations :as store-obs]
            [succession.identity.store.paths :as paths]
            [succession.identity.store.staging :as store-staging]))

;; ------------------------------------------------------------------
;; Transcript parsing
;; ------------------------------------------------------------------

(defn- parse-line [line]
  (try (json/parse-string line true)
       (catch Throwable _ nil)))

(defn- tool-use-entries
  "Walk the JSONL and return `[{:tool-name :tool-input :session :at}]`
   for every tool_use entry. Handles both top-level :type \"tool_use\"
   and assistant messages that embed tool_use blocks in their content
   array."
  [^String transcript-path]
  (let [lines (when (.exists (io/file transcript-path))
                (str/split-lines (slurp transcript-path)))
        fallback-session (or (->> lines
                                  (keep parse-line)
                                  (keep :session_id)
                                  first)
                             "replay-session")]
    (->> lines
         (keep parse-line)
         (mapcat
           (fn [entry]
             (let [session (or (:session_id entry) fallback-session)
                   ts      (or (:timestamp entry) (str (java.time.Instant/now)))
                   at      (try (java.util.Date/from (java.time.Instant/parse ts))
                                (catch Throwable _ (java.util.Date.)))]
               (cond
                 ;; Top-level tool_use entry
                 (= "tool_use" (:type entry))
                 [{:tool-name  (:tool_name entry)
                   :tool-input (:tool_input entry)
                   :session    session
                   :at         at}]

                 ;; Assistant message with content array
                 (and (= "assistant" (:type entry))
                      (vector? (get-in entry [:message :content])))
                 (keep (fn [c]
                         (when (= "tool_use" (:type c))
                           {:tool-name  (:name c)
                            :tool-input (:input c)
                            :session    session
                            :at         at}))
                       (get-in entry [:message :content]))

                 :else []))))
         vec)))

;; ------------------------------------------------------------------
;; Synthetic verdicts
;; ------------------------------------------------------------------

(defn- placeholder-card-id
  "Map a tool name to a placeholder card-id so the synthetic
   observations can be attributed without guessing at real cards."
  [tool-name]
  (str "replay-placeholder-" (str/lower-case (or tool-name "unknown"))))

(defn- synth-observation
  [{:keys [tool-name session at]} idx]
  (dom-obs/make-observation
    {:id      (format "obs-replay-%05d" idx)
     :at      at
     :session session
     :hook    :post-tool-use
     :source  :judge-verdict
     :card-id (placeholder-card-id tool-name)
     :kind    :confirmed
     :context (str "replay synthetic verdict for " tool-name)}))

;; ------------------------------------------------------------------
;; Sandbox plumbing
;; ------------------------------------------------------------------

(defn- sandbox-root [project-root]
  (str project-root "/.succession-next"))

(defn- reset-sandbox!
  "Recursively delete and recreate the sandbox. Sandbox is a sibling
   of `.succession/` so a live install is never touched."
  [project-root]
  (let [root (io/file (sandbox-root project-root))]
    (when (.exists root)
      (letfn [(rm [^java.io.File f]
                (when (.isDirectory f)
                  (doseq [c (.listFiles f)] (rm c)))
                (.delete f))]
        (rm root)))
    (paths/ensure-dir! (paths/root (sandbox-root project-root)))))

;; ------------------------------------------------------------------
;; Harness entry
;; ------------------------------------------------------------------

(defn run
  "Replay `transcript-path` through the identity cycle in a sandbox
   under `project-root`. Returns a summary map; prints a report to
   `*out*`."
  [project-root transcript-path]
  (when-not (.exists (io/file transcript-path))
    (throw (ex-info "transcript not found" {:path transcript-path})))
  (let [sandbox   (sandbox-root project-root)
        _         (reset-sandbox! project-root)
        config-map (config/load-config project-root)

        entries   (tool-use-entries transcript-path)
        sessions  (into #{} (map :session) entries)

        ;; Stream synthetic observations through the store
        synth-obs (map-indexed (fn [i e] (synth-observation e i)) entries)
        _         (doseq [o synth-obs]
                    (store-obs/write-observation! sandbox o))

        ;; Append one :observe-card delta per tool use
        _         (doseq [[i e] (map-indexed vector entries)]
                    (store-staging/append-delta!
                      sandbox (:session e)
                      (store-staging/make-delta
                        {:id      (format "d-replay-%05d" i)
                         :at      (:at e)
                         :kind    :observe-card
                         :card-id (placeholder-card-id (:tool-name e))
                         :source  :judge})))

        ;; Rematerialise staging per session
        _         (doseq [sess sessions]
                    (store-staging/rematerialize! sandbox sess))

        ;; Materialise promoted (empty — no cards written in dry run)
        _         (store-cards/materialize-promoted! sandbox)

        ;; Pure reconcile pass
        all-obs   (store-obs/load-all-observations sandbox)
        by-card   (store-obs/observations-by-card all-obs)
        contradictions (reconcile/detect-all
                         []      ; no real cards in dry run
                         by-card
                         {}      ; no metrics
                         (java.util.Date.)
                         config-map)

        summary   {:transcript transcript-path
                   :sandbox    sandbox
                   :sessions   (count sessions)
                   :tool-uses  (count entries)
                   :observations-written (count synth-obs)
                   :contradictions-found (count contradictions)
                   :tool-distribution
                   (into {}
                         (map (fn [[k v]] [k (count v)]))
                         (group-by :tool-name entries))}]
    (println "=== replay summary ===")
    (println "transcript:" transcript-path)
    (println "sandbox:   " sandbox)
    (println "sessions:  " (:sessions summary))
    (println "tool-uses: " (:tool-uses summary))
    (println "observations written:" (:observations-written summary))
    (println "contradictions detected:" (:contradictions-found summary))
    (println "tool distribution:")
    (doseq [[t n] (sort-by (comp - val) (:tool-distribution summary))]
      (printf "  %-16s %d%n" (str t) n))
    summary))

(defn -main
  [& args]
  (let [project-root (or (System/getProperty "user.dir") ".")
        transcript   (first args)]
    (if (str/blank? transcript)
      (do (binding [*out* *err*]
            (println "usage: bb -m succession.identity.core replay <transcript.jsonl>"))
          (System/exit 1))
      (try (run project-root transcript)
           (System/exit 0)
           (catch Throwable t
             (binding [*out* *err*]
               (println "replay failed:" (.getMessage t)))
             (System/exit 1))))))
