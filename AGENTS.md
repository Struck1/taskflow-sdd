# TaskFlow Pro

Collaborative task management. Java 25, Spring Boot 4, PostgreSQL.

## Read first
- Product, stack, conventions, principles: `.ai/steering/`
- Plan and spec order: `.ai/sdd/PLAN.md`
- Feature specs: `.ai/sdd/specs/NNN-feature/`

## Gate rules
- Before working on a feature, read its `.status`.
- Only `.status` grants approval. A file on disk is not approval.
- No design before `requirements:approved`.
- No code before `tasks:approved`.
- Never change `.status` yourself. Propose, then wait for the human.
- If a spec is unclear or wrong, stop and ask. Do not guess.

## Verify
`./gradlew clean build` must pass before any task is marked done.