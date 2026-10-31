package org.telegram.luckgram.summary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

public class ChatSummaryLogicTest {

    @Test
    public void transcriptLineFormat() {
        List<String> parts = ChatSummaryLogic.split(Arrays.asList(
            new ChatSummaryLogic.Line(101, "Alice", "10-31 14:05", "明天 10 点开会\n地点待定"),
            new ChatSummaryLogic.Line(102, "Bob", "10-31 14:06", "[图片] 菜单")), 60000);
        assertEquals(1, parts.size());
        assertTrue(parts.get(0).startsWith(ChatSummaryLogic.header(false)));
        assertTrue(parts.get(0).endsWith("[#101] Alice (10-31 14:05): 明天 10 点开会 地点待定\n[#102] Bob (10-31 14:06): [图片] 菜单"));
    }

    @Test
    public void longRangesAreSplitAndEveryLineKept() {
        ArrayList<ChatSummaryLogic.Line> lines = new ArrayList<>();
        for (int i = 1; i <= 500; i++) {
            lines.add(new ChatSummaryLogic.Line(i, "User" + (i % 7), "10-31 12:00", "message number " + i + " with some words to make it longer"));
        }
        List<String> parts = ChatSummaryLogic.split(lines, 8000);
        assertTrue(parts.size() > 1);
        int found = 0;
        for (String p : parts) {
            assertTrue("part too long: " + p.length(), p.length() <= 8000);
            for (int i = 1; i <= 500; i++) {
                if (p.contains("\n[#" + i + "] ")) found++;
            }
        }
        assertEquals(500, found);
    }

    @Test
    public void veryLongMessageIsCut() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) sb.append('x');
        List<String> parts = ChatSummaryLogic.split(Arrays.asList(new ChatSummaryLogic.Line(1, "A", "t", sb.toString())), 60000);
        assertTrue(parts.get(0).length() < ChatSummaryLogic.header(false).length() + ChatSummaryLogic.MAX_LINE_CHARS + 10);
    }

    @Test
    public void citationsAreParsedAndValidated() {
        HashSet<Integer> valid = new HashSet<>(Arrays.asList(101, 102, 205));
        List<ChatSummaryLogic.Segment> s = ChatSummaryLogic.parse("Meeting moved to 10am [#101, #102]; budget agreed [#999] (#205).", valid);
        assertEquals("[Meeting moved to 10am, {101}, {102}, ; budget agreed, {205}, .]", s.toString());
    }

    @Test
    public void chineseBracketsAndCommas() {
        HashSet<Integer> valid = new HashSet<>(Arrays.asList(7, 8));
        List<ChatSummaryLogic.Segment> s = ChatSummaryLogic.parse("客户答应周五付款（#7，#8）", valid);
        assertEquals("[客户答应周五付款, {7}, {8}]", s.toString());
    }

    @Test
    public void ordinaryParenthesesAndBracketsStayText() {
        HashSet<Integer> valid = new HashSet<>(Arrays.asList(5));
        List<ChatSummaryLogic.Segment> s = ChatSummaryLogic.parse("Revenue (2024) grew, see [1]", valid);
        assertEquals("[Revenue (2024) grew, see [1]]", s.toString());
    }

    @Test
    public void bulletsAreExtracted() {
        List<String> b = ChatSummaryLogic.bullets("Here you go:\n- 第一点 [#1]\n  继续说明\n* second\n2) third\n\n• fourth");
        assertEquals(Arrays.asList("Here you go:", "第一点 [#1] 继续说明", "second", "third", "fourth"), b);
    }
}
