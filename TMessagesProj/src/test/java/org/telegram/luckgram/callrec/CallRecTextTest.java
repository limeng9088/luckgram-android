package org.telegram.luckgram.callrec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;

public class CallRecTextTest {

    @Test
    public void shortTextIsOneMessage() {
        ArrayList<String> parts = CallRecText.split("  要点总结\n- a\n\n文字稿\nhello  ", 4000, "续");
        assertEquals(1, parts.size());
        assertEquals("要点总结\n- a\n\n文字稿\nhello", parts.get(0));
    }

    @Test
    public void longTextSplitsAtLineEndsWithinLimit() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            sb.append("line ").append(i).append(" some spoken words here\n");
        }
        String text = sb.toString();
        ArrayList<String> parts = CallRecText.split(text, 4000, "文字稿（续）");
        assertTrue(parts.size() >= 2);
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            String p = parts.get(i);
            assertTrue("fits a Telegram message", p.length() <= 4096);
            if (i > 0) {
                assertTrue(p.startsWith("文字稿（续）\n"));
                p = p.substring("文字稿（续）\n".length());
                // Broke at a line end, so no line is cut in half.
                assertTrue(p.startsWith("line "));
                joined.append('\n');
            }
            joined.append(p);
        }
        assertEquals(text.trim(), joined.toString());
    }

    @Test
    public void textWithoutBreaksIsCutHard() {
        String text = new String(new char[9000]).replace('\0', '字');
        ArrayList<String> parts = CallRecText.split(text, 4000, "c");
        assertEquals(3, parts.size());
        assertEquals(4000, parts.get(0).length());
        assertEquals(9000, parts.get(0).length() + parts.get(1).length() - 2 + parts.get(2).length() - 2);
    }

    @Test
    public void limitKeepsShortTextAndNoLimit() {
        assertEquals("abc", CallRecText.limit("abc", 10));
        assertEquals("abcdef", CallRecText.limit("abcdef", 0));
    }

    @Test
    public void limitCutsAtLineEndNearTheLimit() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("sentence number ").append(i).append('\n');
        }
        String cut = CallRecText.limit(sb.toString(), 500);
        assertTrue(cut.length() <= 500);
        assertTrue(cut.length() >= 400);
        assertTrue("ends with a whole line", sb.toString().startsWith(cut + "\n"));
    }

    @Test
    public void limitCutsHardWithoutBreaks() {
        String text = new String(new char[1000]).replace('\0', 'x');
        assertEquals(300, CallRecText.limit(text, 300).length());
    }
}
