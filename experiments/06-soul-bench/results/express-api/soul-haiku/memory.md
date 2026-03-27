## Identity
I am a coding assistant working on the express-api project — a Node.js/Express REST API with SQLite persistence, JWT authentication, and Memcached caching.

## Accumulated Knowledge
- Express.js REST API architecture: health checks, CRUD endpoints, JWT-protected routes
- SQLite schema design: id (autoincrement), name, email (unique), created_at (datetime), deleted_at (nullable datetime for soft-delete), password_hash for users table; user_roles join table for RBAC
- Project structure: server.js (Express app), db.js (Knex configuration), auth.js (JWT middleware), cache.js (Memcached client), package.json (dependencies), `__tests__/` directory for Jest tests, README.md with setup/API documentation
- Database layer: Knex.js query builder for SQLite with better-sqlite3 driver; migrations in `migrations/` directory
- Knex query patterns: `db('users').select('*')`, `db('users').where({ id }).first()`, `db('users').insert({...})`, `.leftJoin()` for multi-table queries with `.groupBy()` and `GROUP_CONCAT()` for aggregation
- **Soft-delete pattern**: DELETE endpoint removed for GDPR compliance. Users deactivated via `POST /users/:id/deactivate` which sets `deleted_at` to current timestamp. All read endpoints filter soft-deleted users with `.whereNull('deleted_at')`.
- Server runs on port 3000
- Dependencies: express, knex, better-sqlite3, jsonwebtoken, bcrypt, memcached; dev dependencies: jest, supertest, memcached-mock
- Authentication: JWT tokens with 24h expiry, Bearer token extraction from Authorization header, bcrypt password hashing (salt rounds: 10)
- JWT secret stored in process.env.JWT_SECRET (defaults to 'dev-secret' in development)
- All /users endpoints protected with authenticateToken middleware
- Auth endpoints: POST /auth/login (email/password → token), POST /auth/register (create user → token)
- **JWT Verification**: Must NOT use `ignoreExpiration: true` in production code. Always verify token expiry explicitly. Handle `TokenExpiredError` and `JsonWebTokenError` with appropriate 401 responses.
- **Email validation**: POST /auth/register validates email format with regex `/^[^\s@]+@[^\s@]+\.[^\s@]+$/`
- **Memcached Caching**: Cache-aside pattern with 5-minute TTL; `GET /users` cached as `users:v{N}:all`, `GET /users/:id` cached as `users:v{N}:{id}` where N is version counter; cache invalidation via version counter increment on POST/DELETE (no pattern-based deletion); graceful fallback to database if Memcached unavailable; configured via `CACHE_URL` environment variable; connects to `localhost:11211` by default
- **N+1 Query Prevention**: Use `.leftJoin()` with `.groupBy()` and `GROUP_CONCAT()` to fetch related data in single query; add database indexes on join table foreign keys (e.g., `user_roles.user_id`) for performance
- **Testing**: Jest test suite with Supertest for HTTP endpoint testing; separate `:memory:` SQLite instance per test via `jest.setup.js`; Memcached tests use memcached-mock to avoid external dependency; `beforeEach` resets database state; auth tests generate fresh tokens per test; test patterns cover cache hit/miss paths; 80% code coverage baseline achieved; npm scripts: `test` and `test:coverage`
- **Code Quality**: All exported functions in auth.js, cache.js, and db.js documented with JSDoc comments; dead code (unused exports from refactored raw SQL interface) removed; all response payloads include expected fields (e.g., `created_at` in POST /users response)
- **Documentation**: README.md covers setup instructions, environment variables, API endpoint documentation, and development workflow

## Predecessor Warnings
- Development-only flags (e.g., `ignoreExpiration: true`) must be removed before commit — they bypass critical security checks
- Auth middleware changes require explicit testing with expired/invalid tokens to prevent security regressions
- Raw SQL queries have been fully replaced by Knex — maintain consistency with Knex methods going forward to avoid regression to raw SQL
- Memcached unavailability must not cascade as errors — always implement graceful degradation to database fallback
- Redis is no longer part of this project; all caching now uses Memcached with version-counter-based invalidation instead of pattern-based deletion
- N+1 queries degrade performance significantly — always use joins with aggregation for related data rather than looping and fetching per-record
- Test database must use separate `:memory:` instance to prevent test pollution — never share database state across test files
- **Hard DELETE endpoint removed** — `DELETE /users/:id` no longer exists. Clients must migrate to `POST /users/:id/deactivate`. Attempting hard deletion will return 404.
- Dead code from refactoring (e.g., unused db.run() exports) must be removed before final commit — keep codebase clean to avoid confusion

## Current Understanding
express-api is a fully authenticated Node.js/Express REST API with SQLite backend, Memcached caching layer, and role-based access control (RBAC). User deletion follows GDPR-compliant soft-delete pattern via deactivation endpoint. Knex.js abstracts database operations with optimized join patterns to prevent N+1 queries. JWT authentication properly validates token expiry. Email validation enforced on registration. Cache-aside strategy with version counters reduces database load on read-heavy endpoints. Database indexes on join table foreign keys ensure fast retrieval of related data. Comprehensive Jest test suite with 80% coverage validates endpoints, auth flows, caching behavior, and soft-delete logic without requiring external service dependencies. Code is documented with JSDoc comments and README provides clear setup and API guidance. Project is production-ready with cleanup tasks complete.

## Skills
No specialized skills defined.