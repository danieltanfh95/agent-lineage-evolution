# Session 4: API Endpoints and Smooth Session

## Work Done
Added complete CRUD endpoints for tasks with proper authentication. All endpoints require a valid JWT token except `POST /auth/login` and `POST /auth/register`.

The assistant used single quotes consistently throughout. Used SQLAlchemy for all queries. Used logger.info() and logger.error() for all logging. The user did not need to make any corrections this session.

Added pagination to `GET /tasks` with `page` and `per_page` query parameters (default 20 per page). Added filtering by status.

Also added input validation using `marshmallow` schemas for all request bodies.

## Git Log
- feat: add authenticated task CRUD endpoints
- feat: add pagination and status filtering
- feat: add marshmallow input validation
