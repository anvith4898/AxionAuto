package com.axion.auth.normalization;

import java.util.regex.Pattern;

/**
 * Pure, stateless text normalization utilities for inbound Instagram message text.
 *
 * <p>All methods are {@code static} and side-effect free, making them trivially
 * unit-testable without any Spring context or mocking.
 *
 * <h3>Normalization pipeline (applied in order):</h3>
 * <ol>
 *   <li>Null-guard → return empty string for null/blank input</li>
 *   <li>CR/LF normalization — {@code \r\n} and lone {@code \r} collapsed to a single space
 *       (prevents stray carriage-return characters surviving the whitespace-collapse step)</li>
 *   <li>Zero-width / invisible noise removal (zero-width space, ZWNJ, ZWJ, BOM,
 *       soft-hyphen, LRM, RLM, bidirectional override characters)</li>
 *   <li>Unicode whitespace normalization — collapse runs of whitespace
 *       (including NBSP, thin space, narrow NBSP) to a single ASCII space</li>
 *   <li>Lowercase conversion (locale-independent, suitable for keyword matching)</li>
 *   <li>Emoji strip (optional — disabled by default, enable via {@link #normalize(String, boolean)})</li>
 *   <li>Final trim</li>
 * </ol>
 */
public final class MessageTextNormalizer {

    // ── Noise patterns ───────────────────────────────────────────────────────

    /**
     * Zero-width characters, soft hyphen, BOM, directional marks, and bidi overrides.
     * These are invisible in most UIs but can confuse keyword-matching rules.
     */
    private static final Pattern ZERO_WIDTH_NOISE =
            Pattern.compile("[\\u200B-\\u200D\\u00AD\\uFEFF\\u200E\\u200F\\u202A-\\u202E]");

    /**
     * Collapse any whitespace run — including tabs, NBSP, various Unicode spaces —
     * into a single ASCII space.
     *
     * <p>Note: {@code \r} and {@code \r\n} are normalized first (step 2) to prevent
     * a lone {@code \r} surviving through {@code \s} matching only in UNIX mode.
     */
    private static final Pattern WHITESPACE_RUN =
            Pattern.compile("[\\s\\u00A0\\u2007\\u202F\\u2009\\u2008\\u200A\\u3000]+");

    /**
     * CR-only and CRLF line endings — replaced with a space before whitespace collapse.
     * Without this step, {@code "hello\r\nworld"} would become {@code "hello\rworld"}
     * after {@code \n} is consumed by the whitespace collapse, leaving a bare {@code \r}.
     */
    private static final Pattern CR_LF = Pattern.compile("\\r\\n?");

    /**
     * Unicode emoji coverage — surrogate-pair ranges plus supplementary BMP blocks.
     *
     * <p>Covers:
     * <ul>
     *   <li>U+1F000–U+1FFFF via surrogate pairs (Emoticons, Misc Symbols &amp; Pictographs,
     *       Transport, Supplemental Symbols, Mahjong/Domino tiles, etc.)</li>
     *   <li>U+2600–U+27FF — Misc Symbols, Dingbats, Arrows</li>
     *   <li>U+2B00–U+2BFF — Misc Symbols and Arrows</li>
     *   <li>U+FE00–U+FE0F — Variation selectors (emoji vs text presentation)</li>
     *   <li>U+20D0–U+20FF — Combining marks used in emoji sequences</li>
     * </ul>
     *
     * <p>Emoji sequences using ZWJ or variation selectors may leave residual spaces after
     * stripping; the final whitespace collapse and trim step cleans those up.
     */
    private static final Pattern EMOJI = Pattern.compile(
            "[\\uD83C\\uDF00-\\uD83D\\uDDFF]"  // Misc Symbols & Pictographs, Emoticons (low half)
            + "|[\\uD83E\\uDD00-\\uD83E\\uDFFF]"// Supplemental Symbols & Pictographs (new emoji)
            + "|[\\uD83D\\uDE00-\\uD83D\\uDEFF]"// Emoticons + Transport & Map
            + "|[\\uD83C\\uDDE0-\\uD83C\\uDDFF]"// Regional Indicator Symbols (flag sequences)
            + "|[\\uD83C\\uDC00-\\uD83C\\uDFFF]"// Mahjong, Domino, Playing Card, enclosed
            + "|[\\u2600-\\u27FF]"               // Misc Symbols, Dingbats, Arrows
            + "|[\\u2B00-\\u2BFF]"               // Misc Symbols and Arrows Supplement
            + "|[\\uFE00-\\uFE0F]"               // Variation selectors
            + "|[\\u20D0-\\u20FF]"               // Combining Diacritical Marks for Symbols
    );

    private MessageTextNormalizer() {
        // utility class — no instances
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Normalizes message text through the full pipeline (emojis preserved).
     *
     * @param raw the raw {@code message.text} string from the webhook payload, may be null
     * @return normalized string, never null; empty string if input was null/blank
     */
    public static String normalize(String raw) {
        return normalize(raw, false);
    }

    /**
     * Normalizes message text with optional emoji stripping.
     *
     * @param raw         the raw text, may be null
     * @param stripEmojis if {@code true}, emoji characters are removed
     * @return normalized string, never null
     */
    public static String normalize(String raw, boolean stripEmojis) {
        if (raw == null || raw.isBlank()) {
            return "";
        }

        String result = raw;

        // 1. Normalize CR/CRLF → space (must happen before whitespace collapse)
        result = CR_LF.matcher(result).replaceAll(" ");

        // 2. Strip invisible / zero-width noise characters
        result = ZERO_WIDTH_NOISE.matcher(result).replaceAll("");

        // 3. Collapse whitespace runs to single space
        result = WHITESPACE_RUN.matcher(result).replaceAll(" ");

        // 4. Lowercase (locale-independent, suitable for matching rules)
        result = result.toLowerCase();

        // 5. Emoji stripping (optional — disabled by default to preserve meaning)
        if (stripEmojis) {
            result = EMOJI.matcher(result).replaceAll("");
            // Re-collapse whitespace after emoji removal (stripping can leave double spaces)
            result = WHITESPACE_RUN.matcher(result).replaceAll(" ");
        }

        // 6. Final trim after all replacements
        return result.trim();
    }

    /**
     * Returns {@code true} if the normalized text is considered "empty" — useful
     * to decide whether an event carries actionable text content.
     *
     * @param normalizedText text already processed through {@link #normalize}
     */
    public static boolean isEffectivelyEmpty(String normalizedText) {
        return normalizedText == null || normalizedText.isBlank();
    }

    /**
     * Truncates normalized text to a safe max length, appending an ellipsis if cut.
     * Prevents downstream systems from receiving unbounded strings.
     *
     * <p><b>Null-safety:</b> unlike the previous version which returned {@code null}
     * for null input, this method now returns an empty string ({@code ""}) for null
     * input to be consistent with the rest of the normalization pipeline.
     *
     * @param text      already normalized text (may be null)
     * @param maxLength maximum number of characters to retain (must be ≥ 1)
     * @return truncated string, never null
     * @throws IllegalArgumentException if {@code maxLength} < 1
     */
    public static String truncate(String text, int maxLength) {
        if (maxLength < 1) {
            throw new IllegalArgumentException("maxLength must be >= 1, got: " + maxLength);
        }
        // Null → empty, consistent with normalize()
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 1) + "…";
    }
}
