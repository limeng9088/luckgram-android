package org.telegram.luckgram.quickreplies;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class QuickRepliesLogicTest {

    // "\u8c22\u8c22\uff0c\u6536\u5230\u3002" = "Thanks, got it." in Chinese, "\u8c22" = its first character
    private static final String THANKS_ZH = "\u8c22\u8c22\uff0c\u6536\u5230\u3002";
    private static final String ON_MY_WAY_ZH = "\u5728\u8def\u4e0a\u4e86\uff01";

    @Test
    public void normalizeTrimsAndRejectsBlank() {
        assertEquals("On my way!", QuickRepliesLogic.normalize("  On my way!\n"));
        assertNull(QuickRepliesLogic.normalize(null));
        assertNull(QuickRepliesLogic.normalize(""));
        assertNull(QuickRepliesLogic.normalize(" \n\t "));
    }

    @Test
    public void normalizeKeepsInnerLineBreaks() {
        assertEquals("line 1\nline 2", QuickRepliesLogic.normalize("line 1\nline 2"));
    }

    @Test
    public void normalizeCutsToMaxLength() {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < QuickRepliesLogic.MAX_LENGTH + 50; i++) {
            sb.append('a');
        }
        assertEquals(QuickRepliesLogic.MAX_LENGTH, QuickRepliesLogic.normalize(sb).length());
    }

    // emoji used below, as UTF-16 escapes (source stays ASCII)
    private static final String GRIN = "\ud83d\ude00";                                  // grinning face, 2 chars
    private static final String FAMILY = "\ud83d\udc68\u200d\ud83d\udc69\u200d\ud83d\udc67"; // man ZWJ woman ZWJ girl, 8 chars
    private static final String THUMB_SKIN = "\ud83d\udc4d\ud83c\udffd";                // thumbs up + medium skin tone
    private static final String FLAG_CN = "\ud83c\udde8\ud83c\uddf3";                   // regional indicators C N
    private static final String RAINBOW_FLAG = "\ud83c\udff3\ufe0f\u200d\ud83c\udf08";  // white flag VS16 ZWJ rainbow
    private static final String KEYCAP_1 = "1\ufe0f\u20e3";
    private static final String E_ACUTE = "e\u0301";                                      // e + combining acute

    private static String repeat(String s, int times) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

    @Test
    public void graphemeCountTreatsEachEmojiAsOne() {
        assertEquals(1, QuickRepliesLogic.graphemeCount(GRIN));
        assertEquals(1, QuickRepliesLogic.graphemeCount(FAMILY));
        assertEquals(1, QuickRepliesLogic.graphemeCount(THUMB_SKIN));
        assertEquals(1, QuickRepliesLogic.graphemeCount(FLAG_CN));
        assertEquals(1, QuickRepliesLogic.graphemeCount(RAINBOW_FLAG));
        assertEquals(1, QuickRepliesLogic.graphemeCount(KEYCAP_1));
        assertEquals(1, QuickRepliesLogic.graphemeCount(E_ACUTE));
        assertEquals(2, QuickRepliesLogic.graphemeCount(FLAG_CN + FLAG_CN));
        assertEquals(1, QuickRepliesLogic.graphemeCount("\r\n"));
        assertEquals(5, QuickRepliesLogic.graphemeCount("ab" + GRIN + "\n" + FAMILY));
        assertEquals(6, QuickRepliesLogic.graphemeCount(THANKS_ZH)); // CJK + fullwidth punctuation
        assertEquals(0, QuickRepliesLogic.graphemeCount(""));
        assertEquals(0, QuickRepliesLogic.graphemeCount(null));
    }

    @Test
    public void thousandEmojiAreKept() {
        final String text = repeat(GRIN, QuickRepliesLogic.MAX_LENGTH); // 2000 UTF-16 chars
        assertEquals(text, QuickRepliesLogic.normalize(text));
    }

    @Test
    public void thousandAndOneEmojiAreCutToThousand() {
        final String result = QuickRepliesLogic.normalize(repeat(GRIN, QuickRepliesLogic.MAX_LENGTH + 1));
        assertEquals(QuickRepliesLogic.MAX_LENGTH, QuickRepliesLogic.graphemeCount(result));
        assertEquals(repeat(GRIN, QuickRepliesLogic.MAX_LENGTH), result);
    }

    @Test
    public void zwjSequenceAtTheLimitIsNotSplit() {
        // 999 letters + a family emoji fills exactly 1000; a second family emoji is dropped whole
        final String base = repeat("a", QuickRepliesLogic.MAX_LENGTH - 1);
        assertEquals(base + FAMILY, QuickRepliesLogic.normalize(base + FAMILY));
        assertEquals(base + FAMILY, QuickRepliesLogic.normalize(base + FAMILY + FAMILY));
        // the cut never leaves a dangling ZWJ or half a surrogate pair
        final String cut = QuickRepliesLogic.normalize(repeat("a", QuickRepliesLogic.MAX_LENGTH) + FAMILY);
        assertEquals(repeat("a", QuickRepliesLogic.MAX_LENGTH), cut);
    }

    @Test
    public void flagsAndSkinTonesAreNotSplit() {
        final String base = repeat("a", QuickRepliesLogic.MAX_LENGTH - 1);
        assertEquals(base + FLAG_CN, QuickRepliesLogic.normalize(base + FLAG_CN + FLAG_CN));
        assertEquals(base + THUMB_SKIN, QuickRepliesLogic.normalize(base + THUMB_SKIN + "b"));
        assertEquals(base + E_ACUTE, QuickRepliesLogic.normalize(base + E_ACUTE + "x"));
    }

    @Test
    public void inputFilterKeepsWhatFits() {
        final String dest = repeat("a", QuickRepliesLogic.MAX_LENGTH - 2);
        // pasting 3 emoji at the end of 998 letters keeps 2 emoji (4 chars)
        assertEquals(4, QuickRepliesLogic.fittingPrefix(repeat(GRIN, 3), dest, dest.length(), dest.length(), QuickRepliesLogic.MAX_LENGTH));
        // a family emoji is kept or dropped whole
        assertEquals(FAMILY.length() * 2, QuickRepliesLogic.fittingPrefix(FAMILY + FAMILY + FAMILY, dest, dest.length(), dest.length(), QuickRepliesLogic.MAX_LENGTH));
        // replacing a selection frees room
        assertEquals(1, QuickRepliesLogic.fittingPrefix("xyz", dest + "bb", 0, 1, QuickRepliesLogic.MAX_LENGTH));
        // full: nothing fits
        assertEquals(0, QuickRepliesLogic.fittingPrefix(GRIN, repeat("a", QuickRepliesLogic.MAX_LENGTH), QuickRepliesLogic.MAX_LENGTH, QuickRepliesLogic.MAX_LENGTH, QuickRepliesLogic.MAX_LENGTH));
    }

    @Test
    public void limitIsOneHundred() {
        assertEquals(100, QuickRepliesLogic.MAX_COUNT);
        assertTrue(QuickRepliesLogic.canAdd(0));
        assertTrue(QuickRepliesLogic.canAdd(99));
        assertFalse(QuickRepliesLogic.canAdd(100)); // the 101st cannot be added
        assertFalse(QuickRepliesLogic.canAdd(150));
    }

    @Test
    public void matchingIsPrefixAndCaseInsensitive() {
        final List<String> items = Arrays.asList("Thanks, got it.", "thank you", "On my way!", THANKS_ZH, ON_MY_WAY_ZH);
        assertEquals(Arrays.asList("Thanks, got it.", "thank you"), QuickRepliesLogic.matching(items, "THANK"));
        assertEquals(Collections.singletonList(THANKS_ZH), QuickRepliesLogic.matching(items, "\u8c22"));
        assertTrue(QuickRepliesLogic.matching(items, "got").isEmpty()); // not a prefix
        assertEquals(items, QuickRepliesLogic.matching(items, "")); // bare "/" shows all
        assertTrue(QuickRepliesLogic.matching(null, "a").isEmpty());
    }

    @Test
    public void moveReordersAndIgnoresInvalidIndices() {
        final List<String> items = Arrays.asList("a", "b", "c", "d");
        assertEquals(Arrays.asList("c", "a", "b", "d"), QuickRepliesLogic.move(items, 2, 0));
        assertEquals(Arrays.asList("b", "c", "a", "d"), QuickRepliesLogic.move(items, 0, 2));
        assertEquals(items, QuickRepliesLogic.move(items, -1, 2));
        assertEquals(items, QuickRepliesLogic.move(items, 1, 4));
        assertEquals(Arrays.asList("a", "b", "c", "d"), items); // input untouched
    }
}
