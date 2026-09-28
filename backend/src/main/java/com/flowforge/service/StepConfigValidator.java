package com.flowforge.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

import com.flowforge.dto.DelayStepConfig;
import com.flowforge.dto.HttpStepConfig;
import com.flowforge.entity.JobType;
import com.flowforge.exception.GlobalExceptionHandler.FieldError;
import com.flowforge.exception.InvalidFieldsException;

@Component
public class StepConfigValidator {

    private static final Duration MIN_DELAY = Duration.ofSeconds(1);
    private static final Duration MAX_DELAY = Duration.ofDays(7);

    private final ObjectMapper objectMapper;
    private final Validator validator;

    public StepConfigValidator(ObjectMapper objectMapper, Validator validator) {
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    public void validate(JobType jobType, JsonNode config) {
        List<FieldError> errors = switch (jobType) {
            case HTTP -> validateHttp(config);
            case DELAY -> validateDelay(config);
        };
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
    }

    private List<FieldError> validateHttp(JsonNode config) {
        List<FieldError> errors = new ArrayList<>();
        HttpStepConfig http = read(config, HttpStepConfig.class, errors);
        if (http == null) {
            return errors;
        }
        errors.addAll(beanValidation(http));
        if (http.url() != null && !http.url().isBlank() && !isAbsoluteHttpUrl(http.url())) {
            errors.add(new FieldError("config.url", "must be an absolute http or https URL"));
        }
        return errors;
    }

    private List<FieldError> validateDelay(JsonNode config) {
        List<FieldError> errors = new ArrayList<>();
        DelayStepConfig delay = read(config, DelayStepConfig.class, errors);
        if (delay == null) {
            return errors;
        }
        errors.addAll(beanValidation(delay));
        if (delay.duration() != null && !delay.duration().isBlank() && !isAllowedDelay(delay.duration())) {
            errors.add(new FieldError("config.duration", "must be an ISO-8601 duration between PT1S and P7D"));
        }
        return errors;
    }

    private <T> T read(JsonNode config, Class<T> type, List<FieldError> errors) {
        if (config == null || !config.isObject()) {
            errors.add(new FieldError("config", "must be a JSON object"));
            return null;
        }
        try {
            return objectMapper.treeToValue(config, type);
        } catch (UnrecognizedPropertyException e) {
            errors.add(new FieldError(fieldPath(e), "unknown field"));
        } catch (DatabindException e) {
            errors.add(new FieldError(fieldPath(e), "has an invalid value"));
        }
        return null;
    }

    private List<FieldError> beanValidation(Object config) {
        return validator.validate(config).stream()
                .map(violation -> new FieldError("config." + propertyPath(violation), violation.getMessage()))
                .toList();
    }

    private static String propertyPath(ConstraintViolation<?> violation) {
        return violation.getPropertyPath().toString().replaceAll("\\.<[^>]+>", "").replaceAll("\\[(\\d+)]", ".$1");
    }

    private static String fieldPath(DatabindException e) {
        String path = e.getPath().stream()
                .map(reference -> reference.getPropertyName() != null
                        ? reference.getPropertyName()
                        : String.valueOf(reference.getIndex()))
                .collect(Collectors.joining("."));
        return path.isEmpty() ? "config" : "config." + path;
    }

    private static boolean isAbsoluteHttpUrl(String url) {
        String withoutPlaceholders = url.replaceAll("\\{\\{[^}]*}}", "placeholder");
        try {
            URI uri = new URI(withoutPlaceholders);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static boolean isAllowedDelay(String duration) {
        try {
            Duration parsed = Duration.parse(duration);
            return parsed.compareTo(MIN_DELAY) >= 0 && parsed.compareTo(MAX_DELAY) <= 0;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
