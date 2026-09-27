package com.flowforge.exception;

import org.springframework.http.HttpStatus;

public class NotFoundException extends ApiException {

    public NotFoundException(String resource) {
        super(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, resource + " not found");
    }
}
