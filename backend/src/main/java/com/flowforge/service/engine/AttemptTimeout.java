package com.flowforge.service.engine;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

@Component
public class AttemptTimeout {

    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "flowforge-attempt-timeout");
        thread.setDaemon(true);
        return thread;
    });

    public JobResult run(int timeoutSeconds, Supplier<JobResult> attempt) {
        Alarm alarm = new Alarm(Thread.currentThread());
        timer.schedule(alarm::ring, timeoutSeconds, TimeUnit.SECONDS);
        JobResult result;
        try {
            result = attempt.get();
        } catch (RuntimeException e) {
            if (alarm.switchOff()) {
                return timeout(timeoutSeconds);
            }
            throw e;
        }
        if (alarm.switchOff() && !(result instanceof JobResult.Success)) {
            return timeout(timeoutSeconds);
        }
        return result;
    }

    @PreDestroy
    void shutdown() {
        timer.shutdownNow();
    }

    private static JobResult timeout(int timeoutSeconds) {
        return new JobResult.Failure(ErrorType.TIMEOUT, "No response within " + timeoutSeconds + " s");
    }

    private static final class Alarm {

        private final Thread attemptThread;
        private boolean finished;
        private boolean rang;

        Alarm(Thread attemptThread) {
            this.attemptThread = attemptThread;
        }

        synchronized void ring() {
            if (!finished) {
                rang = true;
                attemptThread.interrupt();
            }
        }

        synchronized boolean switchOff() {
            finished = true;
            Thread.interrupted();
            return rang;
        }
    }
}
