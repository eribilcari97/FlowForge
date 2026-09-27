package com.flowforge.dto;

import java.time.Instant;

public record ProjectResponse(Long id, String name, String description, long workflowCount, Instant createdAt) {
}
