# Session 9: Remove DELETE /users/:id Endpoint

## Work Done
Removed the `DELETE /users/:id` endpoint for GDPR compliance reasons. User deletion now follows a soft-delete pattern instead.

Changes:
- Removed `app.delete('/users/:id')` route entirely from `server.js`
- Added `deleted_at` column to users table (nullable datetime) via new Knex migration `002_add_soft_delete.js`
- Added `POST /users/:id/deactivate` — sets `deleted_at` to current timestamp
- Modified `GET /users` to filter out soft-deleted users: `.whereNull('deleted_at')`
- Modified `GET /users/:id` to return 404 for soft-deleted users
- Updated cache invalidation to handle deactivation
- Updated test suite: removed DELETE tests, added deactivation tests

**IMPORTANT**: The `DELETE /users/:id` endpoint no longer exists. Any client code using it must migrate to `POST /users/:id/deactivate`. The old endpoint returns 404.

## Git Log
- feat: implement soft-delete for GDPR compliance
- migration: add deleted_at column to users
- fix: filter soft-deleted users from GET endpoints
- test: update tests for soft-delete pattern
