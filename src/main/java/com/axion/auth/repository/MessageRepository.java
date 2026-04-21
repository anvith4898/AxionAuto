package com.axion.auth.repository;

import com.axion.auth.domain.entity.MessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<MessageEntity, UUID> {

    Optional<MessageEntity> findByTenantIdAndIgAccountIdAndMetaMessageId(
            UUID tenantId, String igAccountId, String metaMessageId);

    List<MessageEntity> findByTenantIdAndIgAccountIdAndSenderIdOrderBySentAtAsc(
            UUID tenantId, String igAccountId, String senderId);

    @Query("""
            SELECT m FROM MessageEntity m
            WHERE m.tenantId = :tenantId
              AND m.igAccountId = :igAccountId
              AND m.sentAt = (
                    SELECT MAX(m2.sentAt) FROM MessageEntity m2
                    WHERE m2.tenantId = :tenantId
                      AND m2.igAccountId = :igAccountId
                      AND m2.senderId = m.senderId
              )
            ORDER BY m.sentAt DESC
            """)
    List<MessageEntity> findLatestMessagesPerSender(
            @Param("tenantId") UUID tenantId,
            @Param("igAccountId") String igAccountId);

    @Query("""
            SELECT COUNT(m) FROM MessageEntity m
            WHERE m.tenantId = :tenantId
              AND m.igAccountId = :igAccountId
              AND m.direction = com.axion.auth.domain.entity.MessageEntity.Direction.INBOUND
              AND (:lastReadAt IS NULL OR m.sentAt > :lastReadAt)
            """)
    long countUnreadForAccount(
            @Param("tenantId") UUID tenantId,
            @Param("igAccountId") String igAccountId,
            @Param("lastReadAt") Instant lastReadAt);
}
