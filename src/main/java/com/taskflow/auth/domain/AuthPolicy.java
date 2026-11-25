package com.taskflow.auth.domain;

import java.time.Duration;

/**
 * The numbers from requirements.md. They are constants, not configuration, so they cannot
 * drift from the spec (design §2).
 */
public final class AuthPolicy {

    /** FR-005, FR-009: at most this many links of one purpose per window. */
    public static final int MAX_LINKS_PER_WINDOW = 3;
    public static final Duration LINK_RATE_LIMIT_WINDOW = Duration.ofMinutes(15);

    /** FR-005, US-003. */
    public static final Duration MAGIC_LINK_VALIDITY = Duration.ofMinutes(15);
    /** FR-009. */
    public static final Duration VERIFICATION_LINK_VALIDITY = Duration.ofMinutes(60);

    /** FR-007. */
    public static final Duration SESSION_IDLE_TIMEOUT = Duration.ofDays(30);
    public static final Duration SESSION_ABSOLUTE_LIFETIME = Duration.ofDays(90);
    /** TD-003: last_seen_at is written at most this often. */
    public static final Duration SESSION_TOUCH_INTERVAL = Duration.ofHours(1);

    /** FR-004. */
    public static final int MAX_FAILED_SIGN_INS = 5;
    public static final Duration SIGN_IN_FAILURE_WINDOW = Duration.ofMinutes(15);
    public static final Duration SIGN_IN_LOCKOUT = Duration.ofMinutes(15);

    /** FR-013. */
    public static final Duration UNVERIFIED_ACCOUNT_LIFETIME = Duration.ofDays(7);

    /** FR-002: at most one warning email per address per interval. */
    public static final Duration SIGN_UP_WARNING_INTERVAL = Duration.ofHours(24);

    private AuthPolicy() {
    }
}
