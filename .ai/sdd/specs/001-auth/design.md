# Design: Authentication

> Status: Draft
> Spec: 001-auth
> Requirements: [requirements.md](requirements.md)
> Last Updated: 2026-11-25

## 1. Overview

The `auth` module lets people create an account (email first, password
after verification), sign in with a password or a magic link, and keep a
server-side session for up to 30 days of activity (never more than 90).
It gives every other module the signed-in `UserId` through Spring Security.

Main design choices:
- Sessions are random tokens stored on the server, sent in a cookie (TD-001, TD-002).
- Sign-up and magic-link requests are queued and handled in the
  background, so their response time does not depend on whether the
  email exists (TD-006).
- All email goes through an outbox table with retries (TD-007).
- Link tokens travel in the URL fragment and are used up only by an
  explicit POST, so mail scanners cannot burn them (TD-008).

## 2. Module structure

```
com.taskflow.shared.domain/
└── UserId                              # record; shared ID (conventions: Shared IDs)

com.taskflow.auth/
├── domain/
│   ├── User                            # aggregate root
│   ├── Session                         # entity inside User
│   ├── LinkToken                       # entity inside User (VERIFY_EMAIL, MAGIC_LINK)
│   ├── LinkPurpose                     # enum
│   ├── SignInThrottle                  # aggregate root, keyed by EmailAddress
│   ├── EmailAddress                    # value object (TD-010)
│   ├── RawPassword                     # value object (TD-010)
│   ├── PasswordHash                    # value object, opaque encoded string
│   ├── TokenHash                       # value object, SHA-256 of a raw token
│   ├── RawToken                        # value object, 256-bit random, redacted toString
│   ├── SessionId, LinkTokenId          # records
│   └── AuthPolicy                      # constants: 15 min, 60 min, 30 d, 90 d, 3, 5, 7 d, 24 h
├── application/
│   ├── port/in/
│   │   ├── StartSignUpUseCase
│   │   ├── VerifyEmailUseCase
│   │   ├── SignInWithPasswordUseCase
│   │   ├── RequestMagicLinkUseCase
│   │   ├── SignInWithMagicLinkUseCase
│   │   ├── SignOutUseCase
│   │   ├── AuthenticateSessionUseCase       # used by the security filter
│   │   ├── ProcessAuthRequestsUseCase       # TD-006 worker
│   │   ├── DeliverPendingEmailsUseCase      # TD-007 sender
│   │   └── CleanUpAuthDataUseCase           # TD-012 job
│   ├── port/out/
│   │   ├── UserPersistencePort
│   │   ├── SessionQueryPort                 # read + touch, hot path (TD-003, TD-004)
│   │   ├── SignInThrottlePersistencePort
│   │   ├── AuthRequestQueuePort
│   │   ├── EmailOutboxPort
│   │   ├── EmailSenderPort
│   │   ├── PasswordHasherPort
│   │   └── TokenGeneratorPort
│   └── service/                             # one service per use case
└── adapter/
    ├── in/web/
    │   ├── SignUpController, EmailVerificationController,
    │   │   MagicLinkController, SessionController
    │   ├── SessionAuthenticationFilter      # TD-013
    │   ├── SecurityConfig                   # TD-013, TD-015
    │   └── AuthProblemHandler               # RFC 9457 mapping (TD-014)
    ├── in/scheduling/
    │   ├── AuthRequestWorker                # TD-006
    │   ├── EmailOutboxJob                   # TD-007
    │   └── AuthCleanupJob                   # TD-012
    ├── out/persistence/                     # JPA entities, repositories, native SQL
    └── out/email/
        └── SmtpEmailSender                  # Spring Mail (TD-007)

src/main/resources/
├── db/migration/V1__create_auth_tables.sql  # TD-011
└── static/auth/                             # TEMPORARY pages (TD-008)
    ├── verify-email.html
    └── magic-link.html
```

`AuthPolicy` holds the numbers from the requirements as constants, not
configuration, so they cannot drift from the spec. Only infrastructure is
configurable: SMTP settings and `taskflow.auth.base-url` (used to build links).

## 3. Domain model

### `User` (aggregate root)
| Field | Notes |
|-------|-------|
| `id: UserId` | |
| `email: EmailAddress` | unique |
| `passwordHash: PasswordHash?` | null until verified |
| `verifiedAt: Instant?` | null until verified |
| `createdAt: Instant` | FR-013 clock starts here; not reset by re-sign-up |
| `lastSignUpWarningAt: Instant?` | FR-002, at most one per 24 h |
| `linkTokens: List<LinkToken>` | issued tokens of both purposes |
| `sessions: List<Session>` | |
| `version: long` | optimistic locking |

Behaviour (all invariants live here):
- `register(email, now)`: a new, unverified user with no password.
- `issueVerificationToken(tokenHash, now) → Issued | RateLimited`:
  only while unverified. Refuses when 3 tokens of this purpose were issued in the
  last 15 min. Invalidates older unused verification tokens (FR-009).
- `issueMagicLinkToken(tokenHash, now) → Issued | RateLimited`: only
  while verified. The same limit (3 / 15 min) and invalidation rule apply (FR-005).
