package com.taskflow.auth.domain;

/**
 * A raw session or link token. It is only ever stored as a {@link TokenHash} and never
 * logged (NFR-003). Any non-blank value is accepted: a token that does not match a stored
 * hash is simply unknown.
 */
public record RawToken(String value) {

    static final String REQUIRED = "Token is required";

    public RawToken {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException("token", REQUIRED);
        }
    }

    @Override
    public String toString() {
        return "RawToken[***]";
    }
}
