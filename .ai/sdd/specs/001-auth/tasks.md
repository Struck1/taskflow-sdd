# Tasks: Authentication

> Status: Draft
> Spec: 001-auth
> Requirements: [requirements.md](requirements.md) · Design: [design.md](design.md)
> Last Updated: 2026-11-25

Paths are relative to the repository root. `main/` means
`src/main/java/com/taskflow/`, and `test/` means `src/test/java/com/taskflow/`.

## Implementation Readiness Check

| # | Check | State | Notes |
|---|-------|-------|-------|
| 1 | Requirements approved | ✅ | `.status` history: `requirements:approved` (commit 2c76f94) |
| 2 | Design approved | ✅ | `.status` = `design:approved` (commit 435a1f5) |
| 3 | Tasks approved | ⛔ | **No code until `.status` = `tasks:approved`** (AGENTS.md, principle 3) |
| 4 | Steering matches the design | ✅ | conventions.md now has shared IDs, adapter channels/targets, 202/429 and test rules. PLAN.md says server-side sessions. |
| 5 | Status codes used by the design are in conventions.md | ⛔ | The design returns `415` (non-JSON body, TD-015), `403` for a missing CSRF token (TD-015; conventions defines `403` as "authenticated but not allowed"), and `503` (Argon2 busy, TD-016). None of these is in the conventions table yet. **Needs a decision before T15, T18 and T19.** |
| 6 | Docker is available (Testcontainers, GreenMail runs in-process) | ⛔ | `docker` isn't installed in this WSL distro. `./gradlew clean build` can't pass without it, starting with T1's context test. Turn on Docker Desktop WSL integration, or install Docker Engine in WSL. |
| 7 | JDK 25 | ⚠️ | Only JDK 21 is installed. T1 uses a Gradle toolchain with the Foojay resolver, which downloads JDK 25 on the first build and needs network access. Installing it with SDKMAN works too. |
| 8 | Network access on first build | ⚠️ | Needed for the Gradle wrapper, dependencies, JDK 25 (row 7) and Playwright's Chromium (T22). |
| 9 | Code formatter (tech-stack Q-001) | ➖ | Undecided. Not needed for this spec. No formatter is added. |
| 10 | Frontend (tech-stack Q-002) | ➖ | Covered by the temporary pages (TD-008, T22) and risk R-3. |
| 11 | Production SMTP provider (R-2) | ➖ | Not needed to build or test: Mailpit locally, GreenMail in tests. Needed before production. |
| 12 | Argon2 cost on the target container (R-1, TD-005) | ➖ | T10 measures it locally only. Measuring on the real container is a deployment step, outside these tasks. |

**Verdict:** the tasks can be reviewed now. Implementation can start only after:
- rows 3, 5 and 6 are resolved, and
- row 7 is resolved, if the Foojay JDK download isn't acceptable.

## Requirement Coverage

FR-010, FR-011 and FR-012 were removed from the requirements, so they have no tasks.

| ID | Covered by |
|----|------------|
| FR-001 Start sign-up | T2, T4, T13, T17 |
| FR-002 Existing verified email | T4, T13, T17 |
| FR-003 Password sign-in | T5, T19 |
| FR-004 Brute-force protection | T6, T9, T19 |
| FR-005 Request magic link | T4, T13, T17 |
| FR-006 Use magic link | T3, T5, T20, T22 |
| FR-007 Session lifetime | T3, T8, T16, T21 |
| FR-008 Sign out | T5, T20 |
| FR-009 Verify email and set password | T2, T3, T5, T18, T22 |
| FR-013 Expire unverified accounts | T7, T21 |
| NFR-001 Password storage | T2, T10 |
| NFR-002 Response time | T8, T10, T11, T13, T17, T19 |
| NFR-003 Token secrecy | T2, T11, T12, T15, T16, T17, T18, T19, T20, T22 |
| NFR-004 No timing leaks | T1, T10, T13, T17, T19 |
| TD-001 Server-side sessions | T8, T16, T20 |
| TD-002 `__Host-session` cookie | T16 |
| TD-003 Touch at most hourly | T7, T8 |
| TD-004 Aggregates | T4, T5, T6, T8 |
| TD-005 Argon2id | T10, T19 |
| TD-006 Timing / `auth_requests` | T10, T13, T14, T17, T19 |
| TD-007 Email outbox | T11, T12 |
| TD-008 Fragment tokens, POST to consume, temporary pages | T3, T18, T20, T22 |
| TD-009 Throttle counted before check | T6, T9, T19 |
| TD-010 Self-validating value objects | T2 |
| TD-011 Schema and constraints | T7 |
| TD-012 Cleanup job | T21 |
| TD-013 Spring Security, shared `UserId` | T2, T15, T16 |
| TD-014 REST API shape | T15, T17, T18, T19, T20 |
| TD-015 CSRF | T15, T22 |
| TD-016 Argon2 concurrency limit | T10, T18, T19 |

## Dependency overview

```
T1 ─► T2 ─┬─► T3 ─► T4 ─► T5 ─► T7 ─┬─► T8 ─► T16 ─┬─► T18 ─┐
          │                         ├─► T9 ─────────┼─► T19 ─┼─► T20 ─┐
          ├─► T6 ───────────────────┘               │        │        │
          ├─► T10 ──────────────────────────────────┴────────┘        │
          └─► T15 ─────────────────────► (T16, T17)                   │
T7 ─► T11 ─┬─► T12 ───────────────────────────────────────────────────┤
           └─► T13 ─► T14 ─► T17 ─────────────────────────────────────┼─► T22
T7 ─► T21                                                             │
```

The full dependency list is in each task.

