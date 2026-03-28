# Architecture Invariants

- All CLI options must have help text and sensible defaults
- Data processing must go through Polars, never raw file I/O
- Plugin failures must not crash the main CLI — graceful degradation required
- Config file changes must include a migration path for existing users
