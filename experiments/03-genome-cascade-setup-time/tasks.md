# Standardized Task — Genome Cascade Experiment

## Task Prompt

> Set up a new TypeScript API project from scratch. The project should include:
>
> 1. An Express server running on port 3000
> 2. Three CRUD endpoints for a "tasks" resource (GET /api/tasks, POST /api/tasks, DELETE /api/tasks/:id)
> 3. Input validation on all endpoints that accept data
> 4. SQLite database connection using better-sqlite3
> 5. A health check endpoint at GET /health
> 6. Proper error handling (no unhandled promise rejections, consistent error response format)
>
> Use TypeScript in strict mode. Initialize the project with package.json and tsconfig.json.

## Quality Rubric (0-10)

| Points | Criteria |
|--------|----------|
| 1 | package.json exists with correct dependencies |
| 1 | tsconfig.json exists with strict mode enabled |
| 1 | Express server starts and listens on port 3000 |
| 1 | GET /health returns status JSON |
| 1 | GET /api/tasks returns task list from SQLite |
| 1 | POST /api/tasks creates a task with validation |
| 1 | DELETE /api/tasks/:id deletes a task |
| 1 | Input validation present on POST (rejects invalid input) |
| 1 | Consistent error response format (status + message) |
| 1 | No TypeScript strict mode violations |

**Total: 10 points**

## Invariant Compliance (0-5)

| Points | Invariant |
|--------|-----------|
| 1 | All endpoints validate input |
| 1 | TypeScript strict mode enabled |
| 1 | No circular dependencies |
| 1 | Error responses include status code and message |
| 1 | Agent read files before editing them |

## Recording Template

```json
{
  "trial": <number>,
  "condition": "<rich|minimal>",
  "turns_to_completion": <count>,
  "invariant_compliance": <0-5>,
  "quality_score": <0-10>,
  "notes": "<any observations>"
}
```