- `recordSignUpAttempt(now) → SendWarning | Skip`: only while verified.
  Returns `SendWarning` at most once per 24 h (FR-002).
- `checkVerificationToken(tokenHash, now) → Usable | Rejected(reason)`
  and `verifyEmail(tokenHash, passwordHash, now) → Session`: sets the
  password and `verifiedAt`, marks the token used, and starts a session (FR-009).
- `signInWithMagicLink(tokenHash, now) → Session`: marks the token used and
  starts a session (FR-006).
- `startSession(sessionId, tokenHash, now) → Session`: used by password sign-in (FR-003).
- `endSession(sessionId)`: FR-008.

A `LinkToken` is usable when `usedAt == null && invalidatedAt == null && now < expiresAt`.

### `Session` (entity inside `User`)
`id`, `tokenHash`, `createdAt`, `lastSeenAt`.
`isActiveAt(now)` is `now < lastSeenAt + 30 d && now < createdAt + 90 d` (FR-007).
`needsTouch(now)` is `now >= lastSeenAt + 1 h` (TD-003).

### `SignInThrottle` (aggregate root)
`email`, `recentFailures: List<Instant>` (at most 5), `lockedUntil: Instant?`.
- `beginAttempt(now) → Allowed | Locked(retryAfter)`: if locked, it refuses.
  Otherwise it records the attempt as a failure up front (TD-009). On the
  5th failure inside 15 min it sets `lockedUntil = now + 15 min`.
- `reset()`: called after a successful sign-in (FR-004).

## 4. Data model (`V1__create_auth_tables.sql`)

All timestamps are `timestamptz` in UTC (principle 6). Token values are
never stored, only their SHA-256 hash (`bytea`, 32 bytes).

**`users`**
| Column | Type | Constraint |
|--------|------|------------|
| `id` | `uuid` | PK |
| `email` | `text` | `NOT NULL UNIQUE` (already normalized, TD-010) |
| `password_hash` | `text` | nullable |
| `verified_at` | `timestamptz` | nullable |
| `created_at` | `timestamptz` | `NOT NULL` |
| `last_sign_up_warning_at` | `timestamptz` | nullable |
| `version` | `bigint` | `NOT NULL` |

`CHECK ((verified_at IS NULL) = (password_hash IS NULL))`: an account is
verified exactly when it has a password.

**`link_tokens`**: `id uuid PK`, `user_id uuid NOT NULL FK → users ON DELETE CASCADE`,
`purpose text NOT NULL CHECK (purpose IN ('VERIFY_EMAIL','MAGIC_LINK'))`,
`token_hash bytea NOT NULL UNIQUE`, `created_at`, `expires_at`, `used_at`, `invalidated_at`.
Index on `(user_id, purpose, created_at)` for the rate-limit count.

**`sessions`**: `id uuid PK`, `user_id uuid NOT NULL FK → users ON DELETE CASCADE`,
`token_hash bytea NOT NULL UNIQUE`, `created_at timestamptz NOT NULL`,
`last_seen_at timestamptz NOT NULL`.

**`sign_in_throttles`**: `email text PK`, `recent_failures timestamptz[] NOT NULL`,
`locked_until timestamptz`, `updated_at timestamptz NOT NULL`.

**`auth_requests`** (TD-006): `id uuid PK`,
`kind text NOT NULL CHECK (kind IN ('SIGN_UP','MAGIC_LINK'))`, `email text NOT NULL`,
`created_at`, `attempts int NOT NULL DEFAULT 0`, `next_attempt_at timestamptz NOT NULL`.

**`email_outbox`** (TD-007): `id uuid PK`, `recipient text NOT NULL`,
`template text NOT NULL`, `body text` (cleared after sending), `created_at`,
`attempts int NOT NULL DEFAULT 0`, `next_attempt_at timestamptz NOT NULL`,
`sent_at timestamptz`, `failed_at timestamptz`.

## 5. Flows

### Start sign-up (FR-001, FR-002)
1. `POST /api/v1/sign-ups {email}`. `EmailAddress` validates the email (`400` on error).
2. Insert an `auth_requests` row with kind `SIGN_UP`, then return `202 "Check your email to continue."`
3. The worker (TD-006) loads the user by email:
   - **No user:** `User.register`, then `issueVerificationToken`, then an outbox
     row with the verification link. A concurrent duplicate insert is caught by
     the unique constraint and handled as "unverified user exists".
   - **Unverified user:** `issueVerificationToken`. If it is rate-limited, nothing is sent.
   - **Verified user:** `recordSignUpAttempt`. On `SendWarning`, write an outbox row
     with the warning email (Q-001 text).
   - Everything above runs in one transaction, and the `auth_requests` row is deleted in that same transaction.

### Verify email and set password (FR-009)
1. The user opens `/auth/verify-email.html#token=…`. The page shows a password form.
   Nothing is sent to the server yet.