---

## T1: Gradle project skeleton
**Serves:** NFR-004 (the `timingTest` task), every task (the build)
**Depends on:** —
**Estimate:** 2–3 h

**Checklist**
- [x] Gradle wrapper, `settings.gradle.kts` (`rootProject.name = "taskflow-pro"`, Foojay toolchain resolver)
- [x] `build.gradle.kts`: Java 25 toolchain, Spring Boot 4.x plugin, dependency management
- [x] Dependencies: web, security, data-jpa, flyway (with the PostgreSQL module), postgresql driver, mail, actuator/micrometer, BouncyCastle (`bcprov`)
- [x] Test dependencies: Spring Boot test, Testcontainers (PostgreSQL, JUnit 5), GreenMail (JUnit 5), Playwright for Java, AssertJ, Mockito
- [x] `test` task: `useJUnitPlatform { excludeTags("timing") }`
- [x] `timingTest` task: type `Test`, `includeTags("timing")`, not wired into `check` or `build` (design §9)
- [x] `TaskflowApplication` in `com.taskflow`
- [x] `application.yml`: `spring.jpa.hibernate.ddl-auto=validate`, `open-in-view=false`, Flyway on, Jackson/JDBC time zone UTC
- [x] `@TestConfiguration` with a PostgreSQL Testcontainer through `@ServiceConnection`, reused by later tasks
- [x] Context-loads test
- [x] `.gitignore` for Gradle and build output (`.idea/` stays untouched)

**Acceptance criteria**
- `./gradlew clean build` passes on a machine with Docker and runs the context test against real PostgreSQL.
- `./gradlew timingTest` runs and reports "no tests" without failing.
- `./gradlew tasks` lists `timingTest`, and `./gradlew build --dry-run` doesn't include it.

**Likely files**
`settings.gradle.kts`, `build.gradle.kts`, `gradle/wrapper/*`, `gradlew`, `gradlew.bat`, `.gitignore`,
`main/TaskflowApplication.java`, `src/main/resources/application.yml`,
`test/TestcontainersConfiguration.java`, `test/TaskflowApplicationTests.java`

**Verify:** `./gradlew clean build && ./gradlew timingTest`

---

## T2: Shared `UserId` and auth value objects
**Serves:** FR-001, FR-009, NFR-001, NFR-003, TD-010, TD-013 (shared ID)
**Depends on:** T1
**Estimate:** 3 h

**Checklist**
- [x] `com.taskflow.shared.domain.UserId` (a record over UUID, rejects `null`). This package holds only ID records.
- [x] `EmailAddress`: required → trim → `toLowerCase(Locale.ROOT)` → syntax rules from TD-010
- [x] `RawPassword`: blank → at least 8 → at most 128 code points, in that order. Never trimmed. `toString()` returns `RawPassword[***]`.
- [x] `RawToken` (redacted `toString()`), `TokenHash` (SHA-256 of a raw token, 32 bytes, `TokenHash.of(RawToken)` in plain Java), `PasswordHash` (opaque string)
- [x] `SessionId`, `LinkTokenId` records
- [x] `AuthPolicy` constants: 15 min, 60 min, 30 d, 90 d, 1 h touch, 3 links, 5 failures, 7 d, 24 h
- [x] Error messages exactly as in the requirements' validation table

**Acceptance criteria**
- Every row of the requirements' validation table is a passing unit test.
- `" Ali@Mail.com "` gives `ali@mail.com`. `"ILKER@MAIL.COM"` gives `ilker@mail.com` with the JVM default locale set to `tr-TR`.
- An emoji password of 8 code points (16 UTF-16 chars) is accepted. 129 code points are rejected.
- `toString()` of `RawPassword` and `RawToken` never contains the value.
- No Spring, JPA or Jackson imports in `domain/` or `shared/domain/`.

**Likely files**
`main/shared/domain/UserId.java`,
`main/auth/domain/{EmailAddress,RawPassword,RawToken,TokenHash,PasswordHash,SessionId,LinkTokenId,AuthPolicy}.java`,
`test/auth/domain/{EmailAddressTest,RawPasswordTest,TokenHashTest}.java`

**Verify:** `./gradlew test --tests 'com.taskflow.auth.domain.*' && ./gradlew clean build`

---

## T3: `LinkToken` and `Session` domain entities
**Serves:** FR-006, FR-007, FR-009, TD-008
**Depends on:** T2
**Estimate:** 2 h

**Checklist**
- [x] `LinkPurpose` enum (`VERIFY_EMAIL`, `MAGIC_LINK`)
- [x] `LinkToken`: id, purpose, tokenHash, createdAt, expiresAt, usedAt, invalidatedAt
- [x] `LinkToken`: `isUsableAt(now)`, `markUsed(now)`, `invalidate(now)`, and a reason when it's unusable (expired / used / invalidated)
- [x] `Session`: id, tokenHash, createdAt, lastSeenAt
- [x] `Session`: `isActiveAt(now)` (30 d idle and 90 d absolute), `needsTouch(now)` (1 h)

**Acceptance criteria**
- Boundary tests: a link token expires exactly at `expiresAt`.
- A session is active at `lastSeenAt + 30 d − 1 s` and inactive at `+ 30 d`.
- The 90-day absolute limit holds even when the session was touched yesterday.
- `needsTouch` is false at 59 min and true at 60 min.
- A used or invalidated token is never usable again.

**Likely files**
`main/auth/domain/{LinkPurpose,LinkToken,Session}.java`, `test/auth/domain/{LinkTokenTest,SessionTest}.java`

**Verify:** `./gradlew test --tests 'com.taskflow.auth.domain.*' && ./gradlew clean build`

