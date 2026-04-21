package com.axion.auth.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when any step of the OAuth 2.0 flow fails.
 * Subclasses distinguish permanent vs. transient failures.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class OAuthException extends RuntimeException {

    private final OAuthErrorCode errorCode;

    public OAuthException(OAuthErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public OAuthException(OAuthErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public OAuthErrorCode getErrorCode() {
        return errorCode;
    }

    public enum OAuthErrorCode {
        /** state parameter is missing, invalid, or expired (CSRF protection). */
        INVALID_STATE,

        /** Authorization code is expired or has already been used. */
        EXPIRED_CODE,

        /** Authorization code exchange with Meta API failed. */
        CODE_EXCHANGE_FAILED,

        /** Long-lived token exchange failed. */
        TOKEN_EXTENSION_FAILED,

        /** User granted the app but has no Instagram Business Account linked to any Facebook Page. */
        NO_INSTAGRAM_BUSINESS_ACCOUNT,

        /** User did not grant all required permissions. */
        MISSING_PERMISSIONS,

        /** Token refresh failed after max retries. */
        REFRESH_FAILED,

        /** The authorization was denied by the user. */
        ACCESS_DENIED,

        /** Generic / unexpected OAuth error. */
        UNEXPECTED_ERROR
    }
}
