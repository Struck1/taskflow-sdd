package com.taskflow.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SessionTest {

    private static final Instant SIGN_IN = Instant.parse("2026-11-25T10:00:00Z");
    private static final Duration ONE_SECOND = Duration.ofSeconds(1);

    private static Session restore(Instant createdAt, Instant lastSeenAt) {
        return Session.restore(new SessionId(UUID.randomUUID()), TokenHash.of(new RawToken("s")), createdAt, lastSeenAt);
    }

    @Test
    void newSessionIsActive() {
        Session session = Session.start(new SessionId(UUID.randomUUID()), TokenHash.of(new RawToken("s")), SIGN_IN);
        assertThat(session.isActiveAt(SIGN_IN)).isTrue();
        assertThat(session.lastSeenAt()).isEqualTo(SIGN_IN);
    }

    @Test
    void activeJustBefore30DaysIdleAndInactiveAt30Days() {
        Instant lastSeen = SIGN_IN.plus(Duration.ofDays(10));
        Session session = restore(SIGN_IN, lastSeen);

        assertThat(session.isActiveAt(lastSeen.plus(Duration.ofDays(30)).minus(ONE_SECOND))).isTrue();
        assertThat(session.isActiveAt(lastSeen.plus(Duration.ofDays(30)))).isFalse();
    }

    @Test
    void ninetyDayLimitHoldsEvenWhenTouchedYesterday() {
        Instant ninetyDays = SIGN_IN.plus(Duration.ofDays(90));
        Session session = restore(SIGN_IN, ninetyDays.minus(Duration.ofDays(1)));

        assertThat(session.isActiveAt(ninetyDays.minus(ONE_SECOND))).isTrue();
        assertThat(session.isActiveAt(ninetyDays)).isFalse();
    }

    @Test
    void needsTouchFalseAt59MinutesAndTrueAt60() {
        Session session = restore(SIGN_IN, SIGN_IN);

        assertThat(session.needsTouch(SIGN_IN.plus(Duration.ofMinutes(59)))).isFalse();
        assertThat(session.needsTouch(SIGN_IN.plus(Duration.ofMinutes(60)))).isTrue();
    }

    @Test
    void restoreRejectsLastSeenBeforeCreated() {
        assertThatThrownBy(() -> restore(SIGN_IN, SIGN_IN.minus(ONE_SECOND)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equalById() {
        SessionId id = new SessionId(UUID.randomUUID());
        Session first = Session.restore(id, TokenHash.of(new RawToken("a")), SIGN_IN, SIGN_IN);
        Session second = Session.restore(id, TokenHash.of(new RawToken("b")), SIGN_IN, SIGN_IN.plusSeconds(5));
        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
    }

    @Test
    void toStringDoesNotExposeTheHash() {
        assertThat(restore(SIGN_IN, SIGN_IN).toString()).doesNotContain("TokenHash");
    }
}
