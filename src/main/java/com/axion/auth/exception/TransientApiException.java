package com.axion.auth.exception;

/**
 * Thrown when a Meta Graph API call fails with a retryable error
 * (e.g., 500, 502, 503, 504, or network timeout).
 */
public class TransientApiException extends RuntimeException {

    public TransientApiException(String message) {
        super(message);
    }

    public TransientApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
