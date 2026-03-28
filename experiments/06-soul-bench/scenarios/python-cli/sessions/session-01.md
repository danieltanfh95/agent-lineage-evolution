# Session 1: Initial CLI Setup

## Work Done
Created a Python CLI tool for CSV analysis using Click and Pandas. Two files:
- `cli.py` — Click command with `analyze` function, accepts CSV path and `--format` option (text/json)
- `pyproject.toml` — Project config with click and pandas dependencies

The `analyze` command reads a CSV with `pd.read_csv()`, prints row count, column names, and `df.describe()` summary statistics. JSON output uses `df.describe().to_json()`.

Python 3.10+ required. Uses `pyproject.toml` with `[project.scripts]` for entry point.

## Git Log
- feat: initial CSV analyzer CLI with click and pandas
