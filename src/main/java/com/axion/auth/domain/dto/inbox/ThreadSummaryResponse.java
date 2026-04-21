package com.axion.auth.domain.dto.inbox;

public record ThreadSummaryResponse(
        String id,
        String senderName,
        String senderId,
        String preview,
        boolean unread,
        String updatedAt
) {
}
