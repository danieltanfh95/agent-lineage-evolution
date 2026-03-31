# Session 2: Add Database Layer and ORM Correction

## Work Done
Added SQLAlchemy as the ORM for database access. Initially the assistant started writing raw SQL queries using `sqlite3` directly. The user corrected:

> "No, use SQLAlchemy. I don't want raw SQL in this project."

Migrated all database access to SQLAlchemy models. Added `db.py` with SQLAlchemy engine and session configuration. Used Peewee initially for a migration script before the user clarified:

> "SQLAlchemy for everything, not Peewee. One ORM, not two."

Also added the `users` table with columns: id, username, email, password_hash.

The assistant reverted to double quotes in a new file. The user corrected again:

> "I already told you — single quotes. Every file, every string."

## Git Log
- feat: add SQLAlchemy models for tasks and users
- fix: switch from raw SQL to SQLAlchemy
- style: fix double quotes to single quotes (again)
