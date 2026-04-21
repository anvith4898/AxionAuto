package com.axion.auth.domain.dto.auth;

import java.util.UUID;

public record AuthSessionResponse(
        String token,
        String userId,
        UUID tenantId,
        String name,
        String role,
        String email
) {
}
