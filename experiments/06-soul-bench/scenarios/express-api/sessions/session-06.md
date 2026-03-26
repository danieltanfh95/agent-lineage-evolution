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
