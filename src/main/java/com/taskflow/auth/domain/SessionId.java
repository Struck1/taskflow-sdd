package com.taskflow.auth.domain;

import java.util.Objects;
import java.util.UUID;

public record SessionId(UUID value) {

    public SessionId {
        Objects.requireNonNull(value, "SessionId value must not be null");
    }
}
