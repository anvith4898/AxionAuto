package com.axion.auth.repository;

import com.axion.auth.domain.entity.WebhookEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface WebhookEventRepository extends JpaRepository<WebhookEventEntity, UUID> {

    boolean existsByEventId(String eventId);

    /**
     * Looks up a webhook event by its idempotency event ID.
     * Used by {@link com.axion.auth.integration.WebhookEventStatusUpdater} to update status.
     */
    Optional<WebhookEventEntity> findByEventId(String eventId);
}

