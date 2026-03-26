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
