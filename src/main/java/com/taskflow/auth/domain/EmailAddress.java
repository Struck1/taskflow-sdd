package com.taskflow.auth.domain;

import java.util.Locale;

/**
 * A normalized email address (TD-010, Q-002): trimmed and lowercased with {@link Locale#ROOT},
 * so the result does not depend on the JVM's default locale.
 */
public record EmailAddress(String value) {

    public static final int MAX_LENGTH = 254;

    static final String REQUIRED = "Email is required";
    static final String INVALID = "Email is invalid";

    public EmailAddress {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException("email", REQUIRED);
        }
        value = value.trim().toLowerCase(Locale.ROOT);
        if (!hasValidSyntax(value)) {
            throw new DomainValidationException("email", INVALID);
        }
    }

    private static boolean hasValidSyntax(String email) {
        if (email.length() > MAX_LENGTH || containsWhitespace(email)) {
            return false;
        }
        int at = email.indexOf('@');
        if (at <= 0 || at != email.lastIndexOf('@')) {
            return false; // no '@', empty local part, or more than one '@'
        }
        String domain = email.substring(at + 1);
        if (!domain.contains(".")) {
            return false;
        }
        for (String label : domain.split("\\.", -1)) {
            if (label.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Covers ASCII whitespace and Unicode spaces such as the no-break space (U+00A0). */
    private static boolean containsWhitespace(String email) {
        return email.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c));
    }
}
