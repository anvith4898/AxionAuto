package com.axion.auth.normalization;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link MessageTextNormalizer}.
 * No Spring context required — pure Java.
 */
@DisplayName("MessageTextNormalizer")
class MessageTextNormalizerTest {

    // ── normalize(String) ────────────────────────────────────────────────────

    @Nested
    @DisplayName("normalize(raw)")
    class Normalize {

        @ParameterizedTest(name = "returns empty string for [{0}]")
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t", "\u00A0"})
        void nullBlankInputReturnsEmpty(String input) {
            assertThat(MessageTextNormalizer.normalize(input)).isEmpty();
        }

        @Test
        @DisplayName("lowercases ASCII text")
        void lowercasesText() {
            assertThat(MessageTextNormalizer.normalize("Hello WORLD!"))
                    .isEqualTo("hello world!");
        }

        @Test
        @DisplayName("collapses multiple spaces into one")
        void collapsesWhitespace() {
            assertThat(MessageTextNormalizer.normalize("hello    world"))
                    .isEqualTo("hello world");
        }

        @Test
        @DisplayName("trims leading and trailing whitespace")
        void trimsEdges() {
            assertThat(MessageTextNormalizer.normalize("  hello  "))
                    .isEqualTo("hello");
        }

        @Test
        @DisplayName("strips zero-width spaces")
        void stripsZeroWidthChars() {
            // \u200B = Zero Width Space, \u200C = ZWNJ, \u200D = ZWJ, \uFEFF = BOM
            String noisy = "he\u200Bllo\u200C wor\u200Dld\uFEFF";
            assertThat(MessageTextNormalizer.normalize(noisy))
                    .isEqualTo("hello world");
        }

        @Test
        @DisplayName("strips soft hyphen and directional marks")
        void stripsSoftHyphenAndDirectionalMarks() {
            // \u00AD = Soft Hyphen, \u200E = LRM, \u200F = RLM
            String noisy = "hel\u00ADlo\u200E wor\u200Fld";
            assertThat(MessageTextNormalizer.normalize(noisy))
                    .isEqualTo("hello world");
        }

        @Test
        @DisplayName("collapses non-breaking spaces")
        void collapsesNbsp() {
            String nbspText = "hello\u00A0world";
            assertThat(MessageTextNormalizer.normalize(nbspText))
                    .isEqualTo("hello world");
        }

        @Test
        @DisplayName("preserves emoji by default")
        void preservesEmojiByDefault() {
            String withEmoji = "Hello \uD83D\uDE00 world";
            String result = MessageTextNormalizer.normalize(withEmoji);
            assertThat(result).containsIgnoringCase("hello");
            // emoji bytes should still be present when stripping is disabled
            assertThat(result).contains("\uD83D\uDE00");
        }

        @Test
        @DisplayName("full pipeline: trim + collapse + lowercase + noise strip")
        void fullPipeline() {
            String raw = "  \u200BHello   WORLD\u00AD!  ";
            assertThat(MessageTextNormalizer.normalize(raw))
                    .isEqualTo("hello world!");
        }

        @Test
        @DisplayName("normalizes Windows CRLF line endings to single space")
        void crlfNormalized() {
            assertThat(MessageTextNormalizer.normalize("hello\r\nworld"))
                    .isEqualTo("hello world");
        }

        @Test
        @DisplayName("normalizes bare CR to single space")
        void bareCrNormalized() {
            assertThat(MessageTextNormalizer.normalize("hello\rworld"))
                    .isEqualTo("hello world");
        }
    }

    // ── normalize(raw, stripEmojis=true) ────────────────────────────────────

    @Nested
    @DisplayName("normalize(raw, stripEmojis=true)")
    class NormalizeWithEmojiStrip {

        @Test
        @DisplayName("removes common emoticon emoji")
        void stripsEmoticons() {
            String raw = "hi \uD83D\uDE00 there";
            assertThat(MessageTextNormalizer.normalize(raw, true))
                    .isEqualTo("hi there");
        }

        @Test
        @DisplayName("non-emoji text is unchanged")
        void nonEmojiTextUnchanged() {
            assertThat(MessageTextNormalizer.normalize("hello world", true))
                    .isEqualTo("hello world");
        }
    }

    // ── isEffectivelyEmpty ───────────────────────────────────────────────────

    @Nested
    @DisplayName("isEffectivelyEmpty")
    class IsEffectivelyEmpty {

        @Test
        @DisplayName("returns true for null")
        void nullIsEmpty() {
            assertThat(MessageTextNormalizer.isEffectivelyEmpty(null)).isTrue();
        }

        @Test
        @DisplayName("returns true for blank string")
        void blankIsEmpty() {
            assertThat(MessageTextNormalizer.isEffectivelyEmpty("   ")).isTrue();
        }

        @Test
        @DisplayName("returns false for non-blank")
        void nonBlankNotEmpty() {
            assertThat(MessageTextNormalizer.isEffectivelyEmpty("hi")).isFalse();
        }
    }

    // ── truncate ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("truncate")
    class Truncate {

        @Test
        @DisplayName("returns text unchanged when within limit")
        void withinLimit() {
            assertThat(MessageTextNormalizer.truncate("hello", 10)).isEqualTo("hello");
        }

        @Test
        @DisplayName("truncates at maxLength and appends ellipsis")
        void truncatesLongText() {
            String text = "a".repeat(100);
            String result = MessageTextNormalizer.truncate(text, 10);
            assertThat(result).hasSize(10);
            assertThat(result).endsWith("…");
        }

        @Test
        @DisplayName("returns empty string for null input (not null)")
        void nullInputReturnsEmpty() {
            // truncate(null) must return "" to be consistent with normalize()'s null contract
            assertThat(MessageTextNormalizer.truncate(null, 10)).isEmpty();
        }

        @Test
        @DisplayName("throws IllegalArgumentException for maxLength < 1")
        void invalidMaxLengthThrows() {
            assertThatThrownBy(() -> MessageTextNormalizer.truncate("hello", 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxLength must be >= 1");
        }
    }
}
