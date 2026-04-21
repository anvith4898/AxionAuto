package com.axion.auth.domain.dto.instagram;

public record InstagramStatusResponse(
        boolean connected,
        String accountId,
        String igUsername
) {
}
