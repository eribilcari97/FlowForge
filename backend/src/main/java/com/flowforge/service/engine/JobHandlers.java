package com.flowforge.service.engine;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.flowforge.entity.JobType;

@Component
public class JobHandlers {

    private final Map<JobType, JobHandler> handlers = new EnumMap<>(JobType.class);

    public JobHandlers(List<JobHandler> allHandlers) {
        allHandlers.forEach(handler -> handlers.put(handler.type(), handler));
        for (JobType type : JobType.values()) {
            if (!handlers.containsKey(type)) {
                throw new IllegalStateException("No job handler for " + type);
            }
        }
    }

    public JobHandler forType(JobType type) {
        return handlers.get(type);
    }
}
