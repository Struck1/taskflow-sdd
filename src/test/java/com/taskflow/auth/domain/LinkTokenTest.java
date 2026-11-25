package com.taskflow.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskflow.auth.domain.LinkToken.UnusableReason;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class LinkTokenTest {

    private static final Instant NOW = Instant.parse("2026-11-25T10:00:00Z");
    private static final Duration ONE_SECOND = Duration.ofSeconds(1);

    private static LinkToken issue(LinkPurpose purpose) {
        return LinkToken.issue(new LinkTokenId(UUID.randomUUID()), purpose,
                TokenHash.of(new RawToken("token")), NOW);
    }

    @Test
    void verificationLinkIsValidFor60Minutes() {
        assertThat(issue(LinkPurpose.VERIFY_EMAIL).expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(60)));
    }

    @Test
    void magicLinkIsValidFor15Minutes() {
        assertThat(issue(LinkPurpose.MAGIC_LINK).expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
    }

    @ParameterizedTest
    @EnumSource(LinkPurpose.class)
    void expiresExactlyAtExpiresAt(LinkPurpose purpose) {
        LinkToken token = issue(purpose);

        assertThat(token.isUsableAt(NOW)).isTrue();
        assertThat(token.isUsableAt(token.expiresAt().minus(ONE_SECOND))).isTrue();
        assertThat(token.isUsableAt(token.expiresAt())).isFalse();
        assertThat(token.unusableReasonAt(token.expiresAt())).contains(UnusableReason.EXPIRED);
    }

    @Test
    void usedTokenIsNeverUsableAgain() {
        LinkToken token = issue(LinkPurpose.MAGIC_LINK);
        token.markUsed(NOW.plusSeconds(10));

        assertThat(token.isUsableAt(NOW.plusSeconds(11))).isFalse();
        assertThat(token.unusableReasonAt(NOW.plusSeconds(11))).contains(UnusableReason.USED);
        assertThat(token.usedAt()).contains(NOW.plusSeconds(10));
        assertThatThrownBy(() -> token.markUsed(NOW.plusSeconds(12)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("USED");
    }

    @Test
    void invalidatedTokenIsNeverUsableAgain() {
        LinkToken token = issue(LinkPurpose.VERIFY_EMAIL);
        token.invalidate(NOW.plusSeconds(10));

        assertThat(token.isUsableAt(NOW.plusSeconds(11))).isFalse();
        assertThat(token.unusableReasonAt(NOW.plusSeconds(11))).contains(UnusableReason.INVALIDATED);
        assertThatThrownBy(() -> token.markUsed(NOW.plusSeconds(12)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INVALIDATED");
    }

    @Test
    void expiredTokenCannotBeUsed() {
        LinkToken token = issue(LinkPurpose.MAGIC_LINK);
        assertThatThrownBy(() -> token.markUsed(token.expiresAt()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EXPIRED");
        assertThat(token.usedAt()).isEmpty();
    }

    @Test
    void invalidatingAUsedTokenKeepsItUsed() {
        LinkToken token = issue(LinkPurpose.MAGIC_LINK);
        token.markUsed(NOW.plusSeconds(10));
        token.invalidate(NOW.plusSeconds(20));

        assertThat(token.invalidatedAt()).isEmpty();
        assertThat(token.unusableReasonAt(NOW.plusSeconds(21))).contains(UnusableReason.USED);
    }

    @Test
    void invalidatingTwiceKeepsTheFirstTime() {
        LinkToken token = issue(LinkPurpose.MAGIC_LINK);
        token.invalidate(NOW.plusSeconds(10));
        token.invalidate(NOW.plusSeconds(20));

        assertThat(token.invalidatedAt()).contains(NOW.plusSeconds(10));
    }

    @Test
    void usedWinsOverExpiredAsTheReason() {
        LinkToken token = issue(LinkPurpose.MAGIC_LINK);
        token.markUsed(NOW);
        assertThat(token.unusableReasonAt(token.expiresAt().plusSeconds(60))).contains(UnusableReason.USED);
    }

    @Test
    void restoreRejectsImpossibleState() {
        LinkTokenId id = new LinkTokenId(UUID.randomUUID());
        TokenHash hash = TokenHash.of(new RawToken("token"));
        Instant expires = NOW.plusSeconds(900);

        assertThatThrownBy(() -> LinkToken.restore(id, LinkPurpose.MAGIC_LINK, hash, NOW, expires, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LinkToken.restore(id, LinkPurpose.MAGIC_LINK, hash, NOW, NOW, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void restoredTokenKeepsItsState() {
        LinkTokenId id = new LinkTokenId(UUID.randomUUID());
        LinkToken token = LinkToken.restore(id, LinkPurpose.VERIFY_EMAIL, TokenHash.of(new RawToken("t")),
                NOW, NOW.plusSeconds(3600), NOW.plusSeconds(5), null);

        assertThat(token.unusableReasonAt(NOW.plusSeconds(6))).contains(UnusableReason.USED);
        assertThat(token.id()).isEqualTo(id);
    }

    @Test
    void toStringDoesNotExposeTheHash() {
        assertThat(issue(LinkPurpose.MAGIC_LINK).toString()).doesNotContain("TokenHash");
    }
}
