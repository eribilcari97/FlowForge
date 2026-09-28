package com.flowforge.exception;

import java.util.List;

public class InvalidFieldsException extends RuntimeException {

    private final List<GlobalExceptionHandler.FieldError> errors;

    public InvalidFieldsException(List<GlobalExceptionHandler.FieldError> errors) {
        super("Validation failed");
        this.errors = errors;
    }

    public List<GlobalExceptionHandler.FieldError> getErrors() {
        return errors;
    }
}
