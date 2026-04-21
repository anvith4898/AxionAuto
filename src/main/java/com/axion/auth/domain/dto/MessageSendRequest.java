package com.axion.auth.domain.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body sent to the Meta Graph API to deliver an Instagram Direct Message.
 *
 * <h3>Idempotency via {@code request_id}</h3>
 * <p>Meta's Graph API supports idempotent sends when a caller-generated {@code request_id}
 * (UUID) is included. If the same {@code request_id} is re-submitted within the dedup window,
 * Meta will return the original response without sending a duplicate message. This is critical
 * for safe retries — without it, exponential-backoff retries could send the same DM multiple times.
 *
 * <h3>Messaging type</h3>
 * <p>Meta requires {@code messaging_type} to be present for non-response messages. For
 * automation replies that are direct responses to a user's inbound message, use
 * {@link MessagingType#RESPONSE}. For proactive (non-response) messages use
 * {@link MessagingType#MESSAGE_TAG} with the appropriate tag.
 *
 * @see <a href="https://developers.facebook.com/docs/messenger-platform/send-messages">
 *     Meta Messenger Platform — Send Messages</a>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MessageSendRequest(

        /** The recipient's Instagram-Scoped ID (IGSID). */
        Recipient recipient,

        /** The message content payload. */
        Message message,

        /**
         * Caller-generated UUID for idempotent retries.
         * Meta deduplicates sends with the same request_id within a short window.
         * Include this on every send so Resilience4j retries are safe.
         */
        @JsonProperty("request_id")
        String requestId,

        /**
         * Controls which Meta policy gate the message passes through.
         * Automation replies to inbound DMs must use {@link MessagingType#RESPONSE}.
         */
        @JsonProperty("messaging_type")
        MessagingType messagingType

) {
    /** Simplified constructor for direct-response automation replies. */
    public MessageSendRequest(Recipient recipient, Message message, String requestId) {
        this(recipient, message, requestId, MessagingType.RESPONSE);
    }

    /** Legacy constructor — kept for call-sites that do not supply a requestId. */
    public MessageSendRequest(Recipient recipient, Message message) {
        this(recipient, message, null, MessagingType.RESPONSE);
    }

    /**
     * Static factory that constructs a standard RESPONSE-type DM request.
     * Preferred over the raw record constructor at call-sites for readability.
     *
     * @param recipientId Instagram-Scoped ID of the message recipient
     * @param text        DM body text (max 1 000 characters per Meta spec)
     * @param requestId   caller-generated UUID string for idempotent retries
     * @return a fully-formed {@link MessageSendRequest}
     */
    public static MessageSendRequest of(String recipientId, String text, String requestId) {
        return new MessageSendRequest(
                new Recipient(recipientId),
                new Message(text),
                requestId,
                MessagingType.RESPONSE
        );
    }

    // ── Nested types ─────────────────────────────────────────────────────────

    public record Recipient(
            @JsonProperty("id") String id
    ) {}

    public record Message(
            @JsonProperty("text") String text
    ) {}

    public enum MessagingType {
        /** A message sent in direct response to a user's message within 24 hours. */
        RESPONSE,
        /** A message outside the 24-hour window using a sanctioned message tag. */
        MESSAGE_TAG,
        /** Used for sponsored messages — requires additional permissions. */
        UPDATE
    }
}
