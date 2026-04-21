package com.axion.auth.domain.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The response envelope returned by Meta Graph API after a successful DM send.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} ensures this record remains
 * compatible if Meta introduces new fields in a future API version.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MessageSendResponse(
        @JsonProperty("recipient_id") String recipientId,
        @JsonProperty("message_id")  String messageId
) {
}
