package com.axion.auth.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Persistent entity representing a connected Instagram Business Account OAuth token.
 *
 * <p>Security invariants:
 * <ul>
 *   <li>access_token is stored AES-256-GCM encrypted — never plaintext in DB</li>
 *   <li>Lombok's {@code @ToString.Exclude} on sensitive fields prevents accidental logging</li>
 *   <li>tenant_id is set at creation and NEVER updated (immutable tenancy)</li>
 * </ul>
 *
 * <p>Multi-tenancy: This table has PostgreSQL Row-Level Security enforced via the Flyway migration.
 */
@Entity
@Table(
        name = "instagram_oauth_tokens",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_tenant_ig_account",
                        columnNames = {"tenant_id", "instagram_account_id"}),
                @UniqueConstraint(name = "uq_tenant_user",
                        columnNames = {"tenant_id", "user_id"})
        },
        indexes = {
                @Index(name = "idx_ig_tokens_tenant",        columnList = "tenant_id"),
                @Index(name = "idx_ig_tokens_tenant_status", columnList = "tenant_id, status"),
                @Index(name = "idx_ig_tokens_expiry",        columnList = "token_expiry")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"accessTokenEncrypted", "accessTokenIv", "accessTokenTag"})
public class InstagramOAuthToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    // ── Multi-tenancy ────────────────────────────────────────────────────────
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false, length = 255)
    private String userId;

    // ── Instagram identifiers ─────────────────────────────────────────────────
    @Column(name = "instagram_account_id", length = 255)
    private String instagramAccountId;

    @Column(name = "instagram_username", length = 255)
    private String instagramUsername;

    // ── Encrypted token storage ───────────────────────────────────────────────
    /** AES-256-GCM ciphertext of the long-lived Meta access token. */
    @Column(name = "access_token_encrypted", nullable = false)
    private byte[] accessTokenEncrypted;

    /** 12-byte GCM nonce. Unique per encryption operation. */
    @Column(name = "access_token_iv", nullable = false)
    private byte[] accessTokenIv;

    /** 16-byte GCM authentication tag. Validates ciphertext integrity before decryption. */
    @Column(name = "access_token_tag", nullable = false)
    private byte[] accessTokenTag;

    // ── Token metadata ────────────────────────────────────────────────────────
    @Column(name = "token_expiry", nullable = false)
    private Instant tokenExpiry;

    @Column(name = "scope")
    private String scope;

    @Column(name = "token_type", length = 50)
    @Builder.Default
    private String tokenType = "bearer";

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    @Column(name = "connected_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant connectedAt = Instant.now();

    @Column(name = "last_refreshed_at")
    private Instant lastRefreshedAt;

    @Column(name = "refresh_attempts", nullable = false)
    @Builder.Default
    private int refreshAttempts = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    @Builder.Default
    private TokenStatus status = TokenStatus.ACTIVE;

    // ── Audit ─────────────────────────────────────────────────────────────────
    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // ── Domain methods ────────────────────────────────────────────────────────

    /** Returns true if the token will expire within the given threshold. */
    public boolean isExpiringSoon(java.time.Duration threshold) {
        return tokenExpiry != null && Instant.now().isAfter(tokenExpiry.minus(threshold));
    }

    /** Returns true if the token has already expired. */
    public boolean isExpired() {
        return tokenExpiry != null && Instant.now().isAfter(tokenExpiry);
    }

    public enum TokenStatus {
        ACTIVE,
        EXPIRED,
        REFRESH_FAILED,
        REVOKED
    }
}
