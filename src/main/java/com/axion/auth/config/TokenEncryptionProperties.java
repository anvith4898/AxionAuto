package com.axion.auth.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * AES-256-GCM token encryption configuration.
 * The encryption key is a 32-byte secret, base64-encoded, supplied via environment variable.
 *
 * <pre>
 * Generate a secure key:
 *   openssl rand -base64 32
 * Then set:
 *   TOKEN_ENCRYPTION_KEY=&lt;output&gt;
 * </pre>
 */
@Validated
@ConfigurationProperties(prefix = "axion.token")
public record TokenEncryptionProperties(

        @NotBlank(message = "TOKEN_ENCRYPTION_KEY environment variable is required. "
                + "Generate with: openssl rand -base64 32")
        String encryptionKey

) {
    /**
     * Safe representation — deliberately OMITS the key value.
     */
    @Override
    public String toString() {
        return "TokenEncryptionProperties{encryptionKey='[REDACTED]'}";
    }
}