2. `POST /api/v1/email-verifications {token, password}`.
3. `RawPassword` validates the password (`400`). The token is checked *before*
   the password is hashed. The outcomes are:
   - **Unknown token:** `409`, "Sign up again to get a new link."
   - **Expired, used or invalidated token, account verified:** `409`, "Sign in instead."
   - **Expired, used or invalidated token, account unverified:** `409`, "Sign up again to get a new link."
4. The password is hashed with Argon2id and `user.verifyEmail(...)` runs. The user is saved
   with an optimistic-lock check, so if two submits race, one of them gets
   `409` "Sign in instead".
5. `200`, with the session cookie set.

### Password sign-in (FR-003, FR-004, NFR-004)
1. `POST /api/v1/sessions {email, password}`. Take an Argon2 permit (TD-016). If none
   is free within 1 s: `503`, and nothing is recorded. The permit is released after step 3.
2. **Transaction A:** upsert the `sign_in_throttles` row, `SELECT … FOR UPDATE`,
   then `beginAttempt(now)`, then commit. If locked: `429`, "Too many attempts.
   Try again in 15 minutes.", with `Retry-After`.
3. Load the user (read-only). Check the password against the user's hash, or
   against the dummy hash when there is no user or no password. Exactly one
   Argon2 check runs on every path.
4. **If it fails:** `401 invalid-credentials`. The failure is already counted.
5. **If it succeeds:** in transaction B, `throttle.reset()`. In transaction C,
   `user.startSession(...)` (one aggregate per transaction). Then `201` with the
   cookie and `Location: /api/v1/sessions/current`.

### Request a magic link (FR-005)
1. `POST /api/v1/magic-links {email}`. The email is validated, an `auth_requests` row
   with kind `MAGIC_LINK` is inserted, and the response is `202`.
2. The worker runs `issueMagicLinkToken` only if a verified user exists, then writes an outbox row.
   It does nothing for an unknown or unverified email, or when the request is rate-limited.

### Sign in with a magic link (FR-006)
1. The user opens `/auth/magic-link.html#token=…`. The page shows a "Sign in" button.
2. `POST /api/v1/sessions {magicLinkToken}`, then `user.signInWithMagicLink(...)`.
   An unknown, expired, used or invalidated token gets `409 link-invalid`:
   "This link is no longer valid. Request a new one."
3. `201` with the cookie.

### Authenticated request (FR-007)
1. `SessionAuthenticationFilter` reads the `__Host-session` cookie, hashes it, and calls
   `AuthenticateSessionUseCase`.
2. `SessionQueryPort.findByTokenHash`, then `Session.isActiveAt(now)`.
   If the session is not active, the response is `401` and the cookie is cleared.
3. If `needsTouch(now)` is true: `UPDATE sessions SET last_seen_at = :now
   WHERE id = :id AND last_seen_at < :now - interval '1 hour'`.
4. The principal is `AuthenticatedUser(UserId, SessionId)`.

### Sign out (FR-008)
`DELETE /api/v1/sessions/current` calls `user.endSession(sessionId)`, which deletes the row.
The response is `204`, and the cookie is cleared (`Max-Age=0`).

## 6. REST API

| Method | Path | Body | Success | Errors |
|--------|------|------|---------|--------|
| `POST` | `/api/v1/sign-ups` | `{email}` | `202` | `400` |
| `POST` | `/api/v1/email-verifications` | `{token, password}` | `200` + cookie | `400`, `409 link-invalid`, `503 temporarily-busy` |
| `POST` | `/api/v1/magic-links` | `{email}` | `202` | `400` |
| `POST` | `/api/v1/sessions` | `{email, password}` | `201` + cookie + `Location` | `400`, `401 invalid-credentials`, `429 too-many-attempts`, `503 temporarily-busy` |
| `POST` | `/api/v1/sessions` | `{magicLinkToken}` | `201` + cookie + `Location` | `400`, `409 link-invalid` |
| `DELETE` | `/api/v1/sessions/current` | — | `204` | `401` |

The two `POST /api/v1/sessions` bodies are two credential types for one resource.
A body with both or neither is a `400` error.

Error `type`s: `https://taskflow.pro/errors/{validation, invalid-credentials,
too-many-attempts, link-invalid, temporarily-busy}`. `link-invalid` carries the exact FR-009 / FR-006
message in `detail`. Validation errors use the messages from the requirements'
validation table. No error ever repeats a token or a password (NFR-003).

## 7. Technical decisions

### TD-001: Sessions are random tokens stored on the server, not JWTs
**Decision:** A session is a 256-bit token from `SecureRandom`, encoded as base64url.
Only `SHA-256(token)` is stored in `sessions`. Every authenticated request
looks the session up by that hash. Signing out deletes the row.
**Serves:** FR-007, FR-008, NFR-003, Q-007
**Rejected:**
- *A JWT that expires quickly, plus a refresh token.* After sign-out the JWT
  keeps working until it expires, which breaks FR-008 ("cannot be reused").
- *A JWT plus a list of revoked tokens.* It needs a database lookup on every
  request anyway, plus signing-key management, so the JWT adds nothing.
- *Spring Session JDBC.* It only has an inactivity timeout, with no 90-day
  limit, and it puts session rules outside the domain (principle 2).

