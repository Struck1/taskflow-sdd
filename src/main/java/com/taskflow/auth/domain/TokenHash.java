package com.taskflow.auth.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

/** SHA-256 of a {@link RawToken}. This is the only form in which tokens are stored (TD-001, TD-008). */
public record TokenHash(byte[] value) {

    public static final int LENGTH = 32;

    public TokenHash {
        Objects.requireNonNull(value, "TokenHash value must not be null");
        if (value.length != LENGTH) {
            throw new IllegalArgumentException("TokenHash must be " + LENGTH + " bytes");
        }
        value = value.clone();
    }

    public static TokenHash of(RawToken token) {
        Objects.requireNonNull(token, "token must not be null");
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return new TokenHash(sha256.digest(token.value().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }

    @Override
    public byte[] value() {
        return value.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TokenHash that && MessageDigest.isEqual(value, that.value);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        return "TokenHash[***]";
    }
}
