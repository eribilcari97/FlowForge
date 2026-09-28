package com.flowforge.service.engine;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.flowforge.repository.JobQueue;
import com.flowforge.repository.JobQueue.ClaimedJob;

@Component
@EnableConfigurationProperties(RecoveryProperties.class)
public class RecoveryTask {

    private static final Logger log = LoggerFactory.getLogger(RecoveryTask.class);

    private final JobQueue queue;
    private final ExecutionCoordinator coordinator;
    private final RecoveryProperties properties;

    public RecoveryTask(JobQueue queue, ExecutionCoordinator coordinator, RecoveryProperties properties) {
        this.queue = queue;
        this.coordinator = coordinator;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${flowforge.recovery.interval}", initialDelayString = "${flowforge.recovery.interval}")
    void scheduledRecovery() {
        if (properties.enabled()) {
            recoverExpiredLeases();
        }
    }

    public int recoverExpiredLeases() {
        List<ClaimedJob> expired = queue.findExpiredLeases(properties.batchSize());
        for (ClaimedJob job : expired) {
            try {
                coordinator.recordAbandoned(job);
                log.warn("Recovered job {} (attempt {}) of execution {} after its lease expired",
                        job.stepKey(), job.attemptNumber(), job.executionId());
            } catch (RuntimeException e) {
                log.error("Could not recover job {}", job.id(), e);
            }
        }
        return expired.size();
    }
}