### TD-002: The session token is sent in a `__Host-` cookie
**Decision:** The cookie is `__Host-session=<token>; HttpOnly; Secure; SameSite=Strict; Path=/`,
and `Max-Age` is the time left until the 90-day limit. The server enforces the 30-day
inactivity rule. The WebSocket handshake (spec 005) sends the cookie automatically.
**Serves:** FR-007, FR-008, NFR-003
**Rejected:**
- *A bearer token in `localStorage`.* Any XSS could read it.
- *`SameSite=Lax`.* Strict costs nothing here, because every API call comes from our own pages.
**Assumption:** the frontend and the API are served from the same site
(tech-stack Q-002 is still open).

### TD-003: `last_seen_at` is written at most once an hour
**Decision:** The session's `last_seen_at` is updated only when the stored value is more
than an hour old. The update is one conditional `UPDATE` through `SessionQueryPort`, and
it does not change `users.version`. Session rows are written by the `User`
persistence mapping only when they are inserted or deleted, never updated. So saving a `User`
cannot overwrite a newer `last_seen_at`.
**Serves:** FR-007, NFR-002
**Rejected:** *Writing on every request.* That's needless write load. The 30-day window stays
accurate to within an hour, which is enough.

### TD-004: `User` contains sessions and link tokens. `SignInThrottle` is separate
**Decision:** `User` holds credentials, verification state, link tokens,
rate-limit history and sessions, as the conventions table says. Checking each request
reads only one session through `SessionQueryPort`. The validity rule stays in
the domain (`Session.isActiveAt`). `SignInThrottle` is its own aggregate,
keyed by email.
**Serves:** FR-004, FR-005, FR-006, FR-009, conventions (one aggregate per transaction)
**Rejected:**
- *`Session` as its own aggregate.* Verifying an email or signing in with a magic link
  would then change two aggregates in one transaction, and the conventions table puts
  sessions inside `User`.
- *The throttle inside `User`.* FR-004 also counts emails with no account, and those have no `User`.

### TD-005: Passwords are hashed with Argon2id
**Decision:** Use Spring Security's `Argon2PasswordEncoder` (it needs BouncyCastle)
with m = 19 MiB, t = 2, p = 1 (the OWASP baseline). The time is measured on the target
container and must stay under about 200 ms. The stored hash string includes its
parameters. If the parameters are raised later, the password is rehashed on the next
successful sign-in. The domain sees only `PasswordHash`, and hashing goes through
`PasswordHasherPort`.
**Serves:** NFR-001, NFR-002
**Rejected:**
- *bcrypt.* It ignores everything after 72 bytes, so part of a 128-character password
  would be ignored. The usual workaround, hashing the password first, is easy to get wrong.
- *PBKDF2.* It isn't memory-hard.
- *scrypt.* It's acceptable, but Argon2id is OWASP's first choice.

### TD-006: Response time does not depend on whether the email exists
**Decision:**
- **Password sign-in:** if there is no user, or the user has no password, the password is
  checked against a dummy Argon2id hash. The dummy is computed at startup with the same
  parameters, so every path runs exactly one Argon2 check.
- **Sign-up and magic-link requests:** after the format check, the endpoint inserts one
  `auth_requests` row and returns `202`. `AuthRequestWorker` polls every second and claims
  rows with `FOR UPDATE SKIP LOCKED`. It does the lookup and all the branching (create
  the account, send a link, send a warning, or do nothing) in one transaction per row. A
  row that fails is retried with backoff, and every failure is logged.
- **Lockout response (`429`):** it is returned the same way for every email, because
  `SignInThrottle` exists for registered and unregistered emails alike.
**Serves:** NFR-004, NFR-002, FR-001, FR-002, FR-005
**Rejected:**
- *Padding every response to a fixed delay.* It slows every request, can still leak under
  load, and holds threads.
- *Branching inside the request and only sending email asynchronously.* "Create a user"
  and "do nothing" still take measurably different times.

### TD-007: Email is sent through an outbox table
**Decision:**
- The domain change and its `email_outbox` row are written in the same transaction.
- `EmailOutboxJob` polls every 2 s and claims rows with `FOR UPDATE SKIP LOCKED`.
  It sends through `EmailSenderPort`, implemented by `SmtpEmailSender` with Spring Mail.
- After a send, `body` is set to `NULL` and `sent_at` is set, so the raw link token
  doesn't stay in the database.
- If a send fails, `attempts` goes up and `next_attempt_at` uses exponential backoff
  (from 30 s, capped at 1 h). After 10 attempts the row gets `failed_at` and an `ERROR`
  log (principle 5).
- Logs name the template and the outbox ID only, never the body or the link.
- Email text is plain text and in English. Mailpit is used locally and GreenMail in tests.
**Serves:** FR-001, FR-002, FR-005, FR-009, NFR-002, NFR-003
**Rejected:**
- *Sending inside the request.* It's slow, leaks timing, and the email is lost if
  the mail server is down.
- *`@Async` or an after-commit event with no outbox.* The email is lost on a crash and never retried.

