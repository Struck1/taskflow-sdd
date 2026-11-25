package com.taskflow.auth.domain;

/**
 * A password exactly as the user typed it (Q-006: never trimmed). It is only hashed, never
 * stored or logged (NFR-001). Length is counted in Unicode code points (TD-010).
 */
public record RawPassword(String value) {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 128;

    static final String BLANK = "Password must not be blank";
    static final String TOO_SHORT = "Password must be at least 8 characters";
    static final String TOO_LONG = "Password must be 128 characters or fewer";

    public RawPassword {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException("password", BLANK);
        }
        int length = value.codePointCount(0, value.length());
        if (length < MIN_LENGTH) {
            throw new DomainValidationException("password", TOO_SHORT);
        }
        if (length > MAX_LENGTH) {
            throw new DomainValidationException("password", TOO_LONG);
        }
    }

    @Override
    public String toString() {
        return "RawPassword[***]";
    }
}
