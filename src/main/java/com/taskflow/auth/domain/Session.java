package com.taskflow.auth.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * A signed-in session on one device (TD-001). An entity inside the {@code User} aggregate.
 * Active while used at least once every 30 days, and never longer than 90 days after
 * sign-in (FR-007).
 */
public final class Session {

    private final SessionId id;
    private final TokenHash tokenHash;
    private final Instant createdAt;
    private final Instant lastSeenAt;

    private Session(SessionId id, TokenHash tokenHash, Instant createdAt, Instant lastSeenAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.lastSeenAt = Objects.requireNonNull(lastSeenAt, "lastSeenAt");
        if (lastSeenAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("lastSeenAt must not be before createdAt");
        }
    }

    /** A new session that starts at {@code now}. */
    public static Session start(SessionId id, TokenHash tokenHash, Instant now) {
        return new Session(id, tokenHash, now, now);
    }

    /** Rebuilds a session from stored state. */
    public static Session restore(SessionId id, TokenHash tokenHash, Instant createdAt, Instant lastSeenAt) {
        return new Session(id, tokenHash, createdAt, lastSeenAt);
    }

    /** FR-007: {@code now < lastSeenAt + 30 d} and {@code now < createdAt + 90 d}. */
    public boolean isActiveAt(Instant now) {
        Objects.requireNonNull(now, "now");
        return now.isBefore(lastSeenAt.plus(AuthPolicy.SESSION_IDLE_TIMEOUT))
                && now.isBefore(createdAt.plus(AuthPolicy.SESSION_ABSOLUTE_LIFETIME));
    }

    /** TD-003: {@code lastSeenAt} is written at most once an hour. */
    public boolean needsTouch(Instant now) {
        Objects.requireNonNull(now, "now");
        return !now.isBefore(lastSeenAt.plus(AuthPolicy.SESSION_TOUCH_INTERVAL));
    }

    public SessionId id() {
        return id;
    }

    public TokenHash tokenHash() {
        return tokenHash;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant lastSeenAt() {
        return lastSeenAt;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Session that && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Session[id=" + id.value() + "]";
    }
}
