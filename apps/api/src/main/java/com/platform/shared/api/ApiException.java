package com.platform.shared.api;

import java.util.Map;

public class ApiException extends RuntimeException {
    private final ErrorCode code;
    private final Map<String, Object> details;

    public ApiException(ErrorCode code, String message) { this(code, message, Map.of()); }

    public ApiException(ErrorCode code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details;
    }

    public ErrorCode code() { return code; }
    public Map<String, Object> details() { return details; }

    public static ApiException notFound(String what) { return new ApiException(ErrorCode.NOT_FOUND, what + " not found"); }
    public static ApiException badRequest(String msg) { return new ApiException(ErrorCode.BAD_REQUEST, msg); }
    public static ApiException conflict(String msg) { return new ApiException(ErrorCode.CONFLICT, msg); }
    public static ApiException invalidState(String msg) { return new ApiException(ErrorCode.INVALID_STATE, msg); }
    public static ApiException forbidden(String msg) { return new ApiException(ErrorCode.FORBIDDEN, msg); }
}
