package com.platform.shared.api;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    BAD_REQUEST(HttpStatus.BAD_REQUEST),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),
    MFA_REQUIRED(HttpStatus.FORBIDDEN),
    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    CONFLICT(HttpStatus.CONFLICT),
    INVALID_STATE(HttpStatus.CONFLICT),
    PLAN_FEATURE_REQUIRED(HttpStatus.PAYMENT_REQUIRED),
    SUBSCRIPTION_INACTIVE(HttpStatus.PAYMENT_REQUIRED),
    INSUFFICIENT_BALANCE(HttpStatus.PAYMENT_REQUIRED),
    PROVIDER_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE),
    PROVIDER_ERROR(HttpStatus.BAD_GATEWAY),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    POLICY_BLOCKED(HttpStatus.UNPROCESSABLE_ENTITY),
    UNSUPPORTED_IN_COUNTRY(HttpStatus.UNPROCESSABLE_ENTITY),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE),
    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) { this.status = status; }

    public HttpStatus status() { return status; }
}
