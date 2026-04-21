package com.axion.auth.domain.dto.webhook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * Deserialized representation of the Instagram/Facebook Messenger webhook payload.
 *
 * <p>Field coverage:
 * <ul>
 *   <li>{@code entry[].messaging[]} — Direct Message events</li>
 *   <li>{@code entry[].changes[]} — Comment / story-mention / media changes</li>
 * </ul>
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} on every class prevents
 * deserialization failures when Meta adds new fields in future Graph API versions.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class InstagramWebhookPayload {

    @JsonProperty("object")
    private String object;

    @JsonProperty("entry")
    private List<Entry> entry;

    // ── Entry ─────────────────────────────────────────────────────────────────

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Entry {

        /** Instagram Business Account ID that owns this inbox. */
        @JsonProperty("id")
        private String id;

        /** Unix epoch seconds for the batch timestamp. */
        @JsonProperty("time")
        private Long time;

        /** Present for DM events. */
        @JsonProperty("messaging")
        private List<Messaging> messaging;

        /**
         * Present for comment / story-mention / media change events.
         * Each change carries a {@code field} (e.g. {@code "comments"}) and a
         * {@code value} object with the comment detail.
         */
        @JsonProperty("changes")
        private List<Change> changes;
    }

    // ── Messaging (DM events) ─────────────────────────────────────────────────

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Messaging {

        @JsonProperty("sender")
        private Sender sender;

        @JsonProperty("recipient")
        private Recipient recipient;

        @JsonProperty("timestamp")
        private Long timestamp;

        @JsonProperty("message")
        private Message message;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Sender {
        @JsonProperty("id")
        private String id;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Recipient {
        @JsonProperty("id")
        private String id;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Message {

        /** Meta message ID — used as the idempotency key. */
        @JsonProperty("mid")
        private String mid;

        /** Text body of the message; null for media-only DMs. */
        @JsonProperty("text")
        private String text;

        /** True when the user has unsent (deleted) this message. */
        @JsonProperty("is_deleted")
        private Boolean isDeleted;

        /**
         * True when this message was sent by the business itself (bot echo).
         * Echo messages should be filtered out — they are not inbound events.
         */
        @JsonProperty("is_echo")
        private Boolean isEcho;

        /** Present when the DM contains an image, video, audio or file attachment. */
        @JsonProperty("attachments")
        private List<Attachment> attachments;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Attachment {
        /** e.g. "image", "video", "audio", "file", "template". */
        @JsonProperty("type")
        private String type;

        @JsonProperty("payload")
        private AttachmentPayload payload;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AttachmentPayload {
        @JsonProperty("url")
        private String url;
    }

    // ── Change (comment / story-mention events) ───────────────────────────────

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Change {
        /**
         * Identifies what changed, e.g. {@code "comments"}, {@code "mentions"},
         * {@code "story_insights"}.
         */
        @JsonProperty("field")
        private String field;

        /** The content of the change — structure depends on {@code field}. */
        @JsonProperty("value")
        private ChangeValue value;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ChangeValue {

        /** IGSID of the user who commented. */
        @JsonProperty("from")
        private Sender from;

        /** Comment body text. */
        @JsonProperty("text")
        private String text;

        /** Comment ID. */
        @JsonProperty("id")
        private String id;

        /** Media that was commented on. */
        @JsonProperty("media_id")
        private String mediaId;
    }
}

