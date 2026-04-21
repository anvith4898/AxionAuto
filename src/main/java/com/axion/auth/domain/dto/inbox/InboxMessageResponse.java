package com.axion.auth.domain.dto.inbox;

public record InboxMessageResponse(
        String id,
        String text,
        String direction,
        String timestamp
) {
}
