package com.axion.auth.repository;

import com.axion.auth.domain.entity.Contact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ContactRepository extends JpaRepository<Contact, UUID> {

    Optional<Contact> findByTenantIdAndIgAccountIdAndSenderId(
            UUID tenantId, String igAccountId, String senderId);

    /**
     * Upsert a contact: insert if new, otherwise bump last_seen_at + interaction_count.
     * Uses PostgreSQL ON CONFLICT for atomicity.
     */
    @Modifying
    @Query(nativeQuery = true, value = """
        INSERT INTO contacts (id, tenant_id, ig_account_id, sender_id, first_seen_at, last_seen_at, interaction_count)
        VALUES (gen_random_uuid(), :tenantId, :igAccountId, :senderId, :now, :now, 1)
        ON CONFLICT ON CONSTRAINT uq_contact
        DO UPDATE SET
            last_seen_at       = EXCLUDED.last_seen_at,
            interaction_count  = contacts.interaction_count + 1
        """)
    void upsertContact(
            @Param("tenantId")    UUID    tenantId,
            @Param("igAccountId") String  igAccountId,
            @Param("senderId")    String  senderId,
            @Param("now")         Instant now);
}
