# Session 10: Add Plugin System for Custom Output Formats

## Work Done
Added a plugin system allowing users to define custom output formatters.

- Plugins are Python files in `~/.csv-analyzer/plugins/` or `./plugins/`
- Each plugin exports a `format_output(df, metadata)` function and a `FORMAT_NAME` string
- Plugins are discovered at startup via `importlib.import_module()`
- Built-in formats (text, json, csv) remain as defaults; plugins extend the available formats
- Example plugin for Markdown table output: `plugins/markdown.py`
- Added `--list-formats` flag to show all available formats (built-in + plugins)
- Plugin errors are caught and reported gracefully; the CLI doesn't crash if a plugin is broken

Current project state:
- Python CLI tool for CSV analysis
- Uses Polars for data processing (lazy evaluation available)
- Supports text, JSON, CSV output formats + plugins for custom formats
- TOML config file (`config.toml`) for defaults
- Row filtering with expression syntax (`--filter "col > value"`)
- Multi-file parallel processing with `--workers`
- Pytest test suite with 90% coverage
- Logging via `--log-level` (debug/info/warning/error) and `--quiet`

## Git Log
- feat: add plugin system for custom output formats
- docs: add plugin development guide
- feat: add --list-formats to show available formatters
