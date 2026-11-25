# Feature: Authentication

> Status: approved
> Source: .ai/sdd/PLAN.md (Phase 1)
> Last Updated: 2026-11-25

## Overview
Users create an account and sign in to TaskFlow Pro. Every other
feature depends on knowing who the user is, so this is the first spec.

## Goals
- A new user can sign up and reach the app in under 1 minute.
- A returning user can sign in with a password or with a magic link.
- A session stays valid without asking the user to sign in again
  every hour.

 ## Non-Goals / Out of Scope
- Social login (Google, GitHub) — needs a privacy review first.
- Two-factor authentication — v2.
- Account deletion and data export — separate spec.
- Roles and permissions — belong to 002-workspaces, not to auth.
- Password change and password reset — v2. In the MVP, the magic link
  is the recovery path.
- Sign out of all devices — v2.

## User Stories

### US-001: Sign up with my email
**As a** new user
**I want to** create an account with my email and then choose a password
**So that** I can start using TaskFlow Pro

**Acceptance Criteria:**
- [ ] After entering my email, I am asked to check my email.
- [ ] Opening the link lets me choose a password, then signs me in.
- [ ] A password shorter than 8 characters is rejected with a clear message.
- [ ] Entering an email that already exists shows the same
      "check your email" message.

### US-002: Sign in with a password
**As a** returning user
**I want to** sign in with my email and password
**So that** I can get back to my workspaces

**Acceptance Criteria:**
- [ ] A wrong email or a wrong password shows the same message.
- [ ] After 5 failed attempts in 15 minutes, sign-in is blocked for
      15 minutes.

### US-003: Sign in with a magic link
**As a** returning user
**I want to** receive a sign-in link by email
**So that** I do not need to remember a password

**Acceptance Criteria:**
- [ ] The link works once and expires after 15 minutes.
- [ ] Requesting a link for an unknown email shows the same message
      as for a known one.


### US-004: Stay signed in
**As a** signed-in user
**I want to** stay signed in while I use the app
**So that** I am not interrupted every hour

**Acceptance Criteria:**
- [ ] I stay signed in for up to 30 days of activity.
- [ ] Signing out ends my session on this device.

## Functional Requirements

### FR-001: Start sign-up — Must Have (US-001)
WHEN a visitor submits a valid email, THE SYSTEM SHALL respond
"Check your email to continue."
IF no account exists for that email, THE SYSTEM SHALL create an
unverified account with no password and send a verification link.
IF an unverified account exists for that email, THE SYSTEM SHALL send
a new verification link (FR-009 limits apply). This does not reset the
7-day deletion period (FR-013).
THE SYSTEM SHALL NOT accept a password at this step.

### FR-002: Existing verified email on sign-up — Must Have (US-001)
IF the email belongs to a verified account, THE SYSTEM SHALL return the
same response as FR-001, SHALL NOT change the account, and SHALL send
the owner a warning email — at most one per address per 24 hours.

### FR-003: Password sign-in — Must Have (US-002)
WHEN a user submits a correct email and password, THE SYSTEM SHALL
start a session.
IF the email or the password is wrong, THE SYSTEM SHALL return one
generic error that does not say which one was wrong.

### FR-004: Brute-force protection — Must Have (US-002)
IF an email has 5 failed password attempts within 15 minutes,
THE SYSTEM SHALL reject password sign-in for that email for 15 minutes
with "Too many attempts. Try again in 15 minutes."
This counter is kept for every submitted email: registered or not,
verified or not.
WHEN a sign-in succeeds, THE SYSTEM SHALL reset the counter.

### FR-005: Request magic link — Must Have (US-003)
WHEN a user requests a magic link, THE SYSTEM SHALL show the same
confirmation for every email, and, if a verified account exists and fewer than
3 links were requested in the last 15 minutes, SHALL send a single-use
link valid for 15 minutes. A new link invalidates all older unused links.

