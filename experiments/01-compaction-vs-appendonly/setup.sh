#!/usr/bin/env bash
# Experiment 01 — Setup: Creates a test repository with known initial codebase
#
# Usage: ./setup.sh [target-dir]
# Default target: /tmp/soul-experiment-01
#
# This creates a small TypeScript project with 5 source files,
# documented APIs, and dependencies. The initial state serves as
# the baseline for the 10-session change sequence.

set -euo pipefail

TARGET_DIR="${1:-/tmp/soul-experiment-01}"

echo "Creating test repository at ${TARGET_DIR}..."
rm -rf "${TARGET_DIR}"
mkdir -p "${TARGET_DIR}/src"
cd "${TARGET_DIR}"
git init

# --- package.json ---
cat > package.json << 'EOF'
{
  "name": "soul-experiment-01",
  "version": "1.0.0",
  "dependencies": {
    "express": "^4.18.0",
    "lodash": "^4.17.0",
    "pg": "^8.11.0"
  }
}
EOF

# --- src/server.ts ---
cat > src/server.ts << 'EOF'
import express from 'express';
import { getUsers, createUser } from './users';
import { healthCheck } from './health';

const app = express();
app.use(express.json());

app.get('/health', healthCheck);
app.get('/api/users', getUsers);
app.post('/api/users', createUser);

app.listen(3000, () => console.log('Server running on port 3000'));
EOF

# --- src/users.ts ---
cat > src/users.ts << 'EOF'
import { query } from './db';
import _ from 'lodash';

export async function getUsers(req: any, res: any) {
  const users = await query('SELECT * FROM users');
  res.json(_.sortBy(users, 'name'));
}

export async function createUser(req: any, res: any) {
  const { name, email } = req.body;
  const user = await query('INSERT INTO users (name, email) VALUES ($1, $2) RETURNING *', [name, email]);
  res.status(201).json(user);
}
EOF

# --- src/db.ts ---
cat > src/db.ts << 'EOF'
import { Pool } from 'pg';

const pool = new Pool({
  connectionString: process.env.DATABASE_URL || 'postgresql://localhost:5432/app'
});

export async function query(text: string, params?: any[]) {
  const result = await pool.query(text, params);
  return result.rows;
}
EOF

# --- src/health.ts ---
cat > src/health.ts << 'EOF'
export function healthCheck(req: any, res: any) {
  res.json({ status: 'ok', version: '1.0.0' });
}
EOF

# --- src/utils.ts ---
cat > src/utils.ts << 'EOF'
import _ from 'lodash';

export function formatDate(date: Date): string {
  return date.toISOString().split('T')[0];
}

export function sanitizeInput(input: string): string {
  return _.escape(input);
}
EOF

git add -A && git commit -m "Initial codebase for experiment 01"

echo ""
echo "Test repository created at ${TARGET_DIR}"
echo ""
echo "Initial state:"
echo "  - 5 source files (server.ts, users.ts, db.ts, health.ts, utils.ts)"
echo "  - Dependencies: express, lodash, pg"
echo "  - API: GET /health, GET /api/users, POST /api/users"
echo "  - Database: PostgreSQL via pg Pool"
echo ""
echo "Predetermined changes (to be applied across 10 sessions):"
echo "  Session 1: Add PUT /api/users/:id endpoint"
echo "  Session 2: Fix bug — createUser missing input validation"
echo "  Session 3: Replace lodash with native JS methods"
echo "  Session 4: Add DELETE /api/users/:id endpoint"
echo "  Session 5: Change database from pg to better-sqlite3"
echo "  Session 6: Fix bug — healthCheck should include uptime"
echo "  Session 7: Add GET /api/users/:id endpoint"
echo "  Session 8: Refactor — split routes into separate router files"
echo "  Session 9: Replace express with Hono framework"
echo "  Session 10: Add authentication middleware (JWT)"
echo ""
echo "GROUND TRUTH (final state after all 10 sessions):"
echo "  - Framework: Hono (not express)"
echo "  - Database: better-sqlite3 (not pg)"
echo "  - No lodash dependency"
echo "  - Routes: health, users CRUD (GET all, GET one, POST, PUT, DELETE)"
echo "  - Auth: JWT middleware"
echo "  - healthCheck includes uptime"
echo "  - createUser has input validation"
echo "  - Routes split into separate router files"
