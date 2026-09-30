package com.flowforge.service.engine;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.flowforge.repository.JobQueue;
import com.flowforge.repository.JobQueue.ClaimedJob;

@Component
@EnableConfigurationProperties(WorkerProperties.class)
public class JobWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);
    private static final long ERROR_BACKOFF_MS = 5000;
    private static final int MAX_HOST_NAME_LENGTH = 50;

    private final JobQueue queue;
    private final JobRunner runner;
    private final WorkerProperties properties;
    private final String instanceId;
    private final Object wakeUp = new Object();

    private volatile boolean running;
    private BlockingQueue<Integer> freeSlots;
    private ExecutorService jobThreads;
    private Thread pollThread;

    public JobWorker(JobQueue queue, JobRunner runner, WorkerProperties properties) {
        this.queue = queue;
        this.runner = runner;
        this.properties = properties;
        this.instanceId = properties.instanceId() != null
                ? properties.instanceId()
                : hostName() + ":" + ManagementFactory.getRuntimeMXBean().getPid();
    }

    @Override
    public boolean isAutoStartup() {
        return properties.enabled();
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        freeSlots = new LinkedBlockingQueue<>();
        for (int slot = 1; slot <= properties.threads(); slot++) {
            freeSlots.add(slot);
        }
        jobThreads = Executors.newFixedThreadPool(properties.threads());
        pollThread = new Thread(this::pollLoop, "flowforge-worker-poll");
        pollThread.start();
        log.info("Worker {} started with {} job threads", instanceId, properties.threads());
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        pollThread.interrupt();
        jobThreads.shutdown();
        try {
            pollThread.join(TimeUnit.SECONDS.toMillis(5));
            if (!jobThreads.awaitTermination(30, TimeUnit.SECONDS)) {
                jobThreads.shutdownNow();
            }
        } catch (InterruptedException e) {
            jobThreads.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Worker {} stopped", instanceId);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void pollLoop() {
        while (running) {
            try {
                claimForFreeSlots();
                waitForWork(properties.pollInterval().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.error("Worker poll failed, retrying in {} ms", ERROR_BACKOFF_MS, e);
                try {
                    Thread.sleep(ERROR_BACKOFF_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void claimForFreeSlots() {
        Integer slot;
        while ((slot = freeSlots.poll()) != null) {
            List<ClaimedJob> claimed;
            try {
                claimed = queue.claim(1, workerId(slot), properties.leaseGrace());
            } catch (RuntimeException e) {
                freeSlots.add(slot);
                throw e;
            }
            if (claimed.isEmpty()) {
                freeSlots.add(slot);
                return;
            }
            submit(claimed.getFirst(), slot);
        }
    }

    private String workerId(int slot) {
        return instanceId + ":" + threadName(slot);
    }

    private static String threadName(int slot) {
        return "flowforge-job-" + slot;
    }

    private void submit(ClaimedJob job, int slot) {
        jobThreads.execute(() -> {
            Thread.currentThread().setName(threadName(slot));
            try {
                runner.run(job);
            } finally {
                freeSlots.add(slot);
                synchronized (wakeUp) {
                    wakeUp.notifyAll();
                }
            }
        });
    }

    private void waitForWork(long timeoutMs) throws InterruptedException {
        synchronized (wakeUp) {
            wakeUp.wait(timeoutMs);
        }
    }

    private static String hostName() {
        try {
            String name = InetAddress.getLocalHost().getHostName();
            return name.length() > MAX_HOST_NAME_LENGTH ? name.substring(0, MAX_HOST_NAME_LENGTH) : name;
        } catch (UnknownHostException e) {
            return "unknown-host";
        }
    }
}
