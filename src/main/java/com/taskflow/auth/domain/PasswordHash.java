package com.taskflow.auth.domain;

/** An encoded password hash (Argon2id string, TD-005). Opaque to the domain and never logged. */
public record PasswordHash(String value) {

    public PasswordHash {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("PasswordHash must not be blank");
        }
    }

    @Override
    public String toString() {
        return "PasswordHash[***]";
    }
}
