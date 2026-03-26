const sqlite3 = require('sqlite3');
const { open } = require('sqlite');

let db;

async function getDb() {
  if (!db) {
    db = await open({ filename: './data.db', driver: sqlite3.Database });
    await db.exec(`
      CREATE TABLE IF NOT EXISTS users (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        name TEXT NOT NULL,
        email TEXT UNIQUE NOT NULL,
        created_at DATETIME DEFAULT CURRENT_TIMESTAMP
      )
    `);
  }
  return db;
}

module.exports = {
  all: async (sql, params) => (await getDb()).all(sql, params),
  get: async (sql, params) => (await getDb()).get(sql, params),
  run: async (sql, params) => (await getDb()).run(sql, params),
};
