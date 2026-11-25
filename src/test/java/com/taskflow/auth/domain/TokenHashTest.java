package com.taskflow.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class TokenHashTest {

    @Test
    void hashesWithSha256() {
        // FIPS 180-2 test vector for "abc".
        TokenHash hash = TokenHash.of(new RawToken("abc"));
        assertThat(HexFormat.of().formatHex(hash.value()))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void sameTokenGivesEqualHashes() {
        TokenHash first = TokenHash.of(new RawToken("token-1"));
        TokenHash second = TokenHash.of(new RawToken("token-1"));
        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        assertThat(first).isNotEqualTo(TokenHash.of(new RawToken("token-2")));
    }

    @Test
    void rejectsWrongLength() {
        assertThatThrownBy(() -> new TokenHash(new byte[31])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenHash(new byte[33])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenHash(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void isImmutable() {
        byte[] bytes = new byte[32];
        TokenHash hash = new TokenHash(bytes);
        bytes[0] = 1;
        hash.value()[1] = 1;
        assertThat(hash.value()).containsOnly(0);
    }

    @Test
    void toStringHidesTheHash() {
        assertThat(TokenHash.of(new RawToken("abc")).toString())
                .isEqualTo("TokenHash[***]")
                .doesNotContain("ba7816bf");
    }

    @Test
    void rawTokenToStringHidesTheToken() {
        assertThat(new RawToken("s3cr3t-token").toString())
                .isEqualTo("RawToken[***]")
                .doesNotContain("s3cr3t-token");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  "})
    void rawTokenRejectsBlank(String input) {
        assertThatThrownBy(() -> new RawToken(input))
                .isInstanceOf(DomainValidationException.class)
                .hasMessage("Token is required");
    }

    @Test
    void passwordHashToStringHidesTheHash() {
        assertThat(new PasswordHash("$argon2id$v=19$m=19456,t=2,p=1$c2FsdA$aGFzaA").toString())
                .isEqualTo("PasswordHash[***]");
    }
}
