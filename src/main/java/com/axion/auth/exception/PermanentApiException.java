package com.axion.auth.exception;

/**
 * Thrown when a Meta Graph API call fails with a non-retryable error
 * (e.g., 400 Bad Request, 401 Unauthorized, 403 Forbidden).
 */
public class PermanentApiException extends RuntimeException {

    public PermanentApiException(String message) {
        super(message);
    }

    public PermanentApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
