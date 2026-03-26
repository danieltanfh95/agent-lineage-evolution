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
