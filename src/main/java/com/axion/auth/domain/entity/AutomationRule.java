package com.axion.auth.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Persistent definition of a single automation rule.
 *
 * <h3>Trigger types</h3>
 * <ul>
 *   <li>{@code WELCOME}  – fires once for first-interaction contacts</li>
 *   <li>{@code KEYWORD}  – fires when the message text contains a matching keyword</li>
 *   <li>{@code FALLBACK} – fires when no other rule matched (catch-all)</li>
 * </ul>
 *
 * <h3>Execution modes</h3>
 * <ul>
 *   <li>{@code FIRST_MATCH} – only the highest-priority matching rule fires</li>
 *   <li>{@code RUN_ALL}     – every matching rule fires, ordered by {@link #priority}</li>
 * </ul>
 *
 * <p>Rules are loaded into the in-memory {@code RuleCache} on startup and on any
 * cache-invalidation event; the DB is the source of truth but is rarely queried at runtime.
 */
@Entity
@Table(
    name = "automation_rules",
    indexes = @Index(
        name = "idx_rules_tenant_account_active",
        columnList = "tenant_id, ig_account_id, active, priority"
    )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AutomationRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id",    nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "ig_account_id", nullable = false, length = 255)
    private String igAccountId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /** Discriminator for rule evaluation strategy. */
    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 50)
    private TriggerType triggerType;

    /** Conflict-resolution mode. Stored per-rule; the engine uses the account-level default. */
    @Enumerated(EnumType.STRING)
    @Column(name = "execution_mode", nullable = false, length = 50)
    @Builder.Default
    private ExecutionMode executionMode = ExecutionMode.FIRST_MATCH;

    /** The reply text template to send. Supports {sender_id} placeholder for personalisation. */
    @Column(name = "reply_text", nullable = false, columnDefinition = "TEXT")
    private String replyText;

    /**
     * Lower number = higher priority in FIRST_MATCH mode.
     * Default 100 puts rules behind WELCOME (priority 0) and intentional overrides.
     */
    @Column(name = "priority", nullable = false)
    @Builder.Default
    private int priority = 100;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;

    /**
     * Minimum seconds between re-firing this rule for the same sender.
     * {@code 0} disables cooldown (fire every time).
     */
    @Column(name = "cooldown_seconds", nullable = false)
    @Builder.Default
    private long cooldownSeconds = 3600L;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    /**
     * Keywords associated with this rule (only relevant for {@link TriggerType#KEYWORD}).
     * Loaded eagerly because they are always needed when evaluating a rule.
     */
    @OneToMany(mappedBy = "rule", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @Builder.Default
    private List<RuleKeyword> keywords = new ArrayList<>();

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // ── Nested enums ──────────────────────────────────────────────────────────

    public enum TriggerType {
        WELCOME,
        KEYWORD,
        FALLBACK
    }

    public enum ExecutionMode {
        /** Stop after the first rule that matches (ordered by priority asc). */
        FIRST_MATCH,
        /** Execute every matching rule, ordered by priority. */
        RUN_ALL
    }
}
