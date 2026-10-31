package org.telegram.luckgram.notes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Spec 05 (private notes): text rules shared by the editor, chat bar and "All notes". */
class UserNotesTextTest {

    private static final String NOAH_NOTE = "柏林设计周认识的。欠我 40 欧元";

    private static String repeat(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            b.append(s);
        }
        return b.toString();
    }

    // ---- 需求 2：纯文本，最多 2000 字，支持换行

    @Test
    void keepsLineBreaksAndTrimsOuterWhitespace() {
        assertEquals("第一行\n第二行", UserNotesText.normalize("  第一行\n第二行\n\n "));
    }

    @Test
    void blankNoteMeansNoNote() {
        assertEquals("", UserNotesText.normalize(null));
        assertEquals("", UserNotesText.normalize(" \n\t "));
    }

    @Test
    void exactly2000CharactersAreKept() {
        String note = repeat("字", 2000);
        assertEquals(note, UserNotesText.normalize(note));
    }

    @Test
    void longerNotesAreCutTo2000Characters() {
        String cut = UserNotesText.normalize(repeat("字", 2500));
        assertEquals(2000, cut.codePointCount(0, cut.length()));
    }

    @Test
    void surrogatePairEmojiCountAsOneCharacter() {
        // 😀 is two UTF-16 units; 2001 of them must become exactly 2000 whole emoji.
        String cut = UserNotesText.normalize(repeat("😀", 2001));
        assertEquals(2000, cut.codePointCount(0, cut.length()));
        assertEquals(4000, cut.length());
    }

    // ---- specs#60：和 iOS 一样按字形簇计数（一个 emoji / ZWJ 组合 / 国旗算 1 个）

    private static final String FAMILY = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"; // 👨‍👩‍👧, 5 code points
    private static final String FLAG_CN = "\uD83C\uDDE8\uD83C\uDDF3"; // 🇨🇳
    private static final String THUMB_DARK = "\uD83D\uDC4D\uD83C\uDFFF"; // 👍🏿
    private static final String KEYCAP_1 = "1\uFE0F\u20E3"; // 1️⃣

    @Test
    void emojiSequencesCountAsOneCharacter() {
        assertEquals(1, UserNotesText.graphemeCount(FAMILY));
        assertEquals(1, UserNotesText.graphemeCount(FLAG_CN));
        assertEquals(1, UserNotesText.graphemeCount(THUMB_DARK));
        assertEquals(1, UserNotesText.graphemeCount(KEYCAP_1));
        assertEquals(1, UserNotesText.graphemeCount("e\u0301")); // é with combining accent
        assertEquals(2, UserNotesText.graphemeCount(FLAG_CN + FLAG_CN));
        assertEquals(1, UserNotesText.graphemeCount("\r\n"));
    }

    @Test
    void twoThousandFamilyEmojiFitLikeOnIos() {
        String note = repeat(FAMILY, 2000);
        assertEquals(note, UserNotesText.normalize(note));
        assertEquals(0, UserNotesText.remaining(note));
    }

    @Test
    void cuttingNeverSplitsAZwjSequence() {
        String cut = UserNotesText.normalize(repeat(FAMILY, 2001));
        assertEquals(repeat(FAMILY, 2000), cut);
    }

    @Test
    void editorKeepsOnlyWholeCharactersThatFit() {
        String dest = repeat("字", 1998);
        // Pasting 3 families into room for 2: keep exactly two whole families.
        String paste = FAMILY + FAMILY + FAMILY;
        int keep = UserNotesText.fittingPrefix(paste, dest, dest.length(), dest.length());
        assertEquals(FAMILY.length() * 2, keep);
        // Replacing a selection frees its room.
        assertEquals(paste.length(), UserNotesText.fittingPrefix(paste, dest, 0, 3));
        // Full note: nothing more fits.
        assertEquals(0, UserNotesText.fittingPrefix("a", repeat("字", 2000), 2000, 2000));
    }

    // ---- 需求 3：聊天顶部细条显示备注第一行

    @Test
    void firstLineSkipsEmptyLines() {
        assertEquals("柏林设计周认识的。", UserNotesText.firstLine("\n  \n柏林设计周认识的。\n欠我 40 欧元"));
    }

    @Test
    void firstLineOfSingleLineNoteIsTheNote() {
        assertEquals(NOAH_NOTE, UserNotesText.firstLine(NOAH_NOTE));
        assertEquals("", UserNotesText.firstLine(null));
    }

    // ---- 需求 4 / 验收：「全部备注」搜索「欧元」能找到 Noah

    @Test
    void searchFindsNoahByNoteText() {
        assertTrue(UserNotesText.matches("欧元", NOAH_NOTE, "Noah"));
    }

    @Test
    void searchFindsByNameCaseInsensitive() {
        assertTrue(UserNotesText.matches("noah", NOAH_NOTE, "Noah"));
        assertTrue(UserNotesText.matches(" NOAH ", "", "Noah Schmidt"));
    }

    @Test
    void searchMissesWhenNeitherNoteNorNameContainsQuery() {
        assertFalse(UserNotesText.matches("美元", NOAH_NOTE, "Noah"));
    }

    @Test
    void emptyQueryListsEverything() {
        assertTrue(UserNotesText.matches("", NOAH_NOTE, "Noah"));
        assertTrue(UserNotesText.matches(null, NOAH_NOTE, "Noah"));
    }

    @Test
    void snippetShowsTheMatchingLine() {
        String note = "柏林设计周认识的\n欠我 40 欧元";
        assertEquals("欠我 40 欧元", UserNotesText.snippet(note, "欧元"));
        // Name match or no query: first line.
        assertEquals("柏林设计周认识的", UserNotesText.snippet(note, "Noah"));
        assertEquals("柏林设计周认识的", UserNotesText.snippet(note, ""));
    }
}
