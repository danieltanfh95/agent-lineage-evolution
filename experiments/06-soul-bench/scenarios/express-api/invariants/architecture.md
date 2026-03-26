# Architecture Invariants

- All endpoints must validate input before processing
- Database access must go through the Knex query builder, never raw SQL
- Cache failures must not break the request — graceful degradation required
- Authentication middleware must be applied to all non-public endpoints
