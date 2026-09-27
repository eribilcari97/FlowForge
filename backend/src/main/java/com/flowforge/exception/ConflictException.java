package com.flowforge.exception;

import org.springframework.http.HttpStatus;

public class ConflictException extends ApiException {

    public ConflictException(ErrorCode code, String detail) {
        super(HttpStatus.CONFLICT, code, detail);
    }
}
