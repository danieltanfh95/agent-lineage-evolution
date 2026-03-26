# Backend API Genome — Archetype Knowledge

## REST API Conventions
- Use plural nouns for resource endpoints (e.g., /api/tasks, not /api/task)
- HTTP methods: GET (read), POST (create), PUT (update), DELETE (remove)
- Return appropriate status codes: 200 (OK), 201 (Created), 400 (Bad Request), 404 (Not Found), 500 (Internal Error)
- Always return JSON responses with consistent shape

## Error Response Format
- All errors should return: `{ "error": "<message>", "status": <code> }`
- Never expose stack traces in production error responses
- Log full error details server-side, return sanitized messages to clients

## Input Validation
- Validate all incoming request bodies before processing
- Check required fields exist and have correct types
- Validate string lengths, number ranges, and enum values
- Return 400 with descriptive error message on validation failure
- Never trust client input — validate at the boundary

## Database Patterns
- Use parameterized queries — never string-interpolate user input into SQL
- Keep database logic in a separate module from route handlers
- Use connection pools for PostgreSQL; single connection for SQLite
- Wrap multi-step operations in transactions

## Express.js Patterns
- Use express.json() middleware for parsing JSON bodies
- Organize routes in separate files/routers for each resource
- Add a health check endpoint at GET /health returning `{ "status": "ok" }`
- Use async error handling middleware to catch unhandled promise rejections
- Set appropriate CORS headers if the API will be consumed by browsers

## Security
- Never log sensitive data (passwords, tokens, personal information)
- Use environment variables for configuration (database URLs, API keys)
- Add rate limiting for public-facing endpoints
- Validate Content-Type header on POST/PUT requests
