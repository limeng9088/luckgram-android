package org.telegram.luckgram.reply;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class AiReplyTextTest {

    @Test
    public void threePlainLines() {
        assertEquals(Arrays.asList("周五可以吗？", "不好意思明天去不了，周五方便吗？", "明天不行哈，周五咋样"),
            AiReplyText.candidates("周五可以吗？\n不好意思明天去不了，周五方便吗？\n明天不行哈，周五咋样\n"));
    }

    @Test
    public void numberingQuotesAndExtraLinesAreRemoved() {
        assertEquals(Arrays.asList("Sure", "Thank you, see you then", "cool"),
            AiReplyText.candidates("1. \"Sure\"\n\n2) “Thank you, see you then”\n- cool\n4. extra"));
    }

    @Test
    public void partialStreamShowsWhatArrived() {
        assertEquals(Collections.singletonList("Sure, fri"), AiReplyText.candidates("Sure, fri"));
        assertTrue(AiReplyText.candidates("").isEmpty());
    }

    @Test
    public void promptWithAndWithoutDraft() {
        String withDraft = AiReplyText.prompt("Alice: 明天的午饭还约吗？", "明天去不了 周五行不", "我");
        assertTrue(withDraft.contains("Alice: 明天的午饭还约吗？"));
        assertTrue(withDraft.endsWith("My draft: 明天去不了 周五行不"));
        String noDraft = AiReplyText.prompt("", "  ", "Me");
        assertTrue(noDraft.contains("(none)"));
        assertTrue(noDraft.endsWith("I have no draft; suggest what I could reply."));
    }
}
