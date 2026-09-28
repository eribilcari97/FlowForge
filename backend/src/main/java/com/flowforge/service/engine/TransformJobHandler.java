package com.flowforge.service.engine;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import com.dashjoin.jsonata.Jsonata;
import com.flowforge.entity.JobType;

@Component
public class TransformJobHandler implements JobHandler {

    private static final int MAX_RECURSION_DEPTH = 200;

    private final ObjectMapper objectMapper;

    public TransformJobHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public JobType type() {
        return JobType.TRANSFORM;
    }

    @Override
    public boolean isSafeToRepeat(JsonNode config) {
        return true;
    }

    @Override
    public JobResult execute(JsonNode config, JobContext context) {
        Jsonata expression;
        try {
            expression = Jsonata.jsonata(config.path("expression").asString(""));
        } catch (RuntimeException e) {
            return new JobResult.Failure(ErrorType.TRANSFORM_ERROR, "Invalid expression: " + e.getMessage());
        }
        Jsonata.Frame frame = expression.createFrame();
        frame.setRuntimeBounds(context.timeoutSeconds() * 1000L, MAX_RECURSION_DEPTH);
        try {
            Object data = objectMapper.convertValue(context.data(), Object.class);
            Object result = expression.evaluate(data, frame);
            JsonNode output = result == null
                    ? JsonNodeFactory.instance.nullNode()
                    : objectMapper.valueToTree(result);
            return new JobResult.Success(output);
        } catch (RuntimeException e) {
            return new JobResult.Failure(ErrorType.TRANSFORM_ERROR,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }
}
