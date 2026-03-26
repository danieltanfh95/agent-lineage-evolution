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
