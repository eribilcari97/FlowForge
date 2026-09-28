package com.flowforge.service.engine;

public record JobContext(long jobId, int attemptNumber, int timeoutSeconds) {
}
