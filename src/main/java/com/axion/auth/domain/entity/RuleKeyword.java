package com.axion.auth.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * A single normalised keyword token belonging to an {@link AutomationRule}.
 *
 * <p>Keywords are stored lowercase+trimmed at write-time.
 * At runtime, matching is performed against the already-normalised
 * {@link com.axion.auth.domain.model.MessageDTO#messageText()}.
 */
@Entity
@Table(
    name = "rule_keywords",
    uniqueConstraints = @UniqueConstraint(name = "uq_rule_keyword", columnNames = {"rule_id", "keyword"}),
    indexes = @Index(name = "idx_rule_keywords_rule", columnList = "rule_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RuleKeyword {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rule_id", nullable = false, updatable = false)
    private AutomationRule rule;

    /** Normalised keyword — lowercase, trimmed. Stored ready-to-match. */
    @Column(name = "keyword", nullable = false, length = 255)
    private String keyword;

    @PrePersist
    @PreUpdate
    void normalise() {
        if (this.keyword != null) {
            this.keyword = this.keyword.trim().toLowerCase();
        }
    }
}
