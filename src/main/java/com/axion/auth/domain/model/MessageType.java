package com.axion.auth.domain.model;

/**
 * Canonical message type for normalized Instagram events.
 *
 * <ul>
 *   <li>{@link #DM}      – Direct message from a user to the business inbox.</li>
 *   <li>{@link #COMMENT} – Comment on a media object (story/post), e.g. @mention reply.</li>
 *   <li>{@link #UNKNOWN} – Webhook event that could not be classified; parked for review.</li>
 * </ul>
 */
public enum MessageType {

    DM,
    COMMENT,
    UNKNOWN;

    /**
     * Derives the MessageType from raw Instagram webhook fields.
     *
     * <p>Logic:
     * <ul>
     *   <li>Presence of a {@code messaging} array → {@link #DM}</li>
     *   <li>Presence of a {@code changes} array with {@code field=comments} → {@link #COMMENT}</li>
     *   <li>Anything else → {@link #UNKNOWN}</li>
     * </ul>
     *
     * @param hasMessagingField whether the entry contained a non-empty {@code messaging} list
     * @param hasCommentField   whether the entry contained a {@code changes.field == "comments"} entry
     * @return the resolved {@link MessageType}
     */
    public static MessageType resolve(boolean hasMessagingField, boolean hasCommentField) {
        if (hasMessagingField) {
            return DM;
        }
        if (hasCommentField) {
            return COMMENT;
        }
        return UNKNOWN;
    }
}