### TD-008: Link tokens are in the URL fragment and used up only by a POST
**Decision:**
- Each link token is 256 random bits, and only its hash is stored in `link_tokens`.
  Verification links are valid for 60 min and magic links for 15 min.
- Links are `{base-url}/auth/verify-email.html#token=…` and
  `{base-url}/auth/magic-link.html#token=…`. Browsers never send the fragment to the
  server, so the token can't appear in access logs, the `Referer` header, proxies, or
  the URLs of error pages.
- The page reads the fragment, removes it from the address bar with
  `history.replaceState`, and sends the token in a JSON POST body only after the user
  acts: submitting the password form, or clicking "Sign in". A GET never changes
  anything, so a mail scanner that opens the link cannot use it up (FR-006: "confirms on the page").
- **TEMPORARY:** the two pages are minimal static HTML with plain JS in
  `src/main/resources/static/auth/`. They are served by Spring with
  `Content-Security-Policy: default-src 'self'` and `Referrer-Policy: no-referrer`.
  Both files carry a header comment: "Temporary until the frontend is chosen
  (tech-stack Q-002)". They will be replaced by the real frontend.
**Serves:** FR-006, FR-009, NFR-003
**Rejected:**
- *The token in the query string.* It lands in logs, the `Referer` header and proxies.
- *A GET that uses up the link.* Scanners would use it up before the user clicks.
- *A short code the user types in.* It's worse UX, and the requirements don't ask for it.

### TD-009: Failed sign-ins are counted before the password check, under a row lock
**Decision:**
- There is one `sign_in_throttles` row per normalized email. It is created with
  `INSERT … ON CONFLICT DO NOTHING`, then locked with `SELECT … FOR UPDATE`.
- `beginAttempt` records the attempt as a failure *before* the password is checked, and
  the transaction commits right away. So the lock is not held while Argon2 runs, and
  parallel requests still cannot get past 5. The 6th request sees 5 recorded failures
  and is refused.
- A successful sign-in resets the row.
- The last 5 failure timestamps give an exact 15-minute sliding window.
**Serves:** FR-004, NFR-004
**Rejected:**
- *Counting after the check without a lock.* N parallel guesses all get past the limit.
- *Holding the lock while the hash is checked.* It's correct, but it makes requests for the
  same email wait in line for no extra safety.
- *An in-memory limiter (Bucket4j or Caffeine).* It's lost on restart and wrong with more
  than one instance.
- *A per-IP limit.* FR-004 doesn't ask for one.

### TD-010: Email and password are value objects that validate themselves
**Decision:**
- **`EmailAddress`:** `null` or blank gives "Email is required". The input is trimmed and
  lowercased with `Locale.ROOT`. A practical syntax check follows: exactly one `@`, a
  non-empty part before it, a domain with at least one dot and no empty labels, and at
  most 254 characters. Anything else gives "Email is invalid".
- **`RawPassword`:** the checks run in this order:
  1. `null`, empty or whitespace-only (`String.isBlank()`) gives "Password must not be blank".
  2. Fewer than 8 Unicode code points gives "Password must be at least 8 characters".
  3. More than 128 code points gives "Password must be 128 characters or fewer".
  The value is never trimmed (Q-006). `toString()` returns `"RawPassword[***]"`, and the
  value is never stored.
- `RawToken` hides its value in `toString()` the same way.
**Serves:** FR-001, FR-009, Q-002, Q-006, NFR-001, NFR-003
**Rejected:**
- *Lowercasing with the default locale.* On a Turkish-locale JVM, `"I"` becomes `"ı"`,
  which would create a different account.
- *Counting UTF-16 characters.* An emoji would count as 2.
- *A full RFC 5322 regex.* It accepts addresses that mail providers reject.
- *Validating only with `@Email` on the request DTO.* The rule would then live only in the
  controller (principle 2).

### TD-011: The database enforces the core rules
**Decision:** One Flyway migration, `V1__create_auth_tables.sql`, creates the schema
in §4. The key constraints are:
- `users.email UNIQUE`, so a second account is impossible (FR-002).
- `CHECK ((verified_at IS NULL) = (password_hash IS NULL))`.
- `token_hash UNIQUE` on tokens and sessions.
- `ON DELETE CASCADE` from users to tokens and sessions (FR-013).

`spring.jpa.hibernate.ddl-auto=validate`. Time comes from an injected `java.time.Clock` bean.
**Serves:** FR-001, FR-002, FR-009, FR-013, principles 2 and 6
**Rejected:**
- *`citext` for the email column.* `EmailAddress` already normalizes the email, and
  `citext` needs a database extension.
- *Soft-delete flags.* FR-013 asks for deletion.

### TD-012: An hourly cleanup job
**Decision:** `AuthCleanupJob` (`@Scheduled`, hourly) calls `CleanUpAuthDataUseCase`, which deletes:
- unverified users with `created_at < now − 7 d`, whose tokens and sessions go with them (FR-013)
- sessions that are no longer active
- link tokens older than 24 h, which keeps the 15-minute rate-limit window intact
- throttle rows not updated for 15 min and not locked
- outbox rows sent more than 24 h ago

