package com.axion.auth.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable audit record of every automation-rule execution attempt.
 *
 * <p>This table is the idempotency store: before firing a rule the engine checks
 * whether a row exists for {@code (tenantId, igAccountId, senderId, ruleId)}
 * within the rule's {@code cooldownSeconds} window.
 */
@Entity
@Table(
    name = "automation_execution_log",
    indexes = @Index(
        name = "idx_exec_log_cooldown",
        columnList = "tenant_id, ig_account_id, sender_id, rule_id, executed_at DESC"
    )
)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AutomationExecutionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id",    nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "ig_account_id", nullable = false, updatable = false, length = 255)
    private String igAccountId;

    @Column(name = "sender_id",    nullable = false, updatable = false, length = 255)
    private String senderId;

    @Column(name = "rule_id",      nullable = false, updatable = false)
    private UUID ruleId;

    /** The {@code mid} (message ID) that triggered this execution — for traceability. */
    @Column(name = "message_id",   updatable = false, length = 255)
    private String messageId;

    @Column(name = "executed_at",  nullable = false, updatable = false)
    @Builder.Default
    private Instant executedAt = Instant.now();

    @Enumerated(EnumType.STRING)
    @Column(name = "status",       nullable = false, length = 50)
    @Builder.Default
    private ExecutionStatus status = ExecutionStatus.SENT;

    public enum ExecutionStatus {
        SENT,
        SKIPPED,   // duplicate within cooldown window
        FAILED
    }
}
