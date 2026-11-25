package com.taskflow.auth.domain;

/**
 * A raw session or link token. It is only ever stored as a {@link TokenHash} and never
 * logged (NFR-003).
 *
 * <p>Checks (TD-010): blank input is a validation error. Input longer than
 * {@value #MAX_LENGTH} characters throws {@link UnknownTokenException}, which callers handle
 * exactly like a token that matches no stored hash. Any other value is accepted: a token
 * that matches no stored hash is simply unknown.
 */
public record RawToken(String value) {

    /** Counted in UTF-16 characters. Generated tokens are 43 characters. */
    public static final int MAX_LENGTH = 256;

    static final String REQUIRED = "Token is required";

    public RawToken {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException("token", REQUIRED);
        }
        if (value.length() > MAX_LENGTH) {
            throw new UnknownTokenException();
        }
    }

    @Override
    public String toString() {
        return "RawToken[***]";
    }
}
