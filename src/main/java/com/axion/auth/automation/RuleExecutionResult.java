package com.axion.auth.automation;

import com.axion.auth.domain.entity.AutomationExecutionLog.ExecutionStatus;

import java.util.UUID;

/**
 * Value object representing the outcome of a single rule execution.
 *
 * @param ruleId   ID of the rule that was evaluated
 * @param ruleName Human-readable rule name for logging
 * @param status   {@link ExecutionStatus} — SENT, SKIPPED, or FAILED
 * @param note     Optional explanation (e.g. "Cooldown active" for SKIPPED, error message for FAILED)
 */
public record RuleExecutionResult(
        UUID          ruleId,
        String        ruleName,
        ExecutionStatus status,
        String        note
) {
    public boolean wasSent()    { return status == ExecutionStatus.SENT;    }
    public boolean wasSkipped() { return status == ExecutionStatus.SKIPPED; }
    public boolean hasFailed()  { return status == ExecutionStatus.FAILED;  }
}
