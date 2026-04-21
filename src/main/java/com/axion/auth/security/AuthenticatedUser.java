package com.axion.auth.security;

import java.util.UUID;

public record AuthenticatedUser(
        String userId,
        UUID tenantId,
        String email,
        String name,
        String role
) {
}