Each step is one plain SQL `DELETE`, safe to run twice or on several instances,
so no locking library is needed.
**Serves:** FR-013, FR-007
**Rejected:**
- *`pg_cron`.* It needs a database extension, and the logic would sit outside the app and its tests.
- *Deleting only when a record is accessed.* FR-013 requires deletion even when nobody touches the account.
- *ShedLock.* The deletes are idempotent, so running them twice is harmless.

### TD-013: Spring Security, and how other modules get the signed-in user
**Decision:**
- `SecurityConfig` sets up a stateless filter chain, with no `HttpSession` and no form login.
- `SessionAuthenticationFilter` turns the cookie into an `AuthenticatedUser(UserId, SessionId)` principal.
- These paths are open without sign-in: `POST /api/v1/sign-ups`,
  `POST /api/v1/email-verifications`, `POST /api/v1/magic-links`,
  `POST /api/v1/sessions` and `/auth/**` (the static pages). Everything else under
  `/api/v1/**` requires sign-in, and an unauthenticated request gets `401` Problem Details.
- Other modules get the `UserId` with `@AuthenticationPrincipal` and import only
  `com.taskflow.shared.domain.UserId`, never anything from `auth`.
**Serves:** FR-003, FR-006, FR-007, FR-008, conventions (shared IDs, module boundaries)
**Rejected:**
- *Spring Security `formLogin` with `HttpSession`.* Session rules would live outside
  the domain, and the cookie would be `JSESSIONID`.
- *An OAuth2 resource server.* It's JWT-based (see TD-001).

### TD-014: REST API shape
**Decision:** The endpoints are as in §6.
- Sign-up and magic-link requests return `202`, because nothing the caller can observe should change.
- Sessions are a resource: creating one returns `201` with `Location: /api/v1/sessions/current`,
  and signing out is `DELETE` on it.
- Errors use the conventions status table: `400`, `401`, `409` (link no longer usable,
  a business rule) and `429` with `Retry-After`.
- `AuthProblemHandler` maps domain outcomes to RFC 9457 Problem Details.
**Serves:** FR-001 to FR-009, NFR-003
**Rejected:**
- *`201` for sign-up.* It would say that an account was created.
- *Separate `/magic-link-sessions` and `/password-sessions` resources.* Both create the
  same kind of session.
- *`410 Gone` for used links.* It isn't in the conventions table, and `409` fits
  "the link is no longer usable".

### TD-015: CSRF protection stays on (double-submit with a `__Host-` cookie)
**Decision:** Spring Security CSRF protection stays on for every state-changing request,
including the endpoints that work without sign-in.
- It uses the single-page-app setup (`csrf.spa()`). The CSRF token is put in a readable
  cookie named `__Host-XSRF-TOKEN` (`Secure; Path=/; SameSite=Strict`).
- Pages send the token back in the `X-XSRF-TOKEN` header.
- The cookie is set on page load, so the temporary pages (TD-008) work without an extra call.
- The JSON endpoints also reject any `Content-Type` other than `application/json` (`415`).

**Why `SameSite=Strict` alone is not enough:**
1. **Same-site but not same-origin.** SameSite treats every subdomain of the registrable
   domain as the same site. A compromised or user-content subdomain (marketing site,
   docs, a future status page) could send requests that carry the session cookie.
2. **Login CSRF.** Sign-in and verification run before any session exists, so the session
   cookie's SameSite setting does not protect them. A cross-site page could make a form
   POST that signs the victim into the attacker's account. Requiring JSON blocks plain
   forms. The CSRF token blocks the rest.
3. **Defence in depth.** Older or embedded browsers may ignore SameSite.

The CSRF cookie uses the `__Host-` prefix so that a subdomain cannot overwrite it with a
value it knows ("cookie tossing"). Without that prefix, the double-submit check could be beaten.
**Serves:** FR-003, FR-006, FR-008, FR-009, NFR-003
**Rejected:**
- *Turning CSRF off and relying only on `SameSite=Strict`.* It fails in cases 1–3 above.
  The cost of keeping CSRF on is one header per request.
- *A synchronizer token stored in `HttpSession`.* The app is stateless, and the endpoints
  that work without sign-in have no server-side session to store it in.
- *Checking only a custom header (e.g. `X-Requested-With`).* It's weaker against
  same-site attackers, and Spring Security has no standard support for it.

**Note for spec 005:** a WebSocket handshake is a GET, which CSRF tokens do not cover.
The handshake must check the `Origin` header. That will be decided in 005.

### TD-016: Limit how many Argon2 operations run at once
**Decision:**
- Every Argon2 operation goes through one shared, fair `java.util.concurrent.Semaphore`
  inside the `PasswordHasherPort` adapter. That covers hashing a new password (FR-009),
  checking a password (FR-003), the dummy check (TD-006) and the rehash in TD-005.
- The default is 4 permits, set by `taskflow.auth.argon2.max-concurrent`. This is
  infrastructure configuration, not a requirement constant. 4 permits × 19 MiB is about
  76 MiB at most, instead of about 1.9 GB for 100 parallel sign-ins. Raise it only
  together with the container's memory limit (see §11, R-1).
