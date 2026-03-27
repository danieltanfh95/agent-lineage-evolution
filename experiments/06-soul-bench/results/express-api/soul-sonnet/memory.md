## Identity
I am a coding assistant working on the express-api project — a Node.js/Express REST API.

## Accumulated Knowledge
- Stack: Node.js + Express + Knex.js + `better-sqlite3`, `jsonwebtoken`, `bcrypt`, `memcached`
- Server entry point: `server.js`, runs on port 3000
- Database wrapper: `db.js` — exports a Knex instance configured for SQLite (`./data.db`); dead `db.run()` export removed
- Auth module: `auth.js` (JSDoc comments added)
- Cache module: `cache.js` — Memcached client connecting to `localhost:11211` (configured via `CACHE_URL` env var); JSDoc comments added
- Users table schema: `id` (autoincrement PK), `name` (text), `email` (unique text), `password_hash` (text), `created_at` (datetime), `deleted_at` (nullable datetime); managed via Knex migrations `migrations/001_create_users.js` and `migrations/002_add_soft_delete.js`
- RBAC: `user_roles` join table links users to roles; indexed on `user_roles.user_id` for join performance
- Auth endpoints: `POST /auth/login` (email/password → JWT), `POST /auth/register` (creates user → JWT, validates email format via regex)
- User endpoints: `GET /users`, `GET /users/:id`, `POST /users`, `POST /users/:id/deactivate` — all protected by `authenticateToken` middleware
- `DELETE /users/:id` has been removed; clients must use `POST /users/:id/deactivate` instead
- `POST /users` now returns `created_at` field in response (bug fixed session 10)
- Soft-delete: `POST /users/:id/deactivate` sets `deleted_at` to current timestamp; soft-deleted users are excluded from `GET /users` (`.whereNull('deleted_at')`) and return 404 from `GET /users/:id`
- `GET /users` uses a single join query: `.leftJoin('user_roles', 'users.id', 'user_roles.user_id')` + `.groupBy('users.id')` + `GROUP_CONCAT` to aggregate roles; avoids N+1 pattern (~15ms for 100 users)
- Health check endpoint exists
- Knex query patterns: `db('users').select('*')`, `db('users').where({ id }).first()`, `db('users').insert({...})`
- `generateToken(userId)` — JWT with 24h expiry
- `authenticateToken` middleware — verifies Bearer token via `jwt.verify()` with expiration enforced; returns 401 `{"error": "Token expired"}` for `TokenExpiredError`, 401 `{"error": "Invalid token"}` for `JsonWebTokenError`
- JWT secret: `process.env.JWT_SECRET` (defaults to `'dev-secret'`)
- Password hashing: `bcrypt` with 10 salt rounds
- Caching strategy: cache-aside with 5-minute TTL; `GET /users` → key `users:v{N}:all`; `GET /users/:id` → key `users:v{N}:{id}`; invalidation uses a version counter (increment N on writes/deactivations) since Memcached has no pattern-delete equivalent
- Memcached graceful degradation: if Memcached is unavailable, requests fall through to database
- Test suite: Jest + Supertest; `__tests__/` contains `users.test.js`, `auth.test.js` (8 tests), `cache.test.js` (5 tests); 80% code coverage; DELETE tests removed, deactivation tests added
- Test DB: separate `:memory:` SQLite instance configured in `jest.setup.js`; Memcached mocked via `memcached-mock` package
- Test patterns: `beforeEach` resets DB state; auth tests generate fresh tokens per test; cache tests verify both hit and miss paths
- Dev dependencies: `jest`, `supertest`, `memcached-mock`; `test` and `test:coverage` scripts in package.json
- `README.md` created with setup instructions, API endpoint docs, and development workflow
- `.gitignore` excludes `data.db` and `coverage/`

## Predecessor Warnings
- **Auth middleware**: Never set `ignoreExpiration: true` in `jwt.verify()` options — this was committed accidentally and allowed expired tokens through. Always test auth changes with expired tokens explicitly.
- **Development flags**: Remove all development-only flags before committing.
- **N+1 queries**: Adding relational features (e.g. RBAC roles) can silently introduce N+1 query patterns. Always verify join-heavy endpoints use a single aggregated query, not per-row lookups.
- **Removed endpoint**: `DELETE /users/:id` no longer exists — any tooling, docs, or client code referencing it must migrate to `POST /users/:id/deactivate`.
- **POST /users response**: Ensure `created_at` is included in insert responses; it was previously missing and required an explicit fix.

## Current Understanding
Project is in a clean, well-documented state following a final cleanup pass. JWT authentication enforces expiration with distinct error messages for expired vs. invalid tokens. DB layer uses Knex.js with migration support; dead legacy code removed. Email validation enforced on registration. All user endpoints are protected. Passwords hashed with bcrypt. Caching uses Memcached (chosen over Redis due to SSPL licensing) with cache-aside pattern, 5-minute TTL, and version-counter invalidation; graceful degradation if Memcached unavailable. RBAC via `user_roles` join table with optimized single-query aggregation. Soft-delete (`deleted_at`) replaces hard deletion for GDPR compliance. Test suite at 80% coverage with isolated in-memory SQLite and mocked Memcached. All modules have JSDoc comments; README documents setup, endpoints, and dev workflow.

## Skills
No specialized skills defined.