package com.taskflow.auth.domain;

/**
 * Thrown when user input breaks a validation rule. The message is safe to show to the user
 * and never contains the rejected value.
 */
public class DomainValidationException extends RuntimeException {

    private final String field;

    public DomainValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