### FR-006: Use magic link — Must Have (US-003)
WHEN a user opens a valid, unused magic link and confirms on the page,
THE SYSTEM SHALL start a session and mark the link as used.
IF the link is expired or already used, THE SYSTEM SHALL reject it.

### FR-007: Session lifetime — Must Have (US-004)
WHILE a user makes at least one authenticated request every 30 days,
THE SYSTEM SHALL keep the session valid, but never longer than 90 days
after sign-in.

### FR-008: Sign out — Must Have (US-004)
WHEN a user signs out, THE SYSTEM SHALL end the session on that device
so it cannot be reused.

### FR-009: Verify email and set password — Must Have (US-001)
WHEN a user opens a valid, unused verification link and submits a valid
password, THE SYSTEM SHALL set that password, mark the account as
verified, and start a session.
A verification link is single-use and valid for 60 minutes.
A new verification link invalidates all older unused ones.
At most 3 verification links are sent per email per 15 minutes.
IF the link is expired or already used, THE SYSTEM SHALL reject it:
for a verified account it SHALL say "Sign in instead";
for an unverified account it SHALL say "Sign up again to get a new link."

### FR-010: (Removed)
Not needed: unverified accounts have no password since FR-001 changed.

### FR-011: (Removed)
Not needed: signing up again only sends a new verification link (FR-009).

### FR-012: (Removed)
Not needed: unverified accounts have no password to delete.

### FR-013: Expire unverified accounts — Should Have (US-001)
THE SYSTEM SHALL delete accounts that are not verified within 7 days
of sign-up.

## Non-Functional Requirements

### NFR-001: Password storage
Passwords SHALL be stored only as a slow, salted hash.
A plain-text password SHALL never be stored or logged.

### NFR-002: Response time
Sign-up and sign-in SHALL respond within 500 ms at p95,
excluding email delivery.

### NFR-003: Token secrecy
Magic-link tokens and session secrets SHALL never appear in logs,
URLs of error pages, or API error responses.

### NFR-004: No timing leaks
Sign-up, password sign-in and magic-link requests SHALL take about the
same time whether the email exists or not, so response time does not
reveal which accounts exist.

## Implementation FAQ

### Q-001: What happens when someone signs up with an existing email?
The API returns the same "Check your email to continue" response (FR-002).
The owner gets a warning email, at most once per 24 hours:
"Someone tried to create an account with your address. If it was you,
sign in instead."

### Q-002: Are emails case-sensitive?
No. Emails are trimmed and stored in lowercase.
`Ali@Mail.com` and `ali@mail.com` are the same account.

### Q-003: Does a magic link sign-in also count toward brute-force limits?
No. FR-004 applies only to password attempts. Magic-link requests are
limited separately: at most 3 requests per email per 15 minutes.

### Q-004: How does a user recover access without a password reset?
They request a magic link (FR-005). Password reset is v2.

### Q-005: Can a user sign in with a magic link before verifying the email?
No. Magic links are sent only to verified accounts. An unverified user
completes sign-up through the verification link (FR-009).

### Q-006: Are passwords trimmed?
No. A password is stored exactly as typed. Only a password made of
whitespace alone is rejected.

### Q-007: How is session revocation (FR-008) implemented?
Not decided here. This is a design decision (see design.md, TD-xxx).

### Validation examples

| Input | Expected result |
|-------|-----------------|
| email `""` | Error: "Email is required" |
| email `"ali"` | Error: "Email is invalid" |
| email `"ali veli@mail.com"` | Error: "Email is invalid" |
| email `" Ali@Mail.com "` | Accepted, stored as `ali@mail.com` (Q-002) |
| password `"1234567"` (7 chars) | Error: "Password must be at least 8 characters" |
| password `"12345678"` (8 chars) | Accepted |
| password of 129 chars | Error: "Password must be 128 characters or fewer" |
| password `" abcdefgh"` (leading space) | Accepted as typed, 9 characters (Q-006) |
| password `"        "` (8 spaces) | Error: "Password must not be blank" |

## Confirm Before Building
Before writing any design or code, restate FR-001 to FR-013 in your
own words and list any question this document does not answer.