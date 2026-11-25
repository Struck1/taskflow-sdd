package com.taskflow.auth.domain;

/** Outcome of someone signing up with the email of a verified account (FR-002). */
public enum SignUpAttemptResult {
    /** Send the owner the warning email. */
    SEND_WARNING,
    /** A warning was already sent in the last 24 hours. Send nothing. */
    SKIP
}
