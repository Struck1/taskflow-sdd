# Conventions — TaskFlow Pro

## Architecture: Modular Monolith + Hexagonal

One Spring Boot application, split into modules by business capability:
`auth`, `workspace`, `task`, `automation`, `notification`.

Every module has the same structure:

```
com.taskflow.<module>/
├── domain/                  # Pure Java: aggregates, value objects, domain events
├── application/
│   ├── port/in/             # Use case interfaces (what the module offers)
│   ├── port/out/            # Interfaces the module needs (persistence, email...)
│   └── service/             # Use case implementations
└── adapter/
    ├── in/web/              # REST controllers
    └── out/persistence/     # JPA entities, Spring Data repositories
```

### Rules
- `domain/` has no Spring, JPA or Jackson imports.
- A module talks to another module only through its `port/in`
  interfaces or domain events — never its repositories or entities.
- Cross-module foreign keys are allowed in the database;
  cross-module JPA relationships are not.

## Domain Model (DDD)
| Module | Aggregate root | Contains |
|--------|----------------|----------|
| auth | `User` | credentials, sessions |
| workspace | `Workspace` | memberships, roles, invites |
| task | `Task` | subtasks, tags |
| automation | `AutomationRule` | trigger, action |
| notification | `Notification` | — |

### Rules
- Aggregates reference each other by ID only (`WorkspaceId`, `UserId`),
  never by object reference.
- One transaction changes one aggregate. Other modules react to
  domain events (e.g. `TaskCompleted`) after the transaction commits.
- Invariants live inside the aggregate root, not in services.
  Example: "a workspace always has at least one admin" is enforced
  by `Workspace`, so no caller can bypass it.
- Value objects and IDs are Java `record`s and validate themselves
  in the constructor (e.g. `TaskTitle` rejects blank input).

## REST API

### URLs
- Prefix: `/api/v1`
- Plural nouns, kebab-case: `/api/v1/workspaces`, `/api/v1/automation-rules`
- Workspace-owned resources are nested under the workspace:
  `/api/v1/workspaces/{workspaceId}/tasks/{taskId}`
- Controllers live in `adapter/in/web/` and only call `port/in` use cases.

### Status codes
| Situation | Status |
|-----------|--------|
| Created | `201` + `Location` header |
| Read / updated | `200` |
| Deleted | `204` |
| Validation error | `400` |
| Not authenticated | `401` |
| Authenticated but not allowed | `403` |
| Not found, or belongs to another workspace | `404` |
| Business rule conflict (e.g. removing the last admin) | `409` |

### Error format
Errors use RFC 9457 Problem Details (Spring's `ProblemDetail`):

```json
{
  "type": "https://taskflow.pro/errors/last-admin",
  "title": "Cannot remove the last admin",
  "status": 409,
  "detail": "Workspace 7f3a... must keep at least one admin."
}
```

- Error messages are in English.
- Never expose stack traces, SQL or internal class names.


## Testing

| Layer | Test type | Tools |
|-------|-----------|-------|
| `domain/` | Unit test, no Spring context | JUnit 5, AssertJ |
| `application/service/` | Unit test, `port/out` mocked | JUnit 5, Mockito |
| `adapter/out/persistence/` | Integration test, real database | `@DataJpaTest` + Testcontainers |
| `adapter/in/web/` | API test, full request/response | `@SpringBootTest` + Testcontainers |

- Every task ships with its tests; a task without tests is not done.
- Test names describe behavior: `rejectsBlankTitle()`,
  `returns404WhenTaskBelongsToAnotherWorkspace()`.
- Every workspace-scoped endpoint has an isolation test:
  user from workspace A requests data from workspace B → `404`.

## Naming

| Element | Convention | Example |
|---------|------------|---------|
| Use case port | `<Verb><Noun>UseCase` | `CreateTaskUseCase` |
| Output port | `<Noun><Purpose>Port` | `TaskPersistencePort` |
| Domain event | `<Noun><PastVerb>` | `TaskCompleted` |
| JPA entity | `<Noun>JpaEntity` | `TaskJpaEntity` |
| REST DTO | `<Noun><Request/Response>` | `CreateTaskRequest` |
| Database table | snake_case, plural | `workspace_members` |
| Flyway file | `V<n>__<description>.sql` | `V3__create_tasks.sql` |

- All code, comments, logs and messages are in English.