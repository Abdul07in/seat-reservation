package com.paytm.assignment.constant;

public final class ApiErrorCode {

    public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    public static final String INVALID_REQUEST = "INVALID_REQUEST";
    public static final String RESOURCE_NOT_FOUND = "RESOURCE_NOT_FOUND";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String AUTHENTICATION_FAILED = "AUTHENTICATION_FAILED";
    public static final String EMAIL_ALREADY_EXISTS = "EMAIL_ALREADY_EXISTS";
    public static final String SEAT_UNAVAILABLE = "SEAT_UNAVAILABLE";
    public static final String USER_SEAT_LIMIT_EXCEEDED = "USER_SEAT_LIMIT_EXCEEDED";
    public static final String IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";

    private ApiErrorCode() {
    }
}
