package com.axion.auth.normalization;

import com.axion.auth.domain.dto.webhook.InstagramWebhookPayload;
import com.axion.auth.domain.dto.webhook.InstagramWebhookPayload.Change;
import com.axion.auth.domain.dto.webhook.InstagramWebhookPayload.Entry;
import com.axion.auth.domain.dto.webhook.InstagramWebhookPayload.Message;
import com.axion.auth.domain.dto.webhook.InstagramWebhookPayload.Messaging;
import com.axion.auth.domain.model.MessageDTO;
import com.axion.auth.domain.model.MessageType;
import com.axion.auth.exception.WebhookParseException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Converts raw {@link InstagramWebhookPayload} objects into canonical {@link MessageDTO} lists.
 *
 * <h3>Design principles:</h3>
 * <ul>
 *   <li><strong>Null-safe at every field</strong> — uses explicit null checks instead of relying
 *       on payload correctness from Meta.</li>
 *   <li><strong>Best-effort extraction</strong> — skips individual malformed events and logs
 *       them; continues processing remaining valid events in the batch.</li>
 *   <li><strong>No side effects</strong> — pure transformation; DLQ routing is handled by
 *       the calling {@link WebhookNormalizationService}, not here.</li>
 *   <li><strong>Echo filtering</strong> — messages sent by the business itself
 *       ({@code message.is_echo=true}) are silently dropped; they are not inbound events.</li>
 * </ul>
 *
 * <h3>Supported payload shapes:</h3>
 * <ul>
 *   <li>{@code entry[].messaging[]} — Direct Message events (type = {@link MessageType#DM})</li>
 *   <li>{@code entry[].changes[].field == "comments"} — Comment events
 *       (type = {@link MessageType#COMMENT})</li>
 *   <li>Entries without either → {@link MessageType#UNKNOWN}</li>
 * </ul>
 */
@Slf4j
@Component
public class WebhookPayloadParser {

    /** Max allowed text length before truncation (mirrors Meta's DM limit). */
    private static final int MAX_TEXT_LENGTH = 2048;

    /** Change field value that indicates a comment on a media object. */
    private static final String COMMENT_FIELD = "comments";

    /**
     * Parses a complete {@link InstagramWebhookPayload} into a flat list of {@link MessageDTO}.
     *
     * <p>Skips (and logs) invalid entries. Never returns null; returns empty list if
     * nothing could be extracted.
     *
     * @param payload deserialized webhook payload, may be null
     * @return immutable list of successfully parsed {@link MessageDTO}s
     */
    public List<MessageDTO> parse(InstagramWebhookPayload payload) {
        if (payload == null) {
            log.warn("[parser] Received null webhook payload — nothing to parse");
            return Collections.emptyList();
        }
        if (CollectionUtils.isEmpty(payload.getEntry())) {
            log.warn("[parser] Webhook payload has no entries [object={}]", payload.getObject());
            return Collections.emptyList();
        }

        List<MessageDTO> results = new ArrayList<>();

        for (Entry entry : payload.getEntry()) {
            try {
                results.addAll(parseEntry(entry));
            } catch (WebhookParseException e) {
                // Log and skip — do not let one bad entry poison the batch
                log.warn("[parser] Skipping unparseable webhook entry [entryId={}, reason={}]",
                        safeId(entry), e.getMessage());
            } catch (Exception e) {
                log.error("[parser] Unexpected error parsing webhook entry [entryId={}]",
                        safeId(entry), e);
            }
        }

        log.debug("[parser] Parsed {} MessageDTOs from {} entries",
                results.size(), payload.getEntry().size());

        return Collections.unmodifiableList(results);
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Parses one {@link Entry} from the webhook payload.
     *
     * <p>Priority:
     * <ol>
     *   <li>If {@code messaging} is present → parse as DM events</li>
     *   <li>Else if {@code changes} contains a {@code "comments"} field → parse as comment events</li>
     *   <li>Else → produce a single {@link MessageType#UNKNOWN} placeholder for DLQ routing</li>
     * </ol>
     */
    private List<MessageDTO> parseEntry(Entry entry) {
        if (entry == null) {
            throw new WebhookParseException("Entry is null", "unknown");
        }

        String igAccountId = nullToEmpty(entry.getId());

        // ── DM events ──────────────────────────────────────────────────────
        if (!CollectionUtils.isEmpty(entry.getMessaging())) {
            List<MessageDTO> dtos = new ArrayList<>(entry.getMessaging().size());
            for (Messaging messaging : entry.getMessaging()) {
                try {
                    MessageDTO dto = parseMessaging(messaging, igAccountId);
                    if (dto != null) {  // null means echo — silently dropped
                        dtos.add(dto);
                    }
                } catch (WebhookParseException e) {
                    log.warn("[parser] Skipping messaging event [entryId={}, reason={}]",
                            igAccountId, e.getMessage());
                }
            }
            return dtos;
        }

        // ── Comment / story-mention events ─────────────────────────────────
        if (!CollectionUtils.isEmpty(entry.getChanges())) {
            List<MessageDTO> dtos = new ArrayList<>();
            for (Change change : entry.getChanges()) {
                try {
                    if (change != null && COMMENT_FIELD.equals(change.getField())) {
                        dtos.add(parseCommentChange(change, igAccountId, entry.getTime()));
                    } else {
                        log.debug("[parser] Skipping non-comment change [field={}, entryId={}]",
                                change != null ? change.getField() : "null", igAccountId);
                    }
                } catch (WebhookParseException e) {
                    log.warn("[parser] Skipping unparseable change [entryId={}, reason={}]",
                            igAccountId, e.getMessage());
                }
            }
            return dtos;
        }

        // ── Unknown event shape → produce a parkable UNKNOWN DTO ──────────
        log.debug("[parser] Entry [id={}] has no messaging or changes — producing UNKNOWN event",
                igAccountId);

        Instant timestamp = entry.getTime() != null
                ? Instant.ofEpochSecond(entry.getTime())
                : Instant.now();

        // Use a synthetic senderId that won't fail the compact constructor's non-blank check.
        // The validator will reject this DTO (UNKNOWN type) and park it to DLQ.
        String syntheticEventId = "unknown-" + (igAccountId.isBlank() ? UUID.randomUUID() : igAccountId);
        return List.of(new MessageDTO(
                "000000",           // synthetic senderId — passes blank check, fails IGSID format check in validator
                igAccountId,        // recipientId
                "",                 // messageId
                "",                 // messageText
                MessageType.UNKNOWN,
                timestamp,
                syntheticEventId,   // unique rawEventId so DLQ entries don't clobber each other
                igAccountId,
                false,
                false
        ));
    }

    /**
     * Parses a single {@link Messaging} event into a {@link MessageDTO}.
     *
     * @return the parsed DTO, or {@code null} if the message is an echo (should be dropped)
     * @throws WebhookParseException if the event is structurally invalid
     */
    private MessageDTO parseMessaging(Messaging messaging, String igAccountId) {
        if (messaging == null) {
            throw new WebhookParseException("Messaging object is null", igAccountId);
        }

        // ── Echo guard ────────────────────────────────────────────────────
        Message message = messaging.getMessage();
        if (message != null && Boolean.TRUE.equals(message.getIsEcho())) {
            log.debug("[parser] Dropping echo message from igAccountId={}", igAccountId);
            return null;
        }

        // ── Sender ────────────────────────────────────────────────────────
        String senderId = messaging.getSender() != null
                ? nullToEmpty(messaging.getSender().getId())
                : "";

        if (senderId.isBlank()) {
            throw new WebhookParseException(
                    "Missing sender.id — cannot create actionable MessageDTO", igAccountId);
        }

        // ── Recipient ─────────────────────────────────────────────────────
        String recipientId = messaging.getRecipient() != null
                ? nullToEmpty(messaging.getRecipient().getId())
                : "";

        // ── Timestamp ─────────────────────────────────────────────────────
        Instant timestamp;
        if (messaging.getTimestamp() != null) {
            // Meta timestamps are epoch milliseconds
            timestamp = Instant.ofEpochMilli(messaging.getTimestamp());
        } else {
            log.warn("[parser] Missing timestamp in messaging event for sender {}; defaulting to now",
                    senderId);
            timestamp = Instant.now();
        }

        // ── Message body ──────────────────────────────────────────────────
        String rawText         = message != null ? message.getText() : null;
        String messageId       = message != null ? nullToEmpty(message.getMid()) : "";
        boolean isDeleted      = message != null && Boolean.TRUE.equals(message.getIsDeleted());
        boolean hasAttachment  = message != null
                && !CollectionUtils.isEmpty(message.getAttachments());

        // Normalize text through the full pipeline
        String normalizedText = MessageTextNormalizer.normalize(rawText);
        normalizedText        = MessageTextNormalizer.truncate(normalizedText, MAX_TEXT_LENGTH);

        if (isDeleted) {
            log.debug("[parser] Message {} is marked deleted; clearing text", messageId);
            normalizedText = "";
        }

        // ── Message type ──────────────────────────────────────────────────
        // A messaging[] event is always a DM at this payload level.
        // Comment classification comes from entry.changes[].field == "comments".
        MessageType type = MessageType.DM;

        // ── rawEventId: prefer mid for true idempotency; synthesize when absent ──
        String rawEventId = messageId.isBlank()
                ? igAccountId + "-" + timestamp.toEpochMilli()
                : messageId;

        return new MessageDTO(
                senderId,
                recipientId,
                messageId,
                normalizedText,
                type,
                timestamp,
                rawEventId,
                igAccountId,
                isDeleted,
                hasAttachment
        );
    }

    /**
     * Parses a single comment {@link Change} event into a {@link MessageDTO}.
     *
     * @throws WebhookParseException if the change value is missing required fields
     */
    private MessageDTO parseCommentChange(Change change, String igAccountId, Long entryEpochSeconds) {
        if (change.getValue() == null) {
            throw new WebhookParseException("Change value is null", igAccountId);
        }

        var value = change.getValue();

        // Sender is in value.from.id for comment events
        String senderId = (value.getFrom() != null)
                ? nullToEmpty(value.getFrom().getId())
                : "";

        if (senderId.isBlank()) {
            throw new WebhookParseException(
                    "Missing from.id in comment change — cannot create actionable MessageDTO",
                    igAccountId);
        }

        String commentId   = nullToEmpty(value.getId());
        String rawText     = value.getText();
        String normalized  = MessageTextNormalizer.normalize(rawText);
        normalized         = MessageTextNormalizer.truncate(normalized, MAX_TEXT_LENGTH);

        Instant timestamp = entryEpochSeconds != null
                ? Instant.ofEpochSecond(entryEpochSeconds)
                : Instant.now();

        // rawEventId = comment ID for deduplication; fall back to synthetic key
        String rawEventId = commentId.isBlank()
                ? igAccountId + "-comment-" + timestamp.toEpochMilli()
                : commentId;

        return new MessageDTO(
                senderId,
                igAccountId,    // recipient = the business account
                commentId,      // messageId = the comment ID
                normalized,
                MessageType.COMMENT,
                timestamp,
                rawEventId,
                igAccountId,
                false,          // comments can't be "deleted" in the same way
                false           // comments have no attachment list in this payload shape
        );
    }

    /** Safe null-to-empty-string coercion. */
    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    /** Safe entry ID extraction for logging. */
    private static String safeId(Entry entry) {
        return entry != null ? nullToEmpty(entry.getId()) : "unknown";
    }
}
