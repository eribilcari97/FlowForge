package com.flowforge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CronScheduleTest {

    @Test
    void dailyAtEightFollowsTheTimeZoneInSummerAndWinter() {
        CronSchedule daily = CronSchedule.of("0 8 * * *", "Europe/Berlin");

        assertThat(daily.nextAfter(Instant.parse("2026-07-01T10:00:00Z")))
                .isEqualTo(Instant.parse("2026-07-02T06:00:00Z"));
        assertThat(daily.nextAfter(Instant.parse("2026-12-01T10:00:00Z")))
                .isEqualTo(Instant.parse("2026-12-02T07:00:00Z"));
    }

    @Test
    void theNextRunIsStrictlyAfterTheGivenTime() {
        CronSchedule daily = CronSchedule.of("0 8 * * *", "UTC");

        assertThat(daily.nextAfter(Instant.parse("2026-09-28T08:00:00Z")))
                .isEqualTo(Instant.parse("2026-09-29T08:00:00Z"));
    }

    @Test
    void everyFiveMinutesAndWeekdays() {
        assertThat(CronSchedule.of("*/5 * * * *", "UTC").nextAfter(Instant.parse("2026-09-28T10:02:30Z")))
                .isEqualTo(Instant.parse("2026-09-28T10:05:00Z"));
        assertThat(CronSchedule.of("0 8 * * 1-5", "UTC").nextAfter(Instant.parse("2026-10-02T09:00:00Z")))
                .as("Friday after 08:00 -> Monday")
                .isEqualTo(Instant.parse("2026-10-05T08:00:00Z"));
    }

    @Test
    void aTimeThatDoesNotExistOnTheSpringForwardDayRunsAnHourLaterInsteadOfBeingSkipped() {
        CronSchedule halfPastTwo = CronSchedule.of("30 2 * * *", "Europe/Berlin");

        Instant onTransitionDay = halfPastTwo.nextAfter(Instant.parse("2026-03-28T12:00:00Z"));

        assertThat(onTransitionDay).isEqualTo(Instant.parse("2026-03-29T01:30:00Z"));
        assertThat(halfPastTwo.nextAfter(onTransitionDay)).isEqualTo(Instant.parse("2026-03-30T00:30:00Z"));
    }

    @Test
    void aTimeThatOccursTwiceOnTheFallBackDayFiresOnlyOnce() {
        CronSchedule halfPastTwo = CronSchedule.of("30 2 * * *", "Europe/Berlin");

        Instant first = halfPastTwo.nextAfter(Instant.parse("2026-10-24T12:00:00Z"));

        assertThat(first).isEqualTo(Instant.parse("2026-10-25T00:30:00Z"));
        assertThat(halfPastTwo.nextAfter(first)).isEqualTo(Instant.parse("2026-10-26T01:30:00Z"));
    }

    @Test
    void fromInsideTheRepeatedHourTheNextRunIsNotInThePast() {
        CronSchedule halfPastTwo = CronSchedule.of("30 2 * * *", "Europe/Berlin");

        assertThat(halfPastTwo.nextAfter(Instant.parse("2026-10-25T01:10:00Z")))
                .as("02:10 in the second, winter-time pass of the repeated hour")
                .isEqualTo(Instant.parse("2026-10-26T01:30:00Z"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "0 0 8 * * *", "0 8 * *", "61 * * * *", "@daily", "every day", "" })
    void onlyValidFiveFieldExpressionsAreAccepted(String cron) {
        assertThat(CronSchedule.isValidCron(cron)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = { "0 8 * * *", "*/15 * * * *", "0 9 * * MON", "30 6 1 * *", "0 8 * * 1-5" })
    void commonExpressionsAreAccepted(String cron) {
        assertThat(CronSchedule.isValidCron(cron)).isTrue();
    }

    @Test
    void onlyIanaZoneNamesAreAccepted() {
        assertThat(CronSchedule.isValidZone("Europe/Berlin")).isTrue();
        assertThat(CronSchedule.isValidZone("UTC")).isTrue();
        assertThat(CronSchedule.isValidZone("+02:00")).isFalse();
        assertThat(CronSchedule.isValidZone("Mars/Olympus")).isFalse();
        assertThat(CronSchedule.isValidZone(null)).isFalse();
        assertThatThrownBy(() -> CronSchedule.of("0 8 * * *", "Berlin"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
