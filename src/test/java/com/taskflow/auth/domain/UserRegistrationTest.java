package com.taskflow.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskflow.shared.domain.UserId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserRegistrationTest {

    private static final Instant T0 = Instant.parse("2026-11-25T10:00:00Z");
    private static final EmailAddress EMAIL = new EmailAddress("ali@mail.com");
    private static final PasswordHash HASH = new PasswordHash("$argon2id$v=19$m=19456,t=2,p=1$c2FsdA$aGFzaA");

    private static User unverifiedUser() {
        return User.register(new UserId(UUID.randomUUID()), EMAIL, T0);
    }

    private static User verifiedUser(LinkToken... tokens) {
        return User.restore(new UserId(UUID.randomUUID()), EMAIL, HASH, T0, T0, null, List.of(tokens), List.of(), 3L);
    }

    private static LinkIssueResult issueVerification(User user, Instant at) {
        return user.issueVerificationToken(newTokenId(), newHash(), at);
    }

    private static LinkIssueResult issueMagicLink(User user, Instant at) {
        return user.issueMagicLinkToken(newTokenId(), newHash(), at);
    }

    private static LinkTokenId newTokenId() {
        return new LinkTokenId(UUID.randomUUID());
    }

    private static TokenHash newHash() {
        return TokenHash.of(new RawToken(UUID.randomUUID().toString()));
    }

    private static Instant minutes(long m) {
        return T0.plus(Duration.ofMinutes(m));
    }

    // --- register (FR-001) ---

    @Test
    void registeredUserIsUnverifiedWithoutPassword() {
        User user = unverifiedUser();

        assertThat(user.isVerified()).isFalse();
        assertThat(user.passwordHash()).isEmpty();
        assertThat(user.verifiedAt()).isEmpty();
        assertThat(user.createdAt()).isEqualTo(T0);
        assertThat(user.email()).isEqualTo(EMAIL);
        assertThat(user.linkTokens()).isEmpty();
        assertThat(user.sessions()).isEmpty();
        assertThat(user.version()).isZero();
    }

    // --- rate limit: 3 per 15 min, sliding (FR-005, FR-009) ---

    @Test
    void fourthVerificationTokenWithin15MinutesIsRateLimited() {
        User user = unverifiedUser();
        assertThat(issueVerification(user, minutes(0))).isInstanceOf(LinkIssueResult.Issued.class);
        assertThat(issueVerification(user, minutes(1))).isInstanceOf(LinkIssueResult.Issued.class);
        assertThat(issueVerification(user, minutes(2))).isInstanceOf(LinkIssueResult.Issued.class);

        assertThat(issueVerification(user, minutes(3))).isInstanceOf(LinkIssueResult.RateLimited.class);
        assertThat(user.linkTokens()).hasSize(3);
    }

    @Test
    void tokenAtMinute16IsIssuedAgain() {
        User user = unverifiedUser();
        issueVerification(user, minutes(0));
        issueVerification(user, minutes(0));
        issueVerification(user, minutes(0));

        assertThat(issueVerification(user, minutes(16))).isInstanceOf(LinkIssueResult.Issued.class);
    }

    @Test
    void windowIsSlidingAndExcludesTokensExactly15MinutesOld() {
        User user = unverifiedUser();
        issueVerification(user, minutes(0));
        issueVerification(user, minutes(5));
        issueVerification(user, minutes(10));

        assertThat(issueVerification(user, minutes(15).minusSeconds(1)))
                .isInstanceOf(LinkIssueResult.RateLimited.class);
        // The token from minute 0 has left the window; those from 5 and 10 still count.
        assertThat(issueVerification(user, minutes(15))).isInstanceOf(LinkIssueResult.Issued.class);
        assertThat(issueVerification(user, minutes(16))).isInstanceOf(LinkIssueResult.RateLimited.class);
    }

    @Test
    void rateLimitedRequestChangesNothing() {
        User user = unverifiedUser();
        issueVerification(user, minutes(0));
        issueVerification(user, minutes(1));
        issueVerification(user, minutes(2));
        LinkToken newest = user.linkTokens().get(2);

        issueVerification(user, minutes(3));

        assertThat(newest.isUsableAt(minutes(3))).isTrue();
        assertThat(user.linkTokens()).hasSize(3);
    }

    @Test
    void magicLinksHaveTheSameRateLimit() {
        User user = verifiedUser();
        issueMagicLink(user, minutes(0));
        issueMagicLink(user, minutes(1));
        issueMagicLink(user, minutes(2));

        assertThat(issueMagicLink(user, minutes(3))).isInstanceOf(LinkIssueResult.RateLimited.class);
        assertThat(issueMagicLink(user, minutes(16))).isInstanceOf(LinkIssueResult.Issued.class);
    }

    @Test
    void rateLimitCountsOnlyTheSamePurpose() {
        Instant earlier = minutes(-1);
        User user = verifiedUser(
                LinkToken.issue(newTokenId(), LinkPurpose.VERIFY_EMAIL, newHash(), earlier),
                LinkToken.issue(newTokenId(), LinkPurpose.VERIFY_EMAIL, newHash(), earlier),
                LinkToken.issue(newTokenId(), LinkPurpose.VERIFY_EMAIL, newHash(), earlier));

        assertThat(issueMagicLink(user, minutes(0))).isInstanceOf(LinkIssueResult.Issued.class);
    }

    // --- invalidation (FR-005, FR-009) ---

    @Test
    void newTokenInvalidatesEveryOlderUnusedTokenOfTheSamePurpose() {
        User user = unverifiedUser();
        issueVerification(user, minutes(0));
        issueVerification(user, minutes(1));
        LinkIssueResult.Issued newest = (LinkIssueResult.Issued) issueVerification(user, minutes(2));

        List<LinkToken> tokens = user.linkTokens();
        assertThat(tokens).filteredOn(t -> t.id().equals(newest.linkTokenId()))
                .singleElement()
                .satisfies(t -> assertThat(t.isUsableAt(minutes(2))).isTrue());
        assertThat(tokens).filteredOn(t -> !t.id().equals(newest.linkTokenId()))
                .hasSize(2)
                .allSatisfy(t -> assertThat(t.unusableReasonAt(minutes(2)))
                        .contains(LinkToken.UnusableReason.INVALIDATED));
    }

    @Test
    void newTokenLeavesTheOtherPurposeAlone() {
        LinkToken oldVerification = LinkToken.issue(newTokenId(), LinkPurpose.VERIFY_EMAIL, newHash(), minutes(0));
        User user = verifiedUser(oldVerification);

        issueMagicLink(user, minutes(1));

        assertThat(oldVerification.isUsableAt(minutes(1))).isTrue();
        assertThat(oldVerification.invalidatedAt()).isEmpty();
    }

    @Test
    void newTokenKeepsUsedTokensUsed() {
        LinkToken used = LinkToken.issue(newTokenId(), LinkPurpose.MAGIC_LINK, newHash(), minutes(0));
        used.markUsed(minutes(1));
        User user = verifiedUser(used);

        issueMagicLink(user, minutes(2));

        assertThat(used.unusableReasonAt(minutes(2))).contains(LinkToken.UnusableReason.USED);
        assertThat(used.invalidatedAt()).isEmpty();
    }

    // --- expiry per purpose ---

    @Test
    void verificationTokenExpiresAfter60Minutes() {
        User user = unverifiedUser();
        issueVerification(user, minutes(0));
        assertThat(user.linkTokens().getFirst().expiresAt()).isEqualTo(minutes(60));
    }

    @Test
    void magicLinkExpiresAfter15Minutes() {
        User user = verifiedUser();
        issueMagicLink(user, minutes(0));
        assertThat(user.linkTokens().getFirst().expiresAt()).isEqualTo(minutes(15));
    }

    // --- state rules ---

    @Test
    void verifiedUserCannotGetAVerificationToken() {
        User user = verifiedUser();
        assertThatThrownBy(() -> issueVerification(user, minutes(0))).isInstanceOf(IllegalStateException.class);
        assertThat(user.linkTokens()).isEmpty();
    }

    @Test
    void unverifiedUserCannotGetAMagicLink() {
        User user = unverifiedUser();
        assertThatThrownBy(() -> issueMagicLink(user, minutes(0))).isInstanceOf(IllegalStateException.class);
        assertThat(user.linkTokens()).isEmpty();
    }

    // --- sign-up warning (FR-002) ---

    @Test
    void warningIsSentAt0hSuppressedAt23h59AndSentAgainAt24h() {
        User user = verifiedUser();

        assertThat(user.recordSignUpAttempt(T0)).isEqualTo(SignUpAttemptResult.SEND_WARNING);
        assertThat(user.recordSignUpAttempt(T0.plus(Duration.ofHours(23)).plus(Duration.ofMinutes(59))))
                .isEqualTo(SignUpAttemptResult.SKIP);
        assertThat(user.recordSignUpAttempt(T0.plus(Duration.ofHours(24))))
                .isEqualTo(SignUpAttemptResult.SEND_WARNING);
    }

    @Test
    void sendingAWarningUpdatesLastSignUpWarningAtButSkippingDoesNot() {
        User user = verifiedUser();
        user.recordSignUpAttempt(T0);
        user.recordSignUpAttempt(T0.plus(Duration.ofHours(1)));

        assertThat(user.lastSignUpWarningAt()).contains(T0);
    }

    @Test
    void unverifiedUserGetsNoSignUpWarning() {
        User user = unverifiedUser();
        assertThatThrownBy(() -> user.recordSignUpAttempt(T0)).isInstanceOf(IllegalStateException.class);
    }

    // --- FR-013 clock ---

    @Test
    void issuingLinksDoesNotChangeCreatedAt() {
        User user = unverifiedUser();
        issueVerification(user, minutes(30));
        issueVerification(user, Instant.parse("2026-11-28T10:00:00Z"));

        assertThat(user.createdAt()).isEqualTo(T0);
    }

    // --- restore ---

    @Test
    void restoreRejectsVerifiedWithoutPasswordAndPasswordWithoutVerified() {
        UserId id = new UserId(UUID.randomUUID());
        assertThatThrownBy(() -> User.restore(id, EMAIL, null, T0, T0, null, List.of(), List.of(), 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> User.restore(id, EMAIL, HASH, null, T0, null, List.of(), List.of(), 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void restoreRejectsWarningOnUnverifiedUser() {
        UserId id = new UserId(UUID.randomUUID());
        assertThatThrownBy(() -> User.restore(id, EMAIL, null, null, T0, T0, List.of(), List.of(), 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void restoreKeepsAllState() {
        UserId id = new UserId(UUID.randomUUID());
        LinkToken token = LinkToken.issue(newTokenId(), LinkPurpose.MAGIC_LINK, newHash(), T0);
        Session session = Session.start(new SessionId(UUID.randomUUID()), newHash(), T0);

        User user = User.restore(id, EMAIL, HASH, T0, T0.minusSeconds(60), T0, List.of(token), List.of(session), 7L);

        assertThat(user.id()).isEqualTo(id);
        assertThat(user.passwordHash()).contains(HASH);
        assertThat(user.verifiedAt()).contains(T0);
        assertThat(user.createdAt()).isEqualTo(T0.minusSeconds(60));
        assertThat(user.lastSignUpWarningAt()).contains(T0);
        assertThat(user.linkTokens()).containsExactly(token);
        assertThat(user.sessions()).containsExactly(session);
        assertThat(user.version()).isEqualTo(7L);
    }

    @Test
    void collectionsCannotBeChangedFromOutside() {
        User user = unverifiedUser();
        issueVerification(user, T0);

        assertThatThrownBy(() -> user.linkTokens().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> user.sessions().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
