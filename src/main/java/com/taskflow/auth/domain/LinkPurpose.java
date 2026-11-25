package com.taskflow.auth.domain;

import java.time.Duration;

/** What a link token is for, and how long it stays valid (FR-005, FR-009). */
public enum LinkPurpose {
    VERIFY_EMAIL(AuthPolicy.VERIFICATION_LINK_VALIDITY),
    MAGIC_LINK(AuthPolicy.MAGIC_LINK_VALIDITY);

    private final Duration validity;

    LinkPurpose(Duration validity) {
        this.validity = validity;
    }

    public Duration validity() {
        return validity;
    }
}
