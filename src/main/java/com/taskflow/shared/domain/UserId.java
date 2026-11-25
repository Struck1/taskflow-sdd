package com.taskflow.shared.domain;

import java.util.Objects;
import java.util.UUID;

/** Identifies a user across modules. This package contains only ID records. */
public record UserId(UUID value) {

    public UserId {
        Objects.requireNonNull(value, "UserId value must not be null");
    }
}
