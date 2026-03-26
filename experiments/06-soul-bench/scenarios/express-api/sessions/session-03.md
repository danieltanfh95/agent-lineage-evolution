# Session 3: Fix Auth Bug — Token Expiry

## Work Done
Fixed a critical bug in the authentication middleware: `jwt.verify()` was not checking token expiration. The `ignoreExpiration` option was accidentally set to `true` during development and never removed. This meant expired tokens were still accepted.

The fix:
- Removed `ignoreExpiration: true` from the verify options
- Added explicit error handling for `TokenExpiredError` — returns 401 with `{"error": "Token expired"}`
- Added explicit error handling for `JsonWebTokenError` — returns 401 with `{"error": "Invalid token"}`

Also discovered that the `/auth/register` endpoint was not validating email format. Added a regex check: `/^[^\s@]+@[^\s@]+\.[^\s@]+$/`.

**LESSON LEARNED**: Always remove development-only flags before committing. The `ignoreExpiration` flag should never have been committed. Future auth changes should be tested with expired tokens explicitly.

## Git Log
- fix: remove ignoreExpiration flag from JWT verify
- fix: add email validation to register endpoint