---

## T4: `User` aggregate: registration and issuing links
**Serves:** FR-001, FR-002, FR-005, TD-004
**Depends on:** T3
**Estimate:** 3–4 h

**Checklist**
- [ ] `User` root fields as in design §3, including `version`
- [ ] `User.register(id, email, now)`: unverified, no password
- [ ] `issueVerificationToken(id, tokenHash, now)`: only while unverified. Returns `Issued | RateLimited`. Allows at most 3 per 15 min (sliding window). Invalidates older unused tokens of the same purpose. Expires after 60 min.
- [ ] `issueMagicLinkToken(...)`: the same rules, only while verified, expires after 15 min
- [ ] `recordSignUpAttempt(now)`: only while verified. Returns `SendWarning` at most once per 24 h and updates `lastSignUpWarningAt`.
- [ ] A factory that rebuilds a `User` from stored state, for persistence in T7

**Acceptance criteria**
- The 4th token within 15 min gives `RateLimited`. A token at minute 16 is issued again.
- A new token invalidates every older unused token of the same purpose, and leaves the other purpose alone.
- A verification token can't be issued for a verified user, and a magic link can't be issued for an unverified user.
- The warning is returned at 0 h and suppressed at 23 h 59 min. It is returned again at 24 h.
- Re-issuing doesn't change `createdAt` (the FR-013 clock).

**Likely files**
`main/auth/domain/User.java` (plus result types), `test/auth/domain/UserRegistrationTest.java`

**Verify:** `./gradlew test --tests 'com.taskflow.auth.domain.*' && ./gradlew clean build`

---

## T5: `User` aggregate: verification, sign-in and sign-out
**Serves:** FR-003, FR-006, FR-008, FR-009, TD-004
**Depends on:** T4
**Estimate:** 3 h

**Checklist**
- [ ] `checkVerificationToken(tokenHash, now)`: `Usable`, or `Rejected(SIGN_IN_INSTEAD | SIGN_UP_AGAIN)` depending on whether the account is verified
- [ ] `verifyEmail(tokenHash, passwordHash, sessionId, sessionTokenHash, now)`: sets the password and `verifiedAt`, marks the token used, returns a new `Session`
- [ ] `signInWithMagicLink(tokenHash, sessionId, sessionTokenHash, now)`: only while verified, marks the token used, returns a `Session`
- [ ] `startSession(...)`: requires a verified user
- [ ] `endSession(sessionId)`
- [ ] `replacePasswordHash(newHash)` for the rehash in TD-005

**Acceptance criteria**
- After `verifyEmail` the user is verified, has a password, has one session, and the token is used.
- Verifying again with the same token gives `Rejected(SIGN_IN_INSTEAD)`.
- An expired token on an unverified user gives `Rejected(SIGN_UP_AGAIN)`.
- A magic-link token can't be used for verification, and the reverse.
- After `endSession` the session is gone. Ending an unknown session ID does nothing (idempotent).

**Likely files**
`main/auth/domain/User.java`, `test/auth/domain/{UserVerificationTest,UserSessionTest}.java`

**Verify:** `./gradlew test --tests 'com.taskflow.auth.domain.*' && ./gradlew clean build`

---

## T6: `SignInThrottle` aggregate
**Serves:** FR-004, TD-004, TD-009
**Depends on:** T2
**Estimate:** 2 h

**Checklist**
- [ ] `SignInThrottle(email, recentFailures ≤ 5, lockedUntil)`
- [ ] `beginAttempt(now)`: returns `Locked(retryAfter)` while `now < lockedUntil`. Otherwise it records a failure up front, drops failures older than 15 min, and locks for 15 min on the 5th failure in the window.
- [ ] `reset()`

**Acceptance criteria**
- 5 attempts within 15 min: the 5th sets the lock, and the 6th returns `Locked` with `retryAfter` ≤ 15 min.
- Failures at minutes 0, 1, 2, 3 and then 16 don't lock (sliding window).
- After the lock expires, an attempt is allowed.
- `reset()` clears failures and the lock.

**Likely files**
`main/auth/domain/SignInThrottle.java`, `test/auth/domain/SignInThrottleTest.java`

**Verify:** `./gradlew test --tests 'com.taskflow.auth.domain.*' && ./gradlew clean build`

---

## T7: Flyway V1 and `User` persistence
**Serves:** FR-013 (cascade), TD-003 (insert/delete-only sessions), TD-011
**Depends on:** T5
**Estimate:** 4 h

**Checklist**
- [ ] `V1__create_auth_tables.sql`: all six tables from design §4 (`users`, `link_tokens`, `sessions`, `sign_in_throttles`, `auth_requests`, `email_outbox`), with every constraint and index listed there
- [ ] `UserJpaEntity`, `LinkTokenJpaEntity`, `SessionJpaEntity`, with `@Version` on users
- [ ] Mapper between domain and JPA objects
- [ ] `UserPersistencePort` with `findByEmail`, `findById`, `findByLinkTokenHash`, `findBySessionTokenHash`, `save`
- [ ] `UserPersistenceAdapter` implementing that port
- [ ] Saving inserts new sessions and deletes removed ones. It never updates existing session rows.
- [ ] A `java.time.Clock` bean (UTC) in a config class, injected wherever the code needs the current time

**Acceptance criteria**
- Integration tests (`@DataJpaTest` + Testcontainers):
  - The schema passes `ddl-auto=validate`.
  - A duplicate email is rejected by the database.
  - Inserting a verified user without a password violates the `CHECK`.
  - Deleting a user cascades to its tokens and sessions.
  - A save from a stale version throws an optimistic-lock exception.
