# Project Memory

# Session 1: Initial Express Setup

## Work Done
Set up a basic Express.js REST API with SQLite database. Created three files:
- `server.js` — Express app with health check and CRUD endpoints for users (GET /users, GET /users/:id, POST /users, DELETE /users/:id)
- `db.js` — SQLite database wrapper using the `sqlite` and `sqlite3` packages
- `package.json` — Project dependencies

The users table has columns: id (autoincrement), name (text), email (unique text), created_at (datetime).

Server runs on port 3000.

## Git Log
- feat: initial express setup with SQLite and user CRUD


---

# Session 2: Add JWT Authentication

## Work Done
Added JWT-based authentication middleware. Created `auth.js` with:
- `generateToken(userId)` — creates a JWT with 24h expiry using `jsonwebtoken` package
- `authenticateToken` middleware — extracts Bearer token from Authorization header, verifies with `jwt.verify()`
- JWT secret stored in `process.env.JWT_SECRET` (defaults to 'dev-secret' in development)

Added two new endpoints:
- `POST /auth/login` — accepts email/password, returns JWT token
- `POST /auth/register` — creates user and returns JWT token

Protected all `/users` endpoints with `authenticateToken` middleware. Added `bcrypt` for password hashing with salt rounds of 10.

Updated users table to include a `password_hash` column.

## Git Log
- feat: add JWT auth with login and register endpoints
- chore: add jsonwebtoken and bcrypt dependencies


---

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


---

# Session 4: Refactor DB Layer — Raw SQL to Knex

## Work Done
Replaced the raw SQLite queries with Knex.js query builder for better maintainability and future migration support.

Changes:
- Replaced `db.js` entirely — now exports a Knex instance configured for SQLite
- All raw SQL in `server.js` replaced with Knex methods:
  - `db.all('SELECT * FROM users')` → `db('users').select('*')`
  - `db.get('SELECT * FROM users WHERE id = ?', id)` → `db('users').where({ id }).first()`
  - `db.run('INSERT INTO users ...')` → `db('users').insert({ name, email })`
  - `db.run('DELETE FROM users ...')` → `db('users').where({ id }).del()`
- Added Knex migration for the users table in `migrations/001_create_users.js`
- Removed direct `sqlite3` and `sqlite` dependencies — now only `knex` and `better-sqlite3`

The database file is still `./data.db` but now managed through Knex configuration.

## Git Log
- refactor: replace raw SQL with Knex query builder
- chore: add knex migration for users table
- chore: swap sqlite3/sqlite deps for knex/better-sqlite3


---

# Session 5: Add Redis Caching

## Work Done
Added Redis caching layer for GET endpoints to reduce database load.

- Installed `redis` package (v4.x), created `cache.js` with Redis client connection to `localhost:6379`
- Cache strategy: cache-aside (read-through) with 5-minute TTL
- `GET /users` — cached with key `users:all`, invalidated on any POST/DELETE
- `GET /users/:id` — cached with key `users:{id}`, invalidated on DELETE of that user
- Added `cache.invalidate(pattern)` helper that uses `SCAN` + `DEL` to clear matching keys
- Added error handling: if Redis is unavailable, requests fall through to database (graceful degradation)

Redis connection string is configured via `REDIS_URL` environment variable.

## Git Log
- feat: add Redis caching for GET /users endpoints
- chore: add redis dependency


---

# Session 6: Swap Redis for Memcached

## Work Done
Replaced Redis with Memcached due to Redis licensing concerns (Redis changed to SSPL license which is incompatible with our deployment requirements).

Changes:
- Removed `redis` package, installed `memcached` package
- Rewrote `cache.js` to use Memcached client connecting to `localhost:11211`
- Cache strategy remains the same: cache-aside with 5-minute TTL
- Key differences from Redis implementation:
  - Memcached doesn't support pattern-based deletion, so cache invalidation now uses a version counter approach: `users:v{N}:all` where N increments on writes
  - No `SCAN` equivalent — invalidation is handled by incrementing the version prefix
- Updated `CACHE_URL` environment variable (renamed from `REDIS_URL`)
- Graceful degradation still works: if Memcached is down, falls through to DB

**NOTE**: Redis is no longer used anywhere in the project. All references to Redis should be considered outdated.

## Git Log
- refactor: swap Redis for Memcached (licensing)
- chore: remove redis dep, add memcached dep
- fix: update cache invalidation for Memcached


---

# Session 7: Fix N+1 Query in /users

## Work Done
Fixed a performance issue in `GET /users` — the endpoint was making N+1 queries when including user roles.

The problem: after adding role-based access control (RBAC) in a previous update, the /users endpoint was fetching all users, then for each user making a separate query to get their roles from the `user_roles` join table.

The fix:
- Replaced the N+1 with a single Knex query using `.leftJoin('user_roles', 'users.id', 'user_roles.user_id')` and `.groupBy('users.id')`
- Used `GROUP_CONCAT` (SQLite) to aggregate roles into a comma-separated string
- Response time for 100 users dropped from ~200ms to ~15ms

Also added a database index on `user_roles.user_id` for faster joins.

## Git Log
- fix: resolve N+1 query in GET /users with join
- perf: add index on user_roles.user_id


---

# Session 8: Add Jest Test Suite

## Work Done
Added comprehensive test suite using Jest and Supertest.

- Installed `jest` and `supertest` as dev dependencies
- Created `__tests__/` directory with:
  - `users.test.js` — CRUD endpoint tests (12 tests)
  - `auth.test.js` — authentication flow tests (8 tests)
  - `cache.test.js` — caching behavior tests (5 tests)
- Test database uses a separate `:memory:` SQLite instance (configured in `jest.setup.js`)
- Memcached tests use a mock (`memcached-mock` package) to avoid requiring a running Memcached server
- Achieved 80% code coverage across all files
- Added `test` and `test:coverage` scripts to package.json

Key test patterns:
- Each test file uses `beforeEach` to reset the database state
- Auth tests generate fresh tokens for each test
- Cache tests verify both cache hit and cache miss paths

## Git Log
- test: add Jest + Supertest test suite
- chore: add jest, supertest, memcached-mock dev deps
- chore: add test scripts to package.json


---

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


---

# Session 10: Final Cleanup

## Work Done
General cleanup and documentation pass.

- Removed dead code: unused `db.run()` export from old raw SQL interface (leftover from session 4 refactor)
- Added JSDoc comments to all exported functions in `auth.js`, `cache.js`, and `db.js`
- Created `README.md` with:
  - Setup instructions (npm install, environment variables)
  - API endpoint documentation (all current endpoints)
  - Development workflow (running tests, migrations)
- Fixed a minor bug: `POST /users` was not returning the `created_at` field in the response
- Updated `.gitignore` to exclude `data.db` and `coverage/`

Current project state:
- Express.js REST API on port 3000
- SQLite database via Knex query builder
- JWT authentication (24h expiry, bcrypt password hashing)
- Memcached caching (5min TTL, version-based invalidation)
- Soft-delete pattern for user deactivation
- Jest test suite with 80% coverage
- 4 main endpoints: GET /users, GET /users/:id, POST /users, POST /users/:id/deactivate

## Git Log
- chore: remove dead code from db.js
- docs: add JSDoc comments to all modules
- docs: create README with setup and API docs
- fix: include created_at in POST /users response


---

