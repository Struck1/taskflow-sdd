# Tech Stack - TaskFlow pro

# Runtime / Platform
- **Language:** Java 25 (LTS)
- **Framework:** Spring Boot 4.x 
- **Build tool:** Gradle (Kotlin DSL)
- **Database:** PostgreSQL
- **Deployment target:** Docker container
- **Testing:** JUnit 5 + Testcontainers (real PostgreSQL) 
- **Architecture:** Modular monolith, hexagonal modules (see conventions.md)

## Architectural Decisions

### Why a modular monolith?
One deployable application keeps operations simple for a small team
and 1,000 users: one build, one database, no network calls between
modules. Clear module boundaries keep the code ready to split later
if a module ever needs to scale on its own.

### Why hexagonal modules?
The domain stays free of Spring and JPA, so business rules are tested
with plain unit tests. The cost is mapping between domain objects and
JPA entities; we accept it to keep every module in the same shape.

### Why PostgreSQL
Our data is relational: a workspace owns tasks, a task owns subtasks.
Foreign keys and unique constrants enforce these business rules in the database itself, so a bug in application code cannot break them.

## Why Gradle

Kotlin DSL gives a more modern, type-safe syntax than Maven's XML.
Build logic is real code, so we can use loops and functions instead
of repeating configuration. Keep build logic simple; complex logic goes into a convention plugin.

## Why Flyway
Schema changes are explicit SQL files, reviewed and versioned in git like any other code. Hibernate never changes the schema:
`spring.jpa.hibernate.dll-auto` is always `validate`.

### Why Testcontainers?
Tests run against a real PostgreSQL in Docker, not an in-memory
imitation like H2. Constraints, indexes and Flyway migrations are
tested exactly as they run in production.

## Verification Commands
| Purpose | Command | Notes |
|---------|---------|-------|
| Test | `./gradlew test` | Requires Docker (Testcontainers) |
| Build | `./gradlew build` | Compiles, runs tests, packages the jar |
| Full verify | `./gradlew clean build` | Must pass before any task is marked done |

## Open Questions
- Q-001: Code formatter / linter (Spotless? Checkstyle? none?) — not decided.
- Q-002: Frontend framework — not decided.