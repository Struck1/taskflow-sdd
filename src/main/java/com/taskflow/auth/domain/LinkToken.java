package com.taskflow.auth.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A single-use link sent by email (TD-008). An entity inside the {@code User} aggregate.
 * It is usable while it is unused, not invalidated, and {@code now < expiresAt}.
 * Once used or invalidated it never becomes usable again.
 */
public final class LinkToken {

    /** Why a token cannot be used. When several apply, the first in this order is reported. */
    public enum UnusableReason { USED, INVALIDATED, EXPIRED }

    private final LinkTokenId id;
    private final LinkPurpose purpose;
    private final TokenHash tokenHash;
    private final Instant createdAt;
    private final Instant expiresAt;
    private Instant usedAt;
    private Instant invalidatedAt;

    private LinkToken(LinkTokenId id, LinkPurpose purpose, TokenHash tokenHash, Instant createdAt,
                      Instant expiresAt, Instant usedAt, Instant invalidatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.purpose = Objects.requireNonNull(purpose, "purpose");
        this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
        if (usedAt != null && invalidatedAt != null) {
            throw new IllegalArgumentException("A link token cannot be both used and invalidated");
        }
        this.usedAt = usedAt;
        this.invalidatedAt = invalidatedAt;
    }

    /** A new token, valid for its purpose's validity from {@code now}. */
    public static LinkToken issue(LinkTokenId id, LinkPurpose purpose, TokenHash tokenHash, Instant now) {
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(now, "now");
        return new LinkToken(id, purpose, tokenHash, now, now.plus(purpose.validity()), null, null);
    }

    /** Rebuilds a token from stored state. */
    public static LinkToken restore(LinkTokenId id, LinkPurpose purpose, TokenHash tokenHash, Instant createdAt,
                                    Instant expiresAt, Instant usedAt, Instant invalidatedAt) {
        return new LinkToken(id, purpose, tokenHash, createdAt, expiresAt, usedAt, invalidatedAt);
    }

    public boolean isUsableAt(Instant now) {
        return unusableReasonAt(now).isEmpty();
    }

    public Optional<UnusableReason> unusableReasonAt(Instant now) {
        Objects.requireNonNull(now, "now");
        if (usedAt != null) {
            return Optional.of(UnusableReason.USED);
        }
        if (invalidatedAt != null) {
            return Optional.of(UnusableReason.INVALIDATED);
        }
        if (!now.isBefore(expiresAt)) {
            return Optional.of(UnusableReason.EXPIRED);
        }
        return Optional.empty();
    }

    /**
     * Uses the token up.
     *
     * @throws IllegalStateException if the token is not usable at {@code now}
     */
    public void markUsed(Instant now) {
        unusableReasonAt(now).ifPresent(reason -> {
            throw new IllegalStateException("Link token is not usable: " + reason);
        });
        usedAt = now;
    }

    /**
     * Invalidates an unused token, for example because a newer link was issued. A token
     * that is already used or invalidated keeps its original state.
     */
    public void invalidate(Instant now) {
        Objects.requireNonNull(now, "now");
        if (usedAt == null && invalidatedAt == null) {
            invalidatedAt = now;
        }
    }

    public LinkTokenId id() {
        return id;
    }

    public LinkPurpose purpose() {
        return purpose;
    }

    public TokenHash tokenHash() {
        return tokenHash;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Optional<Instant> usedAt() {
        return Optional.ofNullable(usedAt);
    }

    public Optional<Instant> invalidatedAt() {
        return Optional.ofNullable(invalidatedAt);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof LinkToken that && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "LinkToken[id=" + id.value() + ", purpose=" + purpose + "]";
    }
}
