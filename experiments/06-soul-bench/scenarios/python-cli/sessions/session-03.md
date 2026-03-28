# Session 3: Fix Empty CSV Crash

## Work Done
Fixed a crash when analyzing empty CSV files. `pd.read_csv()` on an empty file returns an empty DataFrame, but `df.describe()` on a zero-row DataFrame produces NaN values that broke the JSON formatter.

The fix:
- Added early check: if `len(df) == 0`, output `{"metadata": {"rows": 0, "columns": [...]}, "statistics": null}` for JSON and a "No data rows found" message for text format
- Added validation that the file has at least a header row (not completely empty / 0 bytes)
- Added `--strict` flag that exits with error code 1 on empty files instead of producing empty output

**LESSON LEARNED**: Always validate input data before passing to pandas. Empty DataFrames cause subtle issues in aggregation functions. The `--strict` flag pattern is useful for CI/CD pipelines where empty input should fail loudly.

## Git Log
- fix: handle empty CSV files gracefully
- feat: add --strict flag for CI/CD use
