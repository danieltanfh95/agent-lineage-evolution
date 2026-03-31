# Session 5: Error Handling and Process Correction

## Work Done
Added global error handling and custom exception classes. Created `exceptions.py` with `APIError`, `NotFoundError`, `ValidationError`, and `AuthError` classes.

The assistant started writing code directly without reading the existing error handling patterns first. The user corrected:

> "Always read the existing code before writing new code. You just duplicated a validation pattern that already exists in the marshmallow schemas."

Removed the duplicate validation and integrated with existing marshmallow error handling instead.

The user also corrected the assistant's approach to documentation:

> "Don't add docstrings to functions I didn't ask you to document. Only add comments where the logic isn't self-evident."

Removed unnecessary docstrings from simple getter/setter methods.

Also added rate limiting using `flask-limiter` — 100 requests per minute per IP for most endpoints, 10 per minute for auth endpoints.

## Git Log
- feat: add custom exception classes and global error handler
- fix: remove duplicate validation, integrate with marshmallow
- feat: add rate limiting
- style: remove unnecessary docstrings
