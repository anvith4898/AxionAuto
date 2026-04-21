package com.axion.auth.domain.dto.rules;

import com.axion.auth.domain.entity.AutomationRule;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record RuleRequest(
        @NotBlank String name,
        @NotNull AutomationRule.TriggerType triggerType,
        List<String> keywords,
        @NotBlank String replyText,
        @Min(1) Integer priority,
        @Min(0) Long cooldownSeconds
) {
}
