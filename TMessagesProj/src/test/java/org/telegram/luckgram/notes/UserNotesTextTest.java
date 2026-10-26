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
    void limitCountsEmojiAsOneCharacterAndNeverSplitsThem() {
        // 😀 is two UTF-16 units; 2001 of them must become exactly 2000 whole emoji.
        String cut = UserNotesText.normalize(repeat("😀", 2001));
        assertEquals(2000, cut.codePointCount(0, cut.length()));
        assertEquals(4000, cut.length());
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
