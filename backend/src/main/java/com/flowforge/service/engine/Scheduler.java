package com.flowforge.service.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableConfigurationProperties(SchedulerProperties.class)
public class Scheduler {

    private static final Logger log = LoggerFactory.getLogger(Scheduler.class);

    private final ScheduleFirer firer;
    private final SchedulerProperties properties;

    public Scheduler(ScheduleFirer firer, SchedulerProperties properties) {
        this.firer = firer;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${flowforge.scheduler.interval}", initialDelayString = "${flowforge.scheduler.interval}")
    void scheduledTick() {
        if (properties.enabled()) {
            tick();
        }
    }

    public int tick() {
        int fired = 0;
        try {
            while (fired < properties.maxRunsPerTick() && firer.fireNextDue()) {
                fired++;
            }
        } catch (RuntimeException e) {
            log.error("Scheduler tick failed after {} due schedules", fired, e);
        }
        return fired;
    }
}
