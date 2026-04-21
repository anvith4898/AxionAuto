package com.axion.auth.exception;

/**
 * Thrown when AES-256-GCM encryption or decryption of a token fails.
 * Always wraps the underlying {@link javax.crypto.AEADBadTagException} or similar.
 */
public class TokenEncryptionException extends RuntimeException {

    public TokenEncryptionException(String message) {
        super(message);
    }

    public TokenEncryptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
