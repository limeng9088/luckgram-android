package org.telegram.luckgram.translateout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class TranslateOutLogicTest {

    // ---- normalizeLanguage

    @Test
    public void normalizeDropsRegionAndLowercases() {
        assertEquals("pt", TranslateOutLogic.normalizeLanguage("pt_BR"));
        assertEquals("pt", TranslateOutLogic.normalizeLanguage("pt-BR"));
        assertEquals("es", TranslateOutLogic.normalizeLanguage("ES"));
        assertEquals("fr", TranslateOutLogic.normalizeLanguage(" fr "));
    }

    @Test
    public void normalizeMapsEveryChineseVariantToZh() {
        assertEquals("zh", TranslateOutLogic.normalizeLanguage("zh-CN"));
        assertEquals("zh", TranslateOutLogic.normalizeLanguage("zh_TW"));
        assertEquals("zh", TranslateOutLogic.normalizeLanguage("zh-Hans"));
        assertEquals("zh", TranslateOutLogic.normalizeLanguage("zh"));
    }

    @Test
    public void normalizeRejectsEmptyAndUnknown() {
        assertNull(TranslateOutLogic.normalizeLanguage(null));
        assertNull(TranslateOutLogic.normalizeLanguage(""));
        assertNull(TranslateOutLogic.normalizeLanguage("  "));
        assertNull(TranslateOutLogic.normalizeLanguage("und"));
        assertNull(TranslateOutLogic.normalizeLanguage("UND"));
    }

    // ---- recent languages

    @Test
    public void updateRecentPutsNewFirstWithoutDuplicates() {
        assertEquals(Arrays.asList("fr", "es", "de"), TranslateOutLogic.updateRecent(Arrays.asList("es", "fr", "de"), "fr"));
        assertEquals(Collections.singletonList("es"), TranslateOutLogic.updateRecent(null, "es"));
    }

    @Test
    public void updateRecentKeepsAtMostFive() {
        final List<String> recent = TranslateOutLogic.updateRecent(Arrays.asList("a", "b", "c", "d", "e"), "f");
        assertEquals(Arrays.asList("f", "a", "b", "c", "d"), recent);
        assertEquals(TranslateOutLogic.MAX_RECENT, recent.size());
    }

    @Test
    public void recentRoundTrips() {
        final List<String> recent = Arrays.asList("fr", "es", "ja");
        assertEquals(recent, TranslateOutLogic.parseRecent(TranslateOutLogic.joinRecent(recent)));
        assertEquals(Arrays.asList("fr", "es"), TranslateOutLogic.parseRecent("fr,,es,fr"));
        assertTrue(TranslateOutLogic.parseRecent(null).isEmpty());
        assertTrue(TranslateOutLogic.parseRecent("").isEmpty());
    }

    // ---- protected ranges (acceptance: @username, links, line breaks survive translation)

    private static List<String> covered(String text, List<int[]> occupied) {
        final ArrayList<String> out = new ArrayList<>();
        for (TranslateOutLogic.ProtectedRange r : TranslateOutLogic.protectedRanges(text, occupied)) {
            out.add((r.url ? "url:" : "mention:") + text.substring(r.start, r.end));
        }
        return out;
    }

    @Test
    public void protectsMentionsAndLinksAcrossLines() {
        // mixed Chinese text, an @username, an emoji, a line break and a link
        final String text = "\u516b\u70b9\u89c1 @alice_01 \ud83d\ude00\n\u94fe\u63a5 https://t.me/luckgram \u770b\u770b";
        assertEquals(Arrays.asList("mention:@alice_01", "url:https://t.me/luckgram"), covered(text, null));
    }

    @Test
    public void linkDoesNotSwallowTrailingPunctuation() {
        assertEquals(Collections.singletonList("url:https://example.com/a"), covered("\u770b https://example.com/a).", null));
        assertEquals(Collections.singletonList("url:www.example.com"), covered("go to www.example.com!", null));
    }

    @Test
    public void ignoresEmailsAndTooShortNames() {
        assertTrue(covered("mail me@example.com", null).isEmpty());
        assertTrue(covered("hi @ab", null).isEmpty());
    }

    @Test
    public void skipsRangesAlreadyCoveredByAnEntity() {
        final String text = "ping @alice_01 now";
        final int start = text.indexOf('@');
        final List<int[]> occupied = Collections.singletonList(new int[]{start, start + "@alice_01".length()});
        assertTrue(covered(text, occupied).isEmpty());
    }

    @Test
    public void mentionInsideUrlIsNotCountedTwice() {
        assertEquals(Collections.singletonList("url:https://t.me/@alice_01"), covered("https://t.me/@alice_01", null));
    }

    @Test
    public void emptyText() {
        assertTrue(covered("", null).isEmpty());
        assertTrue(TranslateOutLogic.protectedRanges(null, null).isEmpty());
    }
}
