(ns succession.domain.render
  "Pure rendering of cards and candidate sets to markdown.

   Produces the strings consumed by three surfaces:

     - `identity-tree`  : SessionStart additionalContext — the full
                          behavior tree of promoted cards, organized by
                          tier then category.
     - `salient-reminder`: PreToolUse / PostToolUse refresh — a compact
                          reminder of the top-K salient cards for the
                          current situation (~300-400 bytes).
     - `consult-view`   : input to `claude -p` from `cli/consult`; shows
                          candidates grouped by tier, tensions, and the
                          situation text.

   This namespace is pure: it takes data and returns strings. It does not
   read files, it does not call LLMs, it does not know what hook it's
   rendering for. The caller (hook or cli) glues context around the
   rendered text.

   All tier labels are romanized in rendered output. Kanji appears in
   docs (etymology) only."
  (:require [clojure.string :as str]))

(def ^:private tier-header
  {:principle "Mandatory · inviolable"
   :rule      "Must · default behavior"
   :ethic     "Preferred · character"})

(def ^:private tier-order [:principle :rule :ethic])

(def ^:private category-label
  {:strategy                "Strategy"
   :failure-inheritance     "Failure inheritance"
   :relational-calibration  "Relational calibration"
   :meta-cognition          "Meta-cognition"})

(defn- section-marker?
  "True if line is a section marker (<!-- human: ... --> or <!-- llm: ... -->)."
  [line]
  (boolean (re-matches #"<!--\s*(human|llm):.*-->" line)))

(defn- card-first-line
  "First non-empty, non-marker line of the card text, trimmed."
  [card]
  (let [txt (or (:card/text card) "")]
    (->> (str/split-lines txt)
         (map str/trim)
         (remove str/blank?)
         (remove section-marker?)
         first)))

(def ^:private tier->label
  {:principle "MANDATORY"
   :rule      "MUST"
   :ethic     "PREFERRED"})

(defn- format-card-line
  "One bullet line for a card: `- **id** (TIER) — first-line`."
  [card]
  (format "- **%s** (%s) — %s"
          (:card/id card)
          (get tier->label (:card/tier card) "PREFERRED")
          (or (card-first-line card) "")))

(defn- format-card-line-with-weight
  "Bullet line that also shows the current weight (used by identity-tree
   and consult-view)."
  [card weight]
  (format "- **%s** (weight %.1f) — %s"
          (:card/id card)
          (double (or weight 0.0))
          (or (card-first-line card) "")))

(defn- section
  "Render a group of cards under a header. Returns nil if `cards` is
   empty so callers can drop empty sections cleanly."
  [header cards line-fn]
  (when (seq cards)
    (str "## " header "\n"
         (str/join "\n" (map line-fn cards)))))

(defn identity-tree
  "Full identity-tree view for SessionStart additionalContext.

   Groups by tier (principle > rule > ethic). Within each tier, groups
   by the four knowledge categories. Cards are passed as
   `[{:card ... :weight ...}]` so the view can show current weight
   without recomputing it here.

   The optional `footer` is appended below the tree — typically the
   consult skill hint per plan §SessionStart."
  [scored-cards {:keys [footer]}]
  (let [by-tier (group-by (comp :card/tier :card) scored-cards)
        tier-blocks
        (for [tier tier-order
              :let [cards-at-tier (get by-tier tier [])]
              :when (seq cards-at-tier)]
          (let [by-cat (group-by (comp :card/category :card) cards-at-tier)
                cat-blocks
                (for [[cat label] category-label
                      :let [cards (get by-cat cat [])]
                      :when (seq cards)]
                  (str "### " label "\n"
                       (str/join "\n"
                                 (map (fn [m]
                                        (format-card-line-with-weight
                                          (:card m) (:weight m)))
                                      cards))))]
            (str "## " (get tier-header tier (name tier)) "\n"
                 (str/join "\n\n" cat-blocks))))
        body (if (seq tier-blocks)
               (str/join "\n\n" tier-blocks)
               "_No promoted identity cards yet._")]
    (if footer
      (str body "\n\n---\n\n" footer)
      body)))

(defn salient-reminder
  "Compact reminder for PreToolUse / PostToolUse refresh. Takes the
   output of `domain/salience/rank` directly (a vector of `{:card :score}`).
   Returns a short markdown string, intentionally < ~400 bytes for the
   refresh channel.

   Header is caller-supplied so PreToolUse and PostToolUse can use
   different framings (e.g. 'Before this tool call' vs 'Identity
   reminder')."
  [ranked header]
  (if (empty? ranked)
    ""
    (str header "\n"
         (str/join "\n"
                   (map (fn [{:keys [card]}]
                          (format-card-line card))
                        ranked)))))

(defn- tension-line
  [tension]
  (let [kind (:tension/kind tension)
        note (:tension/note tension)]
    (format "- **[%s]** %s"
            (name kind)
            (or note ""))))

(defn consult-view
  "Render the candidate set from `domain/consult/query` into the markdown
   that becomes the `claude -p` prompt body. The rendered string is the
   *consult prompt payload*, not the full prompt — the CLI wraps it with
   framing text ('You are the reflective voice of your own identity...').

   Shape:

     # Consult
     **situation:** <situation/text>

     ## Principle · inviolable
     - **card-id** (weight X.X) — first line
     ...

     ## Rule · default behavior
     ...

     ## Ethic · character
     ...

     ## tensions
     - **[principle-forbids]** note
     ...

     (no 'reflection' section — that's what claude -p produces)"
  [consult-result]
  (let [situation (:consult/situation consult-result)
        by-tier   (:consult/by-tier consult-result)
        tensions  (:consult/tensions consult-result)
        tier-sections
        (for [tier tier-order
              :let [cards (get by-tier tier [])]]
          (section (get tier-header tier)
                   cards
                   (fn [m] (format-card-line-with-weight
                             (:card m) (:weight m)))))
        tier-md (->> tier-sections
                     (remove nil?)
                     (str/join "\n\n"))
        tensions-md (when (seq tensions)
                      (str "## tensions\n"
                           (str/join "\n" (map tension-line tensions))))]
    (str "# Consult\n"
         "**situation:** " (or (:situation/text situation) "") "\n\n"
         (if (str/blank? tier-md)
           "_No candidate cards for this situation._"
           tier-md)
         (when tensions-md (str "\n\n" tensions-md)))))
