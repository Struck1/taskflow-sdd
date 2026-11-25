package com.taskflow.auth.domain;

import com.taskflow.shared.domain.UserId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Aggregate root of the auth module (TD-004). Holds the email, credentials, verification
 * state, link tokens and sessions. All rules about them are enforced here.
 */
public final class User {

    private final UserId id;
    private final EmailAddress email;
    private PasswordHash passwordHash;
    private Instant verifiedAt;
    private final Instant createdAt;
    private Instant lastSignUpWarningAt;
    private final List<LinkToken> linkTokens;
    private final List<Session> sessions;
    private final long version;

    private User(UserId id, EmailAddress email, PasswordHash passwordHash, Instant verifiedAt, Instant createdAt,
                 Instant lastSignUpWarningAt, Collection<LinkToken> linkTokens, Collection<Session> sessions,
                 long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.email = Objects.requireNonNull(email, "email");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        // Same rule as the database CHECK (TD-011): verified exactly when a password is set.
        if ((verifiedAt == null) != (passwordHash == null)) {
            throw new IllegalArgumentException("A user is verified exactly when it has a password");
        }
        if (lastSignUpWarningAt != null && verifiedAt == null) {
            throw new IllegalArgumentException("Only a verified user can have a sign-up warning");
        }
        this.passwordHash = passwordHash;
        this.verifiedAt = verifiedAt;
        this.lastSignUpWarningAt = lastSignUpWarningAt;
        this.linkTokens = new ArrayList<>(Objects.requireNonNull(linkTokens, "linkTokens"));
        this.sessions = new ArrayList<>(Objects.requireNonNull(sessions, "sessions"));
        this.version = version;
    }

    /** FR-001: a new, unverified account with no password. */
    public static User register(UserId id, EmailAddress email, Instant now) {
        return new User(id, email, null, null, now, null, List.of(), List.of(), 0L);
    }

    /** Rebuilds a user from stored state. */
    public static User restore(UserId id, EmailAddress email, PasswordHash passwordHash, Instant verifiedAt,
                               Instant createdAt, Instant lastSignUpWarningAt, Collection<LinkToken> linkTokens,
                               Collection<Session> sessions, long version) {
        return new User(id, email, passwordHash, verifiedAt, createdAt, lastSignUpWarningAt, linkTokens, sessions,
                version);
    }

    /**
     * FR-001, FR-009: a new verification link for an unverified account.
     *
     * @throws IllegalStateException if the account is already verified
     */
    public LinkIssueResult issueVerificationToken(LinkTokenId linkTokenId, TokenHash tokenHash, Instant now) {
        if (isVerified()) {
            throw new IllegalStateException("A verified user cannot get a verification link");
        }
        return issueLink(LinkPurpose.VERIFY_EMAIL, linkTokenId, tokenHash, now);
    }

    /**
     * FR-005: a new magic link for a verified account (Q-005).
     *
     * @throws IllegalStateException if the account is not verified
     */
    public LinkIssueResult issueMagicLinkToken(LinkTokenId linkTokenId, TokenHash tokenHash, Instant now) {
        if (!isVerified()) {
            throw new IllegalStateException("An unverified user cannot get a magic link");
        }
        return issueLink(LinkPurpose.MAGIC_LINK, linkTokenId, tokenHash, now);
    }

    /**
     * FR-002: someone signed up with this verified account's email. The owner is warned at
     * most once per 24 hours, counted from the last warning that was sent.
     *
     * @throws IllegalStateException if the account is not verified
     */
    public SignUpAttemptResult recordSignUpAttempt(Instant now) {
        Objects.requireNonNull(now, "now");
        if (!isVerified()) {
            throw new IllegalStateException("Only a verified user gets a sign-up warning");
        }
        if (lastSignUpWarningAt != null
                && now.isBefore(lastSignUpWarningAt.plus(AuthPolicy.SIGN_UP_WARNING_INTERVAL))) {
            return SignUpAttemptResult.SKIP;
        }
        lastSignUpWarningAt = now;
        return SignUpAttemptResult.SEND_WARNING;
    }

    /**
     * At most {@link AuthPolicy#MAX_LINKS_PER_WINDOW} links of one purpose are issued within
     * a sliding window: links created in {@code (now - window, now]} count. A rate-limited
     * request changes nothing. An issued link invalidates every older unused link of the same
     * purpose.
     */
    private LinkIssueResult issueLink(LinkPurpose purpose, LinkTokenId linkTokenId, TokenHash tokenHash,
                                      Instant now) {
        Objects.requireNonNull(now, "now");
        Instant windowStart = now.minus(AuthPolicy.LINK_RATE_LIMIT_WINDOW);
        long issuedInWindow = linkTokens.stream()
                .filter(token -> token.purpose() == purpose)
                .filter(token -> token.createdAt().isAfter(windowStart))
                .count();
        if (issuedInWindow >= AuthPolicy.MAX_LINKS_PER_WINDOW) {
            return new LinkIssueResult.RateLimited();
        }
        LinkToken issued = LinkToken.issue(linkTokenId, purpose, tokenHash, now);
        linkTokens.stream()
                .filter(token -> token.purpose() == purpose)
                .forEach(token -> token.invalidate(now));
        linkTokens.add(issued);
        return new LinkIssueResult.Issued(issued.id());
    }

    public boolean isVerified() {
        return verifiedAt != null;
    }

    public UserId id() {
        return id;
    }

    public EmailAddress email() {
        return email;
    }

    public Optional<PasswordHash> passwordHash() {
        return Optional.ofNullable(passwordHash);
    }

    public Optional<Instant> verifiedAt() {
        return Optional.ofNullable(verifiedAt);
    }

    /** When the account was created. FR-013 counts the 7 days from here; it never changes. */
    public Instant createdAt() {
        return createdAt;
    }

    public Optional<Instant> lastSignUpWarningAt() {
        return Optional.ofNullable(lastSignUpWarningAt);
    }

    public List<LinkToken> linkTokens() {
        return List.copyOf(linkTokens);
    }

    public List<Session> sessions() {
        return List.copyOf(sessions);
    }

    /** Optimistic-locking version from storage. 0 for a user that was never saved. */
    public long version() {
        return version;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof User that && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "User[id=" + id.value() + "]";
    }
}