- **When all permits are taken:** the request waits for a permit up to **1 s**
  (`tryAcquire(1, SECONDS)`). If none frees up, it gets
  `503 Service Unavailable` with `Retry-After: 1` and Problem Details type
  `https://taskflow.pro/errors/temporarily-busy`: "Too many sign-ins right now.
  Try again in a moment."
- **Order in password sign-in:** the permit is taken *before* transaction A in the
  password sign-in flow (§5), and released after the hash check. So a `503` never
  records a failed attempt in `SignInThrottle`. Load can't lock a user out, and the
  TD-009 count stays "attempts that were actually checked".
- **Order in verification (FR-009):** the permit is taken after the token check and before
  the password is hashed. A `503` leaves the token unused, so the user can submit again.
- **Timing (NFR-004):** every path, including the dummy check, takes a permit the same way,
  so waiting for a permit doesn't depend on whether the email exists.
- **Monitoring:** the number of waiting requests and of `503`s is exposed as a Micrometer
  gauge and counter, so the permit count can be tuned.
**Serves:** NFR-002, NFR-004, FR-003, FR-009, R-1
**Rejected:**
- *No limit.* A burst of sign-ins, or an attacker sending many, can exhaust memory and
  crash the instance (OOM), which signs everyone out of service.
- *Waiting without a time limit.* Requests pile up, threads are held, and NFR-002 is
  missed for everyone. Failing fast with `Retry-After` is clearer.
- *Rejecting immediately when all permits are taken.* Ordinary short bursts would fail
  for no reason. A 1 s wait absorbs them and still fits the 500 ms p95 target under
  normal load.
- *A dedicated thread pool for hashing.* It limits concurrency just as well, but adds a
  handoff and a second queue to tune. A semaphore is enough.
- *Lowering Argon2 memory instead.* It weakens NFR-001 for every user to handle a
  load case.

## 8. Requirement traceability

| Requirement | Technical decisions | Main components |
|-------------|---------------------|-----------------|
| FR-001 | TD-006, TD-007, TD-010, TD-011, TD-014 | `StartSignUpUseCase`, `AuthRequestWorker`, `User.register` |
| FR-002 | TD-006, TD-007, TD-011 | `User.recordSignUpAttempt` |
| FR-003 | TD-001, TD-005, TD-006, TD-013, TD-016 | `SignInWithPasswordUseCase` |
| FR-004 | TD-004, TD-009 | `SignInThrottle` |
| FR-005 | TD-006, TD-007, TD-008 | `User.issueMagicLinkToken` |
| FR-006 | TD-008, TD-014, TD-015 | `User.signInWithMagicLink`, `magic-link.html` |
| FR-007 | TD-001, TD-002, TD-003, TD-012 | `Session.isActiveAt`, `SessionAuthenticationFilter` |
| FR-008 | TD-001, TD-002, TD-014 | `User.endSession` |
| FR-009 | TD-005, TD-008, TD-010, TD-015, TD-016 | `User.verifyEmail`, `verify-email.html` |
| FR-013 | TD-011, TD-012 | `AuthCleanupJob` |
| NFR-001 | TD-005, TD-010 | `PasswordHasherPort`, `RawPassword` |
| NFR-002 | TD-003, TD-005, TD-006, TD-007, TD-016 | Argon2 semaphore |
| NFR-003 | TD-001, TD-007, TD-008, TD-010, TD-014 | `RawToken`, `TokenHash` |
| NFR-004 | TD-006, TD-009, TD-016 | dummy hash, `auth_requests` |

## 9. Testing

Layer types follow conventions.md. These are the key behaviours to cover.

| Layer | Examples |
|-------|----------|
| `domain/` | `EmailAddress` and `RawPassword`: every row of the validation table, plus `"I"` under a Turkish default locale. `User`: 4th token in 15 min is rate-limited, a new token invalidates older ones, an expired / used / invalidated token is rejected, `verifyEmail` sets the password and starts a session, warning only once per 24 h. `Session.isActiveAt`: just before and after 30 d idle and 90 d absolute. `SignInThrottle`: 5th failure locks, sliding window, reset on success. |
| `application/service/` | Sign-in runs one Argon2 check for an unknown email, an unverified user and a wrong password. The throttle is checked before the hash. The `auth_requests` worker covers each branch (new, unverified, verified, rate-limited, unknown). |
| `adapter/out/persistence/` | Unique email. The verified ⇔ password `CHECK`. Cascade on user delete. Saving a `User` does not overwrite a touched `last_seen_at`. `FOR UPDATE SKIP LOCKED` claiming. |
| `adapter/in/web/` | Every endpoint, status and Problem Details body in §6. The sign-up response is identical for new, unverified and verified emails. Parallel wrong-password requests never get past 5. A mail scanner's GET of a link page does not use up the token. A request without a CSRF token is rejected (`403`). Cookie attributes. Sign-out makes the old cookie get `401`. No token or password appears in captured logs. |
| `adapter/in/scheduling/` | Cleanup deletes 7-day-old unverified users and keeps younger ones. The outbox job retries, backs off, marks failed after 10 attempts, and clears `body` after sending. |
| `adapter/out/email/` | `SmtpEmailSender` delivers to GreenMail with the right recipient, subject and link format. |
| Argon2 limit (TD-016) | `application/service/` or adapter unit test with 1 permit: a second concurrent hash waits, then gets `503 temporarily-busy` after 1 s. A `503` on password sign-in records nothing in `SignInThrottle`. A `503` on verification leaves the token unused. |

### End-to-end test of the temporary pages, with CSRF
`TemporaryAuthPagesE2ETest` in `adapter/in/web/` runs the real flow in a real browser
and is part of `./gradlew build`.
- **Tools:** `@SpringBootTest(webEnvironment = RANDOM_PORT)` with Testcontainers
  (PostgreSQL) and GreenMail, driving Chromium through Playwright for Java.
- **HTTPS:** the app runs over HTTPS with a self-signed keystore used only in tests, and
  Playwright ignores certificate errors. This is needed because `__Host-` cookies
  (TD-002, TD-015) must be `Secure`, and browsers handle `Secure` cookies over plain HTTP
  inconsistently.
- **Steps:**
  1. Load `/auth/verify-email.html` once with no token, and check that the
     `__Host-XSRF-TOKEN` cookie is set.
  2. `POST /api/v1/sign-ups` from the page context, with the CSRF header. Read the
     verification link from GreenMail.
  3. Open the link. Check that the token is removed from the address bar. Submit a valid
     password and expect `200` with a `__Host-session` cookie. Call an authenticated
     endpoint and expect a signed-in response.
  4. Sign out, then `POST /api/v1/magic-links`. Read the link from GreenMail, open it, and
     click "Sign in". Expect `201` with a new session cookie.
  5. **Negative checks:**
     - Repeat the verification submit with the `X-XSRF-TOKEN` header removed, and expect
       `403`.
     - Opening a magic link without clicking "Sign in" leaves the token unused.
- **What it proves:** the pages, the CSRF cookie and header, the session cookie
  attributes and the fragment handling work together in a browser, not only in
  MockMvc-style API tests.

### Timing test (NFR-004), kept out of the normal build
`AuthTimingTest` in `adapter/in/web/` compares the median response time for existing
and non-existing emails across many runs, and asserts they are within a tolerance. It
covers sign-up, password sign-in and magic-link requests. It is a guard against
regressions, not a benchmark.
- It is tagged `@Tag("timing")`.
- The `test` task excludes it (`useJUnitPlatform { excludeTags("timing") }`), so
  `./gradlew build` and `./gradlew clean build` never run it.
- It runs only through a separate Gradle task: `./gradlew timingTest`, a `Test` task with
  `includeTags("timing")`. That task isn't wired into `check` or `build`.
- **Why it's separate:** timing results depend on the machine and its load. In the normal
  build they would fail at random and block unrelated work.

## 10. Temporary items and follow-ups
- **TEMPORARY:** `static/auth/verify-email.html` and `static/auth/magic-link.html`
  (TD-008) are to be replaced when the frontend is chosen (tech-stack Q-002).
- TD-002 assumes the frontend is served from the same site as the API. Revisit this
  when Q-002 is decided.
- Spec 005 must check `Origin` on the WebSocket handshake (TD-015).

## 11. Risks

| ID | Risk | Impact | Mitigation | Owner / trigger |
|----|------|--------|------------|-----------------|
| R-1 | **Argon2 memory.** Each hash allocates about 19 MiB on the JVM heap (TD-005). Many parallel sign-ins, real or malicious, can exhaust memory. | The instance runs out of memory and everyone loses service. | TD-016 caps concurrent hashes (default 4, about 76 MiB). Size the container's memory limit as heap + permits × 19 MiB + headroom. Watch the TD-016 waiting and `503` metrics, and raise permits only together with memory. | Before the first deployment: measure hash time and memory on the target container (TD-005). |
| R-2 | **SMTP provider not chosen.** Only SMTP configuration exists (Mailpit locally, GreenMail in tests). | Sign-up (FR-001, FR-009) and magic links (FR-005) can't work in production without a provider. Without SPF/DKIM/DMARC on the sending domain, links land in spam, which breaks the "reach the app in under 1 minute" goal. | `EmailSenderPort` hides the provider, so choosing one is a configuration change. The outbox (TD-007) retries and logs failures. Choose a provider and set up the sending domain's DNS before production. | Decision needed before the first production deployment. |
| R-3 | **Same-site assumption (TD-002).** The design assumes the frontend and the API are served from the same site. The frontend is still undecided (tech-stack Q-002). | If the frontend ends up on a different site, `SameSite=Strict` session and CSRF cookies are not sent, so nothing works. Fixing that with `SameSite=None` and CORS would weaken TD-002 and TD-015. | Prefer serving the frontend on the same site, or behind a reverse proxy on the same origin. If that's not possible, revisit TD-002 and TD-015 before choosing. | When tech-stack Q-002 is decided. |
