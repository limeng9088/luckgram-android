package org.telegram.luckgram.quickreplies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

class QuickRepliesLogicTest {

    // "\u8c22\u8c22\uff0c\u6536\u5230\u3002" = "Thanks, got it." in Chinese, "\u8c22" = its first character
    private static final String THANKS_ZH = "\u8c22\u8c22\uff0c\u6536\u5230\u3002";
    private static final String ON_MY_WAY_ZH = "\u5728\u8def\u4e0a\u4e86\uff01";

    @Test
    void normalizeTrimsAndRejectsBlank() {
        assertEquals("On my way!", QuickRepliesLogic.normalize("  On my way!\n"));
        assertNull(QuickRepliesLogic.normalize(null));
        assertNull(QuickRepliesLogic.normalize(""));
        assertNull(QuickRepliesLogic.normalize(" \n\t "));
    }

    @Test
    void normalizeKeepsInnerLineBreaks() {
        assertEquals("line 1\nline 2", QuickRepliesLogic.normalize("line 1\nline 2"));
    }

    @Test
    void normalizeCutsToMaxLength() {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < QuickRepliesLogic.MAX_LENGTH + 50; i++) {
            sb.append('a');
        }
        assertEquals(QuickRepliesLogic.MAX_LENGTH, QuickRepliesLogic.normalize(sb).length());
    }

    @Test
    void normalizeDoesNotSplitAnEmoji() {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < QuickRepliesLogic.MAX_LENGTH - 1; i++) {
            sb.append('a');
        }
        sb.append("\ud83d\ude00"); // emoji straddling the limit
        final String result = QuickRepliesLogic.normalize(sb);
        assertEquals(QuickRepliesLogic.MAX_LENGTH - 1, result.length());
        assertFalse(Character.isHighSurrogate(result.charAt(result.length() - 1)));
    }

    @Test
    void limitIsOneHundred() {
        assertEquals(100, QuickRepliesLogic.MAX_COUNT);
        assertTrue(QuickRepliesLogic.canAdd(0));
        assertTrue(QuickRepliesLogic.canAdd(99));
        assertFalse(QuickRepliesLogic.canAdd(100)); // the 101st cannot be added
        assertFalse(QuickRepliesLogic.canAdd(150));
    }

    @Test
    void matchingIsPrefixAndCaseInsensitive() {
        final List<String> items = Arrays.asList("Thanks, got it.", "thank you", "On my way!", THANKS_ZH, ON_MY_WAY_ZH);
        assertEquals(Arrays.asList("Thanks, got it.", "thank you"), QuickRepliesLogic.matching(items, "THANK"));
        assertEquals(Collections.singletonList(THANKS_ZH), QuickRepliesLogic.matching(items, "\u8c22"));
        assertTrue(QuickRepliesLogic.matching(items, "got").isEmpty()); // not a prefix
        assertEquals(items, QuickRepliesLogic.matching(items, "")); // bare "/" shows all
        assertTrue(QuickRepliesLogic.matching(null, "a").isEmpty());
    }

    @Test
    void moveReordersAndIgnoresInvalidIndices() {
        final List<String> items = Arrays.asList("a", "b", "c", "d");
        assertEquals(Arrays.asList("c", "a", "b", "d"), QuickRepliesLogic.move(items, 2, 0));
        assertEquals(Arrays.asList("b", "c", "a", "d"), QuickRepliesLogic.move(items, 0, 2));
        assertEquals(items, QuickRepliesLogic.move(items, -1, 2));
        assertEquals(items, QuickRepliesLogic.move(items, 1, 4));
        assertEquals(Arrays.asList("a", "b", "c", "d"), items); // input untouched
    }
}
