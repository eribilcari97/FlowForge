package com.flowforge.service.engine;

public enum ErrorType {
    CONNECTION_ERROR,
    TIMEOUT,
    HTTP_4XX,
    HTTP_429,
    HTTP_5XX,
    UNEXPECTED_STATUS,
    INVALID_CONFIG,
    PLACEHOLDER_MISSING,
    OUTPUT_TOO_LARGE,
    LEASE_EXPIRED,
    UNEXPECTED_ERROR
}
