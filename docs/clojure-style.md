# Clojure style guide

Rules derived from violations found in this codebase. Not exhaustive — only covers patterns where we had a real defect.

---

## 1. No mutable accumulation for pure aggregation

Don't use `atom` + `swap!` to build a list when the function has no concurrent callers.

```clojure
;; bad
(let [problems (atom [])]
  (when-not (number? x) (swap! problems conj (problem ...)))
  (seq @problems))

;; good
(seq
  (concat
    (keep identity
      [(when-not (number? x) (problem ...))
       ...])
    (for [item coll :when (pred? item)]
      (problem ...))))
```

Use `keep identity` + a vector of `when`/`when-not` expressions for flat checks.
Use `for` + `:when` for filtered sequences over collections.
Reserve `atom` for genuinely concurrent or stateful code.

---

## 2. Always alias namespace requires

Never call `(clojure.set/intersection ...)` or `(clojure.string/includes? ...)` inline.
Every namespace used in the body must appear in `ns :require` with an alias.

```clojure
;; bad — clojure.set not in :require
(clojure.set/intersection a b)

;; good
(:require [clojure.set :as set])
...
(set/intersection a b)
```

---

## 3. Extract duplicated private functions to a layer-scoped common/util namespace

When a `defn-` appears with the same logic in 2+ namespaces within the same layer,
extract it to the appropriate shared namespace rather than duplicating it:

- `cli/*` layer → `succession.cli.common` (`src/succession/cli/common.clj`)
- `store/*` layer → `succession.store.util` (`src/succession/store/util.clj`)
- `hook/*` layer → `succession.hook.common` (already exists)
- `domain/*` layer → make the private fn public and require it from the other ns

Before writing a new utility fn, check whether it already exists in one of these
shared namespaces. The test: if deleting the private fn would require adding a
require to another file, the fn belongs in a shared namespace.

---

## 4. Use named `fn` instead of `#(hash-map ...)`

When the anonymous fn body needs a type hint or is more than a single expression,
use `(fn [x] ...)` with a map literal instead of `#(hash-map ...)`.

```clojure
;; bad — repeats the type hint, uses hash-map fn
(map #(hash-map :id (.getName ^java.io.File %) :mtime (.lastModified ^java.io.File %)))

;; good
(map (fn [^java.io.File f]
       {:id   (.getName f)
        :mtime (.lastModified f)}))
```

---

## 5. Use closed sets from the `config` namespace

Don't inline `#{:principle :rule :ethic}` or other authoritative sets in logic.
Use the canonical vars: `config/valid-tiers`, `config/valid-categories`,
`config/valid-observation-kinds`.

```clojure
;; bad
(contains? #{:principle :rule :ethic} tier)

;; good
(contains? config/valid-tiers tier)
```
