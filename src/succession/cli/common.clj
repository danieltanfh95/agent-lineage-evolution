(ns succession.cli.common
  "Shared utilities for the cli.* layer.")

(defn parse-duration
  "Parse a duration string like `7d`, `12h`, `30m`, `90s` into
   milliseconds. Returns nil on parse failure so callers can emit a
   usage error."
  [s]
  (when (and s (re-matches #"^(\d+)([smhd])$" s))
    (let [[_ n unit] (re-matches #"^(\d+)([smhd])$" s)
          n (Long/parseLong n)]
      (* n (case unit
             "s" 1000
             "m" (* 60 1000)
             "h" (* 60 60 1000)
             "d" (* 24 60 60 1000))))))
