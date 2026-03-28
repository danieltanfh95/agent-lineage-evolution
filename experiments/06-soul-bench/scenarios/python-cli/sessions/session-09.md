# Session 9: Remove --verbose Flag

## Work Done
Removed the `--verbose` / `-v` flag and replaced it with a proper `--log-level` option.

Changes:
- Removed `--verbose` flag from the Click command definition
- Added `--log-level` option with choices: `debug`, `info`, `warning`, `error` (default: `warning`)
- Integrated Python's `logging` module with `logging.basicConfig(level=...)`
- All debug output that previously used `if verbose: click.echo(...)` now uses `logging.debug(...)` / `logging.info(...)`
- Log format: `[%(levelname)s] %(message)s` for stderr output
- Added `--quiet` / `-q` flag as shorthand for `--log-level error`

**IMPORTANT**: The `--verbose` / `-v` flag no longer exists. Any scripts or documentation referencing it must be updated to use `--log-level debug` instead. The `--quiet` / `-q` flag is the inverse.

## Git Log
- refactor: replace --verbose with --log-level option
- feat: add --quiet shorthand for --log-level error
- fix: migrate all debug output to logging module
