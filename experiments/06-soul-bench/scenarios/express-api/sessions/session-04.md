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
