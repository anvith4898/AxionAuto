package com.axion.auth.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Tracks every unique sender that has contacted this business account.
 *
 * <p>{@link #firstSeenAt} is set once on INSERT and drives the one-time
 * Welcome-message trigger in the automation engine.
 */
@Entity
@Table(
    name = "contacts",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_contact",
        columnNames = {"tenant_id", "ig_account_id", "sender_id"}
    ),
    indexes = {
        @Index(name = "idx_contacts_tenant_account", columnList = "tenant_id, ig_account_id"),
        @Index(name = "idx_contacts_sender",         columnList = "tenant_id, sender_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Contact {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id",     nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "ig_account_id", nullable = false, updatable = false, length = 255)
    private String igAccountId;

    @Column(name = "sender_id",     nullable = false, updatable = false, length = 255)
    private String senderId;

    @Column(name = "first_seen_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant firstSeenAt = Instant.now();

    @Column(name = "last_seen_at",  nullable = false)
    @Builder.Default
    private Instant lastSeenAt = Instant.now();

    @Column(name = "interaction_count", nullable = false)
    @Builder.Default
    private long interactionCount = 1L;

    @Column(name = "last_read_at")
    private Instant lastReadAt;

    /**
     * Returns {@code true} if this is the very first interaction with this sender.
     * Used to gate the Welcome-message trigger.
     */
    public boolean isFirstInteraction() {
        return interactionCount == 1L;
    }
}
