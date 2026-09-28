package com.flowforge.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

import com.dashjoin.jsonata.Jsonata;
import com.flowforge.dto.DelayStepConfig;
import com.flowforge.dto.EmailStepConfig;
import com.flowforge.dto.HttpStepConfig;
import com.flowforge.dto.TransformStepConfig;
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
            case TRANSFORM -> validateTransform(config);
            case EMAIL -> validateEmail(config);
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

    private List<FieldError> validateTransform(JsonNode config) {
        List<FieldError> errors = new ArrayList<>();
        TransformStepConfig transform = read(config, TransformStepConfig.class, errors);
        if (transform == null) {
            return errors;
        }
        errors.addAll(beanValidation(transform));
        if (transform.expression() != null && !transform.expression().isBlank()) {
            try {
                Jsonata.jsonata(transform.expression());
            } catch (RuntimeException e) {
                errors.add(new FieldError("config.expression", "is not a valid JSONata expression: " + e.getMessage()));
            }
        }
        return errors;
    }

    private List<FieldError> validateEmail(JsonNode config) {
        List<FieldError> errors = new ArrayList<>();
        EmailStepConfig email = read(config, EmailStepConfig.class, errors);
        if (email == null) {
            return errors;
        }
        errors.addAll(beanValidation(email));
        addressErrors("config.to", email.to(), errors);
        addressErrors("config.cc", email.cc(), errors);
        if (isBlank(email.text()) && isBlank(email.html())) {
            errors.add(new FieldError("config.text", "text or html is required"));
        }
        return errors;
    }

    private static void addressErrors(String field, List<String> addresses, List<FieldError> errors) {
        if (addresses == null) {
            return;
        }
        for (int i = 0; i < addresses.size(); i++) {
            String address = addresses.get(i);
            if (address != null && !address.isBlank() && !address.contains("{{") && !isEmailAddress(address)) {
                errors.add(new FieldError(field + "." + i, "must be an email address"));
            }
        }
    }

    private static boolean isEmailAddress(String address) {
        try {
            new InternetAddress(address, true);
            return address.contains("@");
        } catch (AddressException e) {
            return false;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
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
