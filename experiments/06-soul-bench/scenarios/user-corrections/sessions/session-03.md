# Session 3: Logging Correction and Auth Setup

## Work Done
Added JWT authentication using `flask-jwt-extended`. Tokens expire after 1 hour.

The assistant used `print()` statements for debugging and logging throughout the auth module. The user corrected:

> "Stop using print() for logging. Use Python's logging module — logger.info(), logger.error(), etc. Set up a proper logger in each module."

Created a `log_config.py` with a configured logger using `logging.getLogger(__name__)` pattern. Replaced all print() calls with appropriate log levels.

Also added password hashing with `werkzeug.security` (generate_password_hash / check_password_hash).

## Git Log
- feat: add JWT authentication
- refactor: replace print() with logging module
- feat: add password hashing
