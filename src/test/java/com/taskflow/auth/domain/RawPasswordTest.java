package com.taskflow.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class RawPasswordTest {

    private static final String EMOJI = "😀"; // one code point, two UTF-16 chars

    // Requirements validation table: password "1234567" (7 chars)
    @Test
    void rejectsSevenCharacters() {
        assertThatThrownBy(() -> new RawPassword("1234567"))
                .isInstanceOf(DomainValidationException.class)
                .hasMessage("Password must be at least 8 characters");
    }

    // Requirements validation table: password "12345678" (8 chars)
    @Test
    void acceptsEightCharacters() {
        assertThat(new RawPassword("12345678").value()).isEqualTo("12345678");
    }

    // Requirements validation table: password of 129 chars
    @Test
    void rejects129Characters() {
        assertThatThrownBy(() -> new RawPassword("a".repeat(129)))
                .isInstanceOf(DomainValidationException.class)
                .hasMessage("Password must be 128 characters or fewer");
    }

    @Test
    void accepts128Characters() {
        assertThat(new RawPassword("a".repeat(128)).value()).hasSize(128);
    }

    // Requirements validation table: password " abcdefgh" (leading space), Q-006
    @Test
    void keepsLeadingSpaceAsTyped() {
        RawPassword password = new RawPassword(" abcdefgh");
        assertThat(password.value()).isEqualTo(" abcdefgh").hasSize(9);
    }

    // Requirements validation table: password of 8 spaces
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "        ", "   ", "\t\t\t\t\t\t\t\t"})
    void rejectsBlankPassword(String input) {
        assertThatThrownBy(() -> new RawPassword(input))
                .isInstanceOf(DomainValidationException.class)
                .hasMessage("Password must not be blank");
    }

    @Test
    void countsCodePointsNotUtf16Chars() {
        String eightEmoji = EMOJI.repeat(8);
        assertThat(eightEmoji).hasSize(16);
        assertThat(new RawPassword(eightEmoji).value()).isEqualTo(eightEmoji);

        assertThatThrownBy(() -> new RawPassword(EMOJI.repeat(7)))
                .hasMessage("Password must be at least 8 characters");
    }

    @Test
    void accepts128EmojiAndRejects129() {
        assertThat(new RawPassword(EMOJI.repeat(128)).value()).hasSize(256);
        assertThatThrownBy(() -> new RawPassword(EMOJI.repeat(129)))
                .hasMessage("Password must be 128 characters or fewer");
    }

    @Test
    void toStringNeverContainsThePassword() {
        RawPassword password = new RawPassword("correct horse battery");
        assertThat(password.toString())
                .isEqualTo("RawPassword[***]")
                .doesNotContain("correct horse battery");
    }

    @Test
    void errorMessageNeverContainsTheInput() {
        assertThatThrownBy(() -> new RawPassword("hunter2"))
                .hasMessageNotContaining("hunter2");
    }
}
