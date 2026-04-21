package com.axion.auth.domain.model;

import java.time.Instant;

/**
 * Canonical, fully normalized representation of an inbound Instagram message event.
 *
 * <p>All fields are guaranteed non-null after successful validation. {@code messageText}
 * may be empty only for non-text events (e.g. media-only DMs) — callers should check
 * {@link #hasText()} before processing text-based automation rules.
 *
 * <p>This record is immutable and safe to share across threads.
 *
 * @param senderId          IGSID of the message sender (always present for DMs).
 * @param recipientId       IGSID of the receiving business account (page-scoped ID).
 * @param messageId         Meta-assigned message ID ({@code mid}). Empty for comment events.
 * @param messageText       Normalized message text: trimmed, lowercased, noise-stripped.
 *                          Empty string if the DM contains no text (sticker/media/etc).
 * @param messageType       Canonical {@link MessageType} enum.
 * @param timestamp         UTC instant derived from the webhook epoch-millisecond timestamp.
 * @param rawEventId        Idempotency key — the original webhook {@code mid} for DMs, or
 *                          the {@code changes.value.id} for comments. Used for deduplication.
 * @param igAccountId       The Instagram Business Account ID that owns this inbox (entry.id).
 * @param isDeleted         True if the user unsent this message. Text is empty, but the
 *                          event is still delivered. Callers may want to retract automation
 *                          replies triggered in response.
 * @param hasAttachment     True if the message body contains one or more media attachments
 *                          (image, video, audio, file). {@code messageText} may still be
 *                          non-empty if the user also typed a caption.
 */
public record MessageDTO(
        String      senderId,
        String      recipientId,
        String      messageId,
        String      messageText,
        MessageType messageType,
        Instant     timestamp,
        String      rawEventId,
        String      igAccountId,
        boolean     isDeleted,
        boolean     hasAttachment
) {

    // ── Compact canonical constructor for defensive null guards ──────────────

    public MessageDTO {
        // nudge callers towards correct usage without hiding bugs
        if (senderId == null || senderId.isBlank()) {
            throw new IllegalArgumentException("MessageDTO.senderId must not be null/blank");
        }
        if (messageType == null) {
            throw new IllegalArgumentException("MessageDTO.messageType must not be null");
        }
        if (timestamp == null) {
            throw new IllegalArgumentException("MessageDTO.timestamp must not be null");
        }

        // Safe defaults for optional fields
        messageText   = messageText   != null ? messageText   : "";
        messageId     = messageId     != null ? messageId     : "";
        recipientId   = recipientId   != null ? recipientId   : "";
        rawEventId    = rawEventId    != null ? rawEventId    : "";
        igAccountId   = igAccountId   != null ? igAccountId   : "";
    }

    // ── Backward-compatible convenience constructor (no deletion/attachment flags) ──

    /**
     * Convenience constructor for call-sites that don't need the deletion or
     * attachment flags (e.g. test builders, legacy callers).
     */
    public MessageDTO(
            String      senderId,
            String      recipientId,
            String      messageId,
            String      messageText,
            MessageType messageType,
            Instant     timestamp,
            String      rawEventId,
            String      igAccountId) {
        this(senderId, recipientId, messageId, messageText,
             messageType, timestamp, rawEventId, igAccountId,
             false, false);
    }

    // ── Convenience helpers ──────────────────────────────────────────────────

    /** Returns {@code true} if the message contains non-empty normalized text. */
    public boolean hasText() {
        return !messageText.isBlank();
    }

    /** Returns {@code true} if this message was sent via the DM channel. */
    public boolean isDm() {
        return MessageType.DM == messageType;
    }

    /** Returns {@code true} if this is a comment/mention type event. */
    public boolean isComment() {
        return MessageType.COMMENT == messageType;
    }

    /**
     * Returns {@code true} if this event carries actionable content for automation.
     *
     * <p>An event is actionable when it is not deleted, has a known type, and either
     * has text or has an attachment. UNKNOWN-type events are never actionable.
     */
    public boolean isActionable() {
        return !isDeleted
                && messageType != MessageType.UNKNOWN
                && (hasText() || hasAttachment);
    }
}
