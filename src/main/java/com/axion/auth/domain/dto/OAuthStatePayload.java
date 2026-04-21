package com.axion.auth.domain.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload stored in Redis for OAuth CSRF state validation.
 * Stored as JSON with a 10-minute TTL.
 *
 * <p>State key format in Redis: {@code oauth:state:{stateToken}}
 */
public record OAuthStatePayload(
        UUID tenantId,
        String userId,
        String stateToken,
        Instant createdAt,
        String redirectAfterCallback   // optional: where to redirect the user after success
) {

    public static OAuthStatePayload create(UUID tenantId, String userId,
                                           String stateToken, String redirectAfterCallback) {
        return new OAuthStatePayload(tenantId, userId, stateToken,
                Instant.now(), redirectAfterCallback);
    }
}
