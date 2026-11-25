package com.taskflow.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class EmailAddressTest {

    // Requirements validation table: email ""
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\t"})
    void rejectsMissingEmailAsRequired(String input) {
        assertThatThrownBy(() -> new EmailAddress(input))
                .isInstanceOf(DomainValidationException.class)
                .hasMessage("Email is required");
    }

    // Requirements validation table: email "ali"
    @Test
    void rejectsEmailWithoutAtSign() {
        assertThatThrownBy(() -> new EmailAddress("ali"))
                .isInstanceOf(DomainValidationException.class)
                .hasMessage("Email is invalid");
    }

    // Requirements validation table: email "ali veli@mail.com"
    @Test
    void rejectsSpaceInsideAddress() {
        assertThatThrownBy(() -> new EmailAddress("ali veli@mail.com"))
                .isInstanceOf(DomainValidationException.class)
                .hasMessage("Email is invalid");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ali@mail .com",        // space in the domain
            "ali\tveli@mail.com",   // tab
            "ali\nveli@mail.com",   // line break
            "ali veli@mail.com" // no-break space
    })
    void rejectsAnyWhitespaceInsideAddress(String input) {
        assertThatThrownBy(() -> new EmailAddress(input))
                .isInstanceOf(DomainValidationException.class)
                .hasMessage("Email is invalid");
    }

    // Requirements validation table: email " Ali@Mail.com " (Q-002)
    @Test
    void trimsAndLowercases() {
        assertThat(new EmailAddress(" Ali@Mail.com ").value()).isEqualTo("ali@mail.com");
    }

    @Test
    void differentlyCasedEmailsAreEqual() {
        assertThat(new EmailAddress("Ali@Mail.com")).isEqualTo(new EmailAddress("ali@mail.com"));
    }

    @Test
    void lowercasesIndependentlyOfTurkishDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(new EmailAddress("ILKER@MAIL.COM").value()).isEqualTo("ilker@mail.com");
        } finally {
            Locale.setDefault(original);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "@mail.com",          // empty local part
            "ali@@mail.com",      // two '@'
            "ali@x@mail.com",     // two '@'
            "ali@mail",           // domain without a dot
            "ali@",               // empty domain
            "ali@.mail.com",      // empty first label
            "ali@mail..com",      // empty middle label
            "ali@mail.com."       // empty last label
    })
    void rejectsInvalidSyntax(String input) {
        assertThatThrownBy(() -> new EmailAddress(input))
                .isInstanceOf(DomainValidationException.class)
                .hasMessage("Email is invalid");
    }

    @Test
    void accepts254CharactersAndRejects255() {
        String domain = "@mail.com";
        String max = "a".repeat(254 - domain.length()) + domain;
        assertThat(new EmailAddress(max).value()).hasSize(254);

        String tooLong = "a" + max;
        assertThatThrownBy(() -> new EmailAddress(tooLong))
                .isInstanceOf(DomainValidationException.class)
                .hasMessage("Email is invalid");
    }

    @Test
    void lengthIsCheckedAfterTrimming() {
        String max = "a".repeat(254 - "@mail.com".length()) + "@mail.com";
        assertThat(new EmailAddress("  " + max + "  ").value()).hasSize(254);
    }

    @Test
    void errorMessageNeverContainsTheInput() {
        assertThatThrownBy(() -> new EmailAddress("secret-person@nodot"))
                .hasMessageNotContaining("secret-person");
    }
}
