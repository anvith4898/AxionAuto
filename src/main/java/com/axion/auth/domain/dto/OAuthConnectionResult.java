package com.axion.auth.domain.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Successful result of the OAuth flow, returned to the controller / API caller.
 * Does NOT expose the access token — only metadata about the connected account.
 */
public record OAuthConnectionResult(
        UUID tokenId,
        UUID tenantId,
        String userId,
        String instagramAccountId,
        String instagramUsername,
        Instant tokenExpiry,
        String status,
        boolean isNewConnection,
        String redirectAfterCallback
) {}