- A round trip (save, then load) preserves every field of `User`, `LinkToken` and `Session`.

**Likely files**
`src/main/resources/db/migration/V1__create_auth_tables.sql`,
`main/auth/application/port/out/UserPersistencePort.java`,
`main/auth/adapter/out/persistence/{UserJpaEntity,LinkTokenJpaEntity,SessionJpaEntity,UserJpaRepository,UserPersistenceAdapter,UserMapper}.java`,
`main/config/ClockConfig.java`,
`test/auth/adapter/out/persistence/UserPersistenceAdapterTest.java`

**Verify:** `./gradlew test --tests 'com.taskflow.auth.adapter.out.persistence.*' && ./gradlew clean build`

---

## T8: `SessionQueryPort`: read and touch
**Serves:** FR-007, NFR-002, TD-001, TD-003, TD-004
**Depends on:** T7
**Estimate:** 2 h

**Checklist**
- [ ] `SessionQueryPort.findByTokenHash(TokenHash)` returns `Optional<SessionView(sessionId, userId, createdAt, lastSeenAt)>`
- [ ] `SessionQueryPort.touch(sessionId, now)`: the conditional `UPDATE … WHERE last_seen_at < :now - interval '1 hour'` from design §5
- [ ] The touch doesn't change `users.version`

**Acceptance criteria**
- Integration tests:
  - Touch updates `last_seen_at` only when it is more than 1 h old.
  - `users.version` doesn't change after a touch.
  - **TD-003 guard:** load a user, touch one of its sessions, then save the user with a new session added. The touched `last_seen_at` is kept.

**Likely files**
`main/auth/application/port/out/SessionQueryPort.java`,
`main/auth/adapter/out/persistence/SessionQueryAdapter.java`,
`test/auth/adapter/out/persistence/SessionQueryAdapterTest.java`

**Verify:** `./gradlew test --tests 'com.taskflow.auth.adapter.out.persistence.*' && ./gradlew clean build`

---

## T9: `SignInThrottle` persistence
**Serves:** FR-004, TD-009
**Depends on:** T6, T7
**Estimate:** 2 h

**Checklist**
- [ ] `SignInThrottleJpaEntity` (with `timestamptz[]` mapping)
- [ ] `SignInThrottlePersistencePort.lockForUpdate(email)`: `INSERT … ON CONFLICT DO NOTHING`, then `SELECT … FOR UPDATE`, then return the domain object
- [ ] `save(throttle)`, which also sets `updated_at`

**Acceptance criteria**
- Integration test: the first call creates the row, and the second returns the same row.
- Two concurrent transactions on the same email are serialized: the second waits until the first commits.
- The array of failure timestamps survives a round trip with UTC precision.

**Likely files**
`main/auth/application/port/out/SignInThrottlePersistencePort.java`,
`main/auth/adapter/out/persistence/{SignInThrottleJpaEntity,SignInThrottlePersistenceAdapter}.java`,
`test/auth/adapter/out/persistence/SignInThrottlePersistenceAdapterTest.java`

**Verify:** `./gradlew test --tests 'com.taskflow.auth.adapter.out.persistence.*' && ./gradlew clean build`

---

## T10: Password hasher (Argon2id, dummy hash, concurrency limit) and token generator
**Serves:** NFR-001, NFR-002, NFR-004, TD-005, TD-006 (dummy hash), TD-016
**Depends on:** T2
**Estimate:** 3–4 h

**Checklist**
- [ ] `PasswordHasherPort` with `hash(RawPassword)`, `matches(RawPassword, PasswordHash)`, `matchesDummy(RawPassword)`, `needsRehash(PasswordHash)`
- [ ] The adapter uses `Argon2PasswordEncoder` with m = 19 MiB, t = 2, p = 1, salt 16 bytes, hash 32 bytes
- [ ] The dummy hash is computed once at startup with the same parameters
- [ ] A fair `Semaphore` wraps every operation. Permits come from `taskflow.auth.argon2.max-concurrent` (default 4).
- [ ] `tryAcquire(1, SECONDS)`. On timeout, throw `PasswordHashingBusyException`. The permit is always released in `finally`.
- [ ] Micrometer gauge for waiting requests, and a counter for rejected ones
- [ ] `TokenGeneratorPort`, with an adapter using `SecureRandom`: 32 bytes, base64url without padding
- [ ] Record the local time per hash in the PR description (R-1: the real container is measured at deployment)

**Acceptance criteria**
- `hash` gives an `$argon2id$v=19$m=19456,t=2,p=1$…` string. `matches` is true for the right password and false for a wrong one.
- `matchesDummy` always returns false, and its duration is within a tolerance of `matches`.
- With 1 permit, a second concurrent call waits. If the first holds the permit for more than 1 s, the second throws `PasswordHashingBusyException`, and the rejection counter goes up.
- After an exception inside the hash, the permit is released.
- `needsRehash` is true for a hash with weaker parameters.
- Generated tokens are 43 base64url characters, and 10,000 of them have no duplicates.

**Likely files**
`main/auth/application/port/out/{PasswordHasherPort,TokenGeneratorPort}.java`,
`main/auth/adapter/out/security/{Argon2PasswordHasher,SecureRandomTokenGenerator,PasswordHashingBusyException}.java`,
`test/auth/adapter/out/security/{Argon2PasswordHasherTest,SecureRandomTokenGeneratorTest}.java`

> Note: `adapter/out/security/` isn't one of the example targets in conventions.md
> (`persistence`, `email`), but the generalized `adapter/out/<target>/` allows it.
> Review this folder name together with this task.

