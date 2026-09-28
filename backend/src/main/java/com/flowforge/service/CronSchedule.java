package com.flowforge.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.scheduling.support.CronExpression;

public final class CronSchedule {

    private static final int MAX_SEARCH_STEPS = 100;

    private final CronExpression expression;
    private final ZoneId zone;

    private CronSchedule(CronExpression expression, ZoneId zone) {
        this.expression = expression;
        this.zone = zone;
    }

    public static CronSchedule of(String cronExpression, String timezone) {
        return new CronSchedule(parseCron(cronExpression), parseZone(timezone));
    }

    public static boolean isValidCron(String cronExpression) {
        try {
            parseCron(cronExpression);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static boolean isValidZone(String timezone) {
        return timezone != null && ZoneId.getAvailableZoneIds().contains(timezone);
    }

    public Instant nextAfter(Instant time) {
        LocalDateTime localTime = LocalDateTime.ofInstant(time, zone);
        for (int i = 0; i < MAX_SEARCH_STEPS; i++) {
            LocalDateTime nextLocalTime = expression.next(localTime);
            if (nextLocalTime == null) {
                break;
            }
            Instant candidate = nextLocalTime.atZone(zone).toInstant();
            if (candidate.isAfter(time)) {
                return candidate;
            }
            localTime = nextLocalTime;
        }
        throw new IllegalStateException("The cron expression never fires again");
    }

    private static CronExpression parseCron(String cronExpression) {
        if (cronExpression == null || cronExpression.isBlank()) {
            throw new IllegalArgumentException("Cron expression is required");
        }
        String[] fields = cronExpression.trim().split("\\s+");
        if (fields.length != 5) {
            throw new IllegalArgumentException("Cron expression must have 5 fields: minute hour day month weekday");
        }
        return CronExpression.parse("0 " + String.join(" ", fields));
    }

    private static ZoneId parseZone(String timezone) {
        if (!isValidZone(timezone)) {
            throw new IllegalArgumentException("Unknown time zone: " + timezone);
        }
        return ZoneId.of(timezone);
    }
}
