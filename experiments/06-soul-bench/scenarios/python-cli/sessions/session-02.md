# Session 2: Add JSON Output Format

## Work Done
Enhanced the `--format` option to support structured JSON output with metadata.

- Added `--format json` which now outputs a proper JSON object with `{"metadata": {"rows": N, "columns": [...]}, "statistics": {...}}`
- Added `--format csv` for outputting summary stats as CSV (useful for piping)
- Refactored output formatting into a separate `formatters.py` module with `format_text()`, `format_json()`, and `format_csv()` functions
- Added `--output` / `-o` option to write to a file instead of stdout

## Git Log
- feat: add json and csv output formats
- refactor: extract formatters into separate module
- feat: add --output option for file output