**Verify:** `./gradlew test --tests 'com.taskflow.auth.adapter.out.security.*' && ./gradlew clean build`

---

## T11: Email outbox: composing, storing and delivering
**Serves:** NFR-002, NFR-003, TD-007
**Depends on:** T7
**Estimate:** 4 h

**Checklist**
- [ ] `AuthEmailComposer` (application/service) builds plain-text English emails for:
  - verification, with a `{base-url}/auth/verify-email.html#token=…` link
  - magic link, with a `{base-url}/auth/magic-link.html#token=…` link
  - the sign-up warning, using the Q-001 text
- [ ] `taskflow.auth.base-url` property
- [ ] `EmailOutboxPort` with `enqueue`, `claimDue(limit)` (`FOR UPDATE SKIP LOCKED`), `markSent` (clears `body`), `markRetry(nextAttemptAt)`, `markFailed`
- [ ] `EmailOutboxPersistenceAdapter` implementing that port
- [ ] `EmailSenderPort` (interface only; T12 implements it)
- [ ] `DeliverPendingEmailsUseCase` and its service: exponential backoff from 30 s, capped at 1 h. After 10 attempts, `failed_at` is set and an `ERROR` is logged.
- [ ] `EmailOutboxJob` in `adapter/in/scheduling/` runs every 2 s
- [ ] `@EnableScheduling`
- [ ] Logs show only the template name and the outbox ID

**Acceptance criteria**
- **Service unit tests** (ports mocked):
  - success marks the row sent
  - a failure schedules a retry with the right backoff
  - the 10th failure marks the row failed and logs at `ERROR`
- **Persistence integration test:**
  - two concurrent `claimDue` calls never return the same row
  - after `markSent`, `body` is `NULL`
- **Scheduling integration test:** calling the job method directly delivers a due row through a stub `EmailSenderPort`.
- A test using `OutputCaptureExtension` shows that no token or link appears in the logs.

**Likely files**
`main/auth/application/port/in/DeliverPendingEmailsUseCase.java`,
`main/auth/application/port/out/{EmailOutboxPort,EmailSenderPort}.java`,
`main/auth/application/service/{AuthEmailComposer,DeliverPendingEmailsService}.java`,
`main/auth/adapter/out/persistence/{EmailOutboxJpaEntity,EmailOutboxPersistenceAdapter}.java`,
`main/auth/adapter/in/scheduling/EmailOutboxJob.java`, plus tests in the matching packages

**Verify:** `./gradlew test --tests 'com.taskflow.auth.*Outbox*' --tests 'com.taskflow.auth.*Email*' && ./gradlew clean build`

---

## T12: SMTP email sender and local Mailpit
**Serves:** NFR-003, TD-007, R-2
**Depends on:** T11
**Estimate:** 2 h

