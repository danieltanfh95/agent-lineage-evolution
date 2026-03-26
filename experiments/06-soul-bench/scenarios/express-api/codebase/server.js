const express = require('express');
const db = require('./db');

const app = express();
app.use(express.json());

// Health check
app.get('/health', (req, res) => res.json({ status: 'ok' }));

// CRUD: Users
app.get('/users', async (req, res) => {
  const users = await db.all('SELECT * FROM users');
  res.json(users);
});

app.get('/users/:id', async (req, res) => {
  const user = await db.get('SELECT * FROM users WHERE id = ?', req.params.id);
  if (!user) return res.status(404).json({ error: 'Not found' });
  res.json(user);
});

app.post('/users', async (req, res) => {
  const { name, email } = req.body;
  const result = await db.run('INSERT INTO users (name, email) VALUES (?, ?)', [name, email]);
  res.status(201).json({ id: result.lastID, name, email });
});

app.delete('/users/:id', async (req, res) => {
  await db.run('DELETE FROM users WHERE id = ?', req.params.id);
  res.status(204).send();
});

app.listen(3000, () => console.log('Server running on port 3000'));
