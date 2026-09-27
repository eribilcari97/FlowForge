package com.flowforge.exception;

import java.util.List;
import java.util.Map;

import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;


@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record FieldError(String field, String message) {
    }

    private static final Map<String, ConstraintConflict> CONSTRAINT_CONFLICTS = Map.of(
            "uq_user_email", new ConstraintConflict(ErrorCode.EMAIL_TAKEN, "Email is already registered"),
            "uq_project_owner_name", new ConstraintConflict(ErrorCode.DUPLICATE_NAME, "A project with this name already exists"));

    private record ConstraintConflict(ErrorCode code, String detail) {
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldError(error.getField(), error.getDefaultMessage()))
                .toList();

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "Validation failed");
        problem.setProperty("code", ErrorCode.VALIDATION_ERROR.name());
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    /** Every Spring MVC exception handled by the base class passes through here. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem && !hasCode(problem)) {
            problem.setProperty("code", codeFor(statusCode));
        }
        return response;
    }

    @ExceptionHandler(ApiException.class)
    ProblemDetail handleApiException(ApiException ex) {
        return problem(ex.getStatus(), ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(problem(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHENTICATED, "Authentication required"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        if (ex.getCause() instanceof ConstraintViolationException violation
                && CONSTRAINT_CONFLICTS.get(violation.getConstraintName()) instanceof ConstraintConflict conflict) {
            return problem(HttpStatus.CONFLICT, conflict.code(), conflict.detail());
        }
        return handleUnexpected(ex);
    }

    /** Anything not handled above. The real cause goes to the log only, never to the client. */
    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        problem.setProperty("code", ErrorCode.INTERNAL_ERROR.name());
        return problem;
    }

    private static ProblemDetail problem(HttpStatus status, ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code.name());
        return problem;
    }

    private static boolean hasCode(ProblemDetail problem) {
        Map<String, Object> properties = problem.getProperties();
        return properties != null && properties.containsKey("code");
    }

    /**
     * Framework errors without a documented code fall back to the HTTP status name,
     * for example {@code METHOD_NOT_ALLOWED}.
     */
    private static String codeFor(HttpStatusCode status) {
        if (status.value() == 400) {
            return ErrorCode.MALFORMED_REQUEST.name();
        }
        if (status.value() == 404) {
            return ErrorCode.NOT_FOUND.name();
        }
        if (status.is5xxServerError()) {
            return ErrorCode.INTERNAL_ERROR.name();
        }
        HttpStatus known = HttpStatus.resolve(status.value());
        return known != null ? known.name() : "HTTP_" + status.value();
    }
}
