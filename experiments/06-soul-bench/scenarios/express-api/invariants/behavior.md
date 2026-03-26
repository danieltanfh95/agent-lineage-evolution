# Behavior Invariants

- Always read a file before editing it
- Never delete endpoints without implementing a migration path
- Always update tests when changing endpoint behavior
- Never commit development-only flags (like ignoreExpiration)
