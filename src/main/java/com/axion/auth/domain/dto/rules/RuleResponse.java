package com.axion.auth.domain.dto.rules;

import com.axion.auth.domain.entity.AutomationRule;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RuleResponse(
        UUID id,
        String name,
        AutomationRule.TriggerType triggerType,
        List<String> keywords,
        String replyText,
        boolean active,
        int priority,
        long cooldownSeconds,
        Instant createdAt
) {
}
