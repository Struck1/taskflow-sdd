package com.taskflow.auth.domain;

import java.util.Objects;
import java.util.UUID;

public record LinkTokenId(UUID value) {

    public LinkTokenId {
        Objects.requireNonNull(value, "LinkTokenId value must not be null");
    }
}
