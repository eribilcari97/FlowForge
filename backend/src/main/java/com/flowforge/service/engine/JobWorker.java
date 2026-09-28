package com.flowforge.service.engine;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

    private final JobQueue queue;
    private final JobRunner runner;
    private final WorkerProperties properties;
    private final String workerId;
    private final Object wakeUp = new Object();

    private volatile boolean running;
    private Semaphore freeThreads;
    private ExecutorService jobThreads;
    private Thread pollThread;

    public JobWorker(JobQueue queue, JobRunner runner, WorkerProperties properties) {
        this.queue = queue;
        this.runner = runner;
        this.properties = properties;
        this.workerId = hostName() + ":" + ManagementFactory.getRuntimeMXBean().getPid();
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
        freeThreads = new Semaphore(properties.threads());
        AtomicInteger threadNumber = new AtomicInteger();
        jobThreads = Executors.newFixedThreadPool(properties.threads(),
                task -> new Thread(task, "flowforge-job-" + threadNumber.incrementAndGet()));
        pollThread = new Thread(this::pollLoop, "flowforge-worker-poll");
        pollThread.start();
        log.info("Worker {} started with {} job threads", workerId, properties.threads());
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
        log.info("Worker {} stopped", workerId);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void pollLoop() {
        while (running) {
            try {
                int free = freeThreads.availablePermits();
                if (free > 0) {
                    List<ClaimedJob> jobs = queue.claim(free, workerId, properties.leaseGrace());
                    jobs.forEach(this::submit);
                }
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

    private void submit(ClaimedJob job) {
        freeThreads.acquireUninterruptibly();
        jobThreads.execute(() -> {
            try {
                runner.run(job);
            } finally {
                freeThreads.release();
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
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown-host";
        }
    }
}