**Checklist**
- [ ] `SmtpEmailSender` implements `EmailSenderPort` with Spring `JavaMailSender`
- [ ] `spring.mail.*` in `application.yml` points to Mailpit (`localhost:1025`) by default, so no provider is assumed
- [ ] `compose.yaml` with PostgreSQL and Mailpit for local runs only (it isn't used by tests)
- [ ] Sender address from `taskflow.auth.mail-from`

**Acceptance criteria**
- An integration test with GreenMail (`@RegisterExtension`) receives one message with the right recipient, sender, subject and plain-text body, and a link in fragment form.
- An SMTP failure surfaces as an exception that T11's service turns into a retry.

**Likely files**
`main/auth/adapter/out/email/SmtpEmailSender.java`, `src/main/resources/application.yml`, `compose.yaml`,
`test/auth/adapter/out/email/SmtpEmailSenderTest.java`

**Verify:** `./gradlew test --tests 'com.taskflow.auth.adapter.out.email.*' && ./gradlew clean build`

---

## T13: Processing auth requests (the branching logic)
**Serves:** FR-001, FR-002, FR-005, NFR-002, NFR-004, TD-006
**Depends on:** T5, T11
**Estimate:** 3 h

**Checklist**
- [ ] `ProcessAuthRequestsUseCase.process(AuthRequest)`, with one transaction per request
- [ ] Branches for `SIGN_UP`:
  - **no user:** register, issue a verification token, enqueue the verification email
  - **unverified user:** issue a token, or do nothing if rate-limited
  - **verified user:** `recordSignUpAttempt`, and enqueue the warning on `SendWarning`
- [ ] Branches for `MAGIC_LINK`:
  - **verified user:** issue a token and enqueue the email, or do nothing if rate-limited
  - **unknown or unverified email:** do nothing
- [ ] A unique-email conflict during registration is retried as "unverified user exists"
- [ ] The `auth_requests` row is deleted in the same transaction (through `AuthRequestQueuePort`)

**Acceptance criteria**
- Unit tests, with ports mocked, cover every branch above: the right domain call is made, and the right email template is enqueued (or none).
- The raw token generated for an email appears only in the composed email body, never in a return value that gets logged.

**Likely files**
`main/auth/application/port/in/ProcessAuthRequestsUseCase.java`,
`main/auth/application/port/out/AuthRequestQueuePort.java`,
`main/auth/application/service/ProcessAuthRequestsService.java`,
`test/auth/application/service/ProcessAuthRequestsServiceTest.java`

**Verify:** `./gradlew test --tests 'com.taskflow.auth.application.service.*' && ./gradlew clean build`

---

## T14: Auth request queue persistence and worker
**Serves:** TD-006
**Depends on:** T13
**Estimate:** 2–3 h

**Checklist**
- [ ] `AuthRequestQueuePersistenceAdapter` with `enqueue(kind, email)`, `claimDue(limit)` (`FOR UPDATE SKIP LOCKED`), `delete`, `markRetry` (backoff, `attempts++`)
- [ ] `AuthRequestWorker` in `adapter/in/scheduling/` runs every 1 s. Each claimed row is processed in its own transaction. A failure logs at `ERROR` and is retried.

**Acceptance criteria**
- Persistence integration test: concurrent claims never overlap, and a retried row isn't claimed before `next_attempt_at`.
- **Scheduling integration test** (the job method called directly, Testcontainers): a `SIGN_UP` row for a new email ends with the user created, a verification token stored, an outbox row written, and the queue row deleted.
- A forced failure leaves the row with `attempts = 1` and logs an error.

**Likely files**
`main/auth/adapter/out/persistence/{AuthRequestJpaEntity,AuthRequestQueuePersistenceAdapter}.java`,
`main/auth/adapter/in/scheduling/AuthRequestWorker.java`, plus tests

**Verify:** `./gradlew test --tests 'com.taskflow.auth.adapter.*AuthRequest*' && ./gradlew clean build`

---

## T15: Security configuration, CSRF and Problem Details
**Serves:** NFR-003, TD-013, TD-014, TD-015
**Depends on:** T1, T2 · **Blocked by:** readiness row 5 (`403`/`415` in conventions)
**Estimate:** 3–4 h

**Checklist**
- [ ] `SecurityConfig`: a stateless filter chain with no `HttpSession`, no form login and no HTTP Basic
- [ ] Open paths: `POST /api/v1/sign-ups`, `POST /api/v1/email-verifications`, `POST /api/v1/magic-links`, `POST /api/v1/sessions`, and `/auth/**` (TD-013). Everything else under `/api/v1/**` requires sign-in, including `DELETE /api/v1/sessions/current`.
- [ ] CSRF: `csrf.spa()` with the cookie named `__Host-XSRF-TOKEN` (`Secure; Path=/; SameSite=Strict`) and the header `X-XSRF-TOKEN`
- [ ] An authentication entry point gives `401` and an access-denied handler gives `403`, both as Problem Details
- [ ] `AuthProblemHandler` maps:
  - validation → `400` with the requirements' messages
  - a non-JSON content type → `415`
  - a type URI base of `https://taskflow.pro/errors/`
- [ ] No stack traces, SQL or class names in any response

**Acceptance criteria**
- API tests (`@SpringBootTest` + Testcontainers) against a test-only controller:
  - a protected GET without a session gives `401` Problem Details
  - a `POST` without the CSRF header gives `403`
  - a `POST` with the cookie and matching header gets through
  - `text/plain` or form content gives `415`
- A `GET` sets the `__Host-XSRF-TOKEN` cookie with the right attributes.

**Likely files**
`main/auth/adapter/in/web/{SecurityConfig,AuthProblemHandler,ProblemTypes}.java`,
`test/auth/adapter/in/web/{SecurityConfigTest,TestOnlyController}.java`

**Verify:** `./gradlew test --tests 'com.taskflow.auth.adapter.in.web.*' && ./gradlew clean build`

---

## T16: Session authentication filter and session cookie
**Serves:** FR-007, NFR-003, TD-001, TD-002, TD-013
**Depends on:** T8, T15
**Estimate:** 3 h

**Checklist**
- [ ] `AuthenticateSessionUseCase` and its service: hash the token, `findByTokenHash`, `Session.isActiveAt`, touch when `needsTouch` is true, return `AuthenticatedUser(UserId, SessionId)`
- [ ] `SessionAuthenticationFilter` reads `__Host-session` and sets the `SecurityContext`. An invalid session gets `401`, and the cookie is cleared.
- [ ] `SessionCookies` helper:
  - **issue:** `__Host-session`, `HttpOnly; Secure; SameSite=Strict; Path=/`, with `Max-Age` set to the time left until the 90-day limit
  - **clear:** `Max-Age=0`
- [ ] `@AuthenticationPrincipal AuthenticatedUser` works in controllers

**Acceptance criteria**
- **Service unit tests:**
  - an active session authenticates
  - a session idle for 30 days is rejected
  - a session past the 90-day limit is rejected
  - touch is called only when `needsTouch` is true
- **API tests** with a seeded session:
  - the test-only endpoint returns the right `UserId`
  - an expired session gets `401` and a `Set-Cookie` that clears the cookie
  - an unknown token gets `401`
- The cookie attributes match TD-002 exactly.
- `OutputCaptureExtension`: the cookie value never appears in the logs.

**Likely files**
`main/auth/application/port/in/AuthenticateSessionUseCase.java`,
`main/auth/application/service/AuthenticateSessionService.java`,
`main/auth/adapter/in/web/{SessionAuthenticationFilter,SessionCookies,AuthenticatedUser}.java`, plus tests

**Verify:** `./gradlew test --tests 'com.taskflow.auth.*' && ./gradlew clean build`

---

## T17: Sign-up and magic-link request endpoints
**Serves:** FR-001, FR-002, FR-005, NFR-002, NFR-003, NFR-004, TD-006, TD-014
**Depends on:** T14, T15
**Estimate:** 3 h

**Checklist**
- [ ] `StartSignUpUseCase` and `RequestMagicLinkUseCase`: validate the `EmailAddress`, enqueue an `auth_requests` row, return nothing
- [ ] `SignUpController` handles `POST /api/v1/sign-ups` and returns `202` with "Check your email to continue."
- [ ] `MagicLinkController` handles `POST /api/v1/magic-links` and returns `202` with the same confirmation for every email
- [ ] DTOs: `StartSignUpRequest`, `RequestMagicLinkRequest`
- [ ] Part of `AuthTimingTest` (`@Tag("timing")`): sign-up and magic-link requests for existing and non-existing emails

**Acceptance criteria**
- **API tests:**
  - the response status and body are byte-identical for a new, an unverified and a verified email, on both endpoints
  - an invalid email gives `400` with the exact message from the validation table
  - there is exactly one `auth_requests` row per call
  - the response is the same `202` even when the user is rate-limited
- `./gradlew timingTest` passes its sign-up and magic-link cases, and `./gradlew build` doesn't run them.

**Likely files**
`main/auth/application/port/in/{StartSignUpUseCase,RequestMagicLinkUseCase}.java`,
`main/auth/application/service/{StartSignUpService,RequestMagicLinkService}.java`,
`main/auth/adapter/in/web/{SignUpController,MagicLinkController,StartSignUpRequest,RequestMagicLinkRequest}.java`,
`test/auth/adapter/in/web/{SignUpApiTest,MagicLinkRequestApiTest,AuthTimingTest}.java`

**Verify:** `./gradlew test --tests 'com.taskflow.auth.adapter.in.web.*' && ./gradlew timingTest && ./gradlew clean build`

---

## T18: Email verification endpoint
**Serves:** FR-009, NFR-003, TD-008, TD-014, TD-016
**Depends on:** T7, T10, T16 · **Blocked by:** readiness row 5 (`503` in conventions)
**Estimate:** 3 h

**Checklist**
- [ ] `VerifyEmailUseCase` and its service, in this order:
  1. validate the `RawPassword`
  2. look the user up by token hash
  3. `checkVerificationToken`
  4. take an Argon2 permit and hash the password
  5. `verifyEmail` and save
  6. issue the session cookie
- [ ] Outcomes:
  - unknown token → `409 link-invalid`, "Sign up again to get a new link."
  - `Rejected` → `409` with the FR-009 message for that reason
  - an optimistic-lock conflict → `409` "Sign in instead"
  - `PasswordHashingBusyException` → `503 temporarily-busy` with `Retry-After: 1`
- [ ] `EmailVerificationController` handles `POST /api/v1/email-verifications` and returns `200` with the cookie
- [ ] DTO `VerifyEmailRequest`, whose `toString()` hides the token and the password

**Acceptance criteria**
- **API tests:**
  - happy path: `200`, a session cookie, and the user is verified with a password
  - a used token on a verified account gives `409` "Sign in instead"
  - an expired token on an unverified account gives `409` "Sign up again to get a new link."
  - an invalid password gives `400`, and the token is still usable afterwards
  - two parallel submits: exactly one `200`
  - with the hasher stubbed as busy: `503`, and the token is still unused
- `OutputCaptureExtension`: neither the token nor the password appears in the logs.

**Likely files**
`main/auth/application/port/in/VerifyEmailUseCase.java`,
`main/auth/application/service/VerifyEmailService.java`,
`main/auth/adapter/in/web/{EmailVerificationController,VerifyEmailRequest}.java`,
`test/auth/adapter/in/web/EmailVerificationApiTest.java`

**Verify:** `./gradlew test --tests 'com.taskflow.auth.adapter.in.web.EmailVerification*' && ./gradlew clean build`

---

## T19: Password sign-in endpoint
**Serves:** FR-003, FR-004, NFR-002, NFR-003, NFR-004, TD-005, TD-006, TD-009, TD-014, TD-016
**Depends on:** T9, T10, T16 · **Blocked by:** readiness row 5 (`503` in conventions)
**Estimate:** 4 h

**Checklist**
- [ ] `SignInWithPasswordUseCase` and its service, following design §5:
  1. take an Argon2 permit (busy → `503`, nothing recorded)
  2. transaction A: `beginAttempt`, then commit (locked → `429`)
  3. `matches`, or `matchesDummy` when there is no user or no password
  4. on failure → `401`
  5. on success → transaction B (`reset`) and transaction C (`startSession`, plus a rehash if `needsRehash`)
- [ ] `SessionController` handles `POST /api/v1/sessions` with `{email, password}`. It returns `201`, `Location: /api/v1/sessions/current`, and the cookie.
- [ ] `429 too-many-attempts`: "Too many attempts. Try again in 15 minutes.", with `Retry-After` in seconds
- [ ] A body with both credential types, or neither, gives `400`
- [ ] DTO `CreateSessionRequest`, whose `toString()` hides secrets
- [ ] Add the password sign-in cases to `AuthTimingTest`: unknown email, unverified email and wrong password, compared with a known email

**Acceptance criteria**
- **API tests:**
  - correct credentials give `201`, the cookie and `Location`
  - a wrong password, an unknown email and an unverified email all give the identical `401` body
  - the 6th attempt within 15 min gives `429` even with the right password
  - a success resets the counter
  - 20 parallel wrong attempts: at most 5 reach the hash check, and the rest get `429`
  - with the hasher busy: `503`, and the throttle row is unchanged
- **Service unit tests:** exactly one hasher call on every path. A weaker stored hash is upgraded on success.
- `./gradlew timingTest` passes its sign-in cases.
- `OutputCaptureExtension`: no password appears in the logs.

**Likely files**
`main/auth/application/port/in/SignInWithPasswordUseCase.java`,
`main/auth/application/service/SignInWithPasswordService.java`,
`main/auth/adapter/in/web/{SessionController,CreateSessionRequest}.java`,
`test/auth/adapter/in/web/PasswordSignInApiTest.java`,
`test/auth/application/service/SignInWithPasswordServiceTest.java`,
`test/auth/adapter/in/web/AuthTimingTest.java`

**Verify:** `./gradlew test --tests 'com.taskflow.auth.*SignIn*' && ./gradlew timingTest && ./gradlew clean build`

---

## T20: Magic-link sign-in and sign-out
**Serves:** FR-006, FR-008, NFR-003, TD-001, TD-008, TD-014
**Depends on:** T19
**Estimate:** 3 h

**Checklist**
- [ ] `SignInWithMagicLinkUseCase` and its service: find the user by token hash, `signInWithMagicLink`, save
- [ ] Any unusable token, or an optimistic-lock conflict, gives `409 link-invalid`: "This link is no longer valid. Request a new one."
- [ ] `SessionController` also accepts `POST /api/v1/sessions` with `{magicLinkToken}` and returns `201`, the cookie and `Location`
- [ ] `SignOutUseCase` and its service: `user.endSession(sessionId)`, then save
- [ ] `DELETE /api/v1/sessions/current` returns `204` and clears the cookie. Without a valid session it returns `401`.

**Acceptance criteria**
- **API tests:**
  - a valid magic link gives `201` and a session
  - using the same token again gives `409`
  - an expired token, or one replaced by a newer link, gives `409`
  - two parallel uses: exactly one `201`
  - after sign-out, the old cookie gets `401` on a protected endpoint, and the session row is gone
- `OutputCaptureExtension`: no token appears in the logs.

**Likely files**
`main/auth/application/port/in/{SignInWithMagicLinkUseCase,SignOutUseCase}.java`,
`main/auth/application/service/{SignInWithMagicLinkService,SignOutService}.java`,
`main/auth/adapter/in/web/SessionController.java`,
`test/auth/adapter/in/web/{MagicLinkSignInApiTest,SignOutApiTest}.java`

**Verify:** `./gradlew test --tests 'com.taskflow.auth.adapter.in.web.*' && ./gradlew clean build`

---

## T21: Cleanup job
**Serves:** FR-007, FR-013, TD-012
**Depends on:** T7
**Estimate:** 2–3 h

**Checklist**
- [ ] `CleanUpAuthDataUseCase` and its service. It runs one idempotent `DELETE` per item in design TD-012.
- [ ] One method per delete on a persistence port, so each is tested separately
- [ ] `AuthCleanupJob` in `adapter/in/scheduling/` runs hourly
- [ ] Log the number of rows deleted per category (counts only)

**Acceptance criteria**
- **Scheduling integration test** (job method called directly, Testcontainers):
  - an unverified user aged 7 d + 1 min is deleted, with its tokens and sessions
  - an unverified user aged 6 d 23 h is kept
  - a verified user is always kept
  - an inactive session is deleted and an active one is kept
  - a link token older than 24 h is deleted and a 14-minute-old one is kept
  - a stale unlocked throttle row is deleted and a locked one is kept
  - an outbox row sent more than 24 h ago is deleted
- Running the job twice in a row is harmless.

**Likely files**
`main/auth/application/port/in/CleanUpAuthDataUseCase.java`,
`main/auth/application/port/out/AuthCleanupPort.java`,
`main/auth/application/service/CleanUpAuthDataService.java`,
`main/auth/adapter/out/persistence/AuthCleanupAdapter.java`,
`main/auth/adapter/in/scheduling/AuthCleanupJob.java`,
`test/auth/adapter/in/scheduling/AuthCleanupJobTest.java`

**Verify:** `./gradlew test --tests 'com.taskflow.auth.adapter.in.scheduling.*' && ./gradlew clean build`

---

## T22: Temporary pages and the browser end-to-end test
**Serves:** FR-006, FR-009, NFR-003, TD-008, TD-015
**Depends on:** T12, T14, T17, T18, T20
**Estimate:** 4 h

**Checklist**
- [ ] `static/auth/verify-email.html` and `static/auth/magic-link.html`: plain HTML and JS with no inline script (CSP)
- [ ] Each page:
  - reads `#token`, then calls `history.replaceState` to remove it
  - shows the password form or the "Sign in" button
  - sends a JSON POST with `X-XSRF-TOKEN` taken from the `__Host-XSRF-TOKEN` cookie
  - shows the Problem Details `detail` text on error
- [ ] Both files start with the comment "TEMPORARY until the frontend is chosen (tech-stack Q-002)"
- [ ] Response headers for `/auth/**`: `Content-Security-Policy: default-src 'self'` and `Referrer-Policy: no-referrer`
- [ ] Test-only HTTPS: a self-signed keystore under `src/test/resources`, with `server.ssl.*` in a test profile
- [ ] `TemporaryAuthPagesE2ETest`: the steps in design §9, using Playwright Chromium against Testcontainers PostgreSQL and GreenMail

**Acceptance criteria**
- The E2E test passes as part of `./gradlew clean build`. It covers sign-up, the verification email, setting the password, being signed in, sign-out, requesting a magic link, and the "Sign in" button.
- After loading a page, the token is no longer in the URL.
- **Negative checks:**
  - a submit with `X-XSRF-TOKEN` removed gives `403`
  - opening a magic link without clicking leaves the token unused
- Both CSP and Referrer-Policy headers are present on both pages.

**Likely files**
`src/main/resources/static/auth/{verify-email.html,magic-link.html,auth-pages.js}`,
`main/auth/adapter/in/web/SecurityConfig.java` (headers),
`src/test/resources/{application-e2e.yml,e2e-keystore.p12}`,
`test/auth/adapter/in/web/TemporaryAuthPagesE2ETest.java`

**Verify:** `./gradlew test --tests 'com.taskflow.auth.adapter.in.web.TemporaryAuthPagesE2ETest' && ./gradlew clean build`
