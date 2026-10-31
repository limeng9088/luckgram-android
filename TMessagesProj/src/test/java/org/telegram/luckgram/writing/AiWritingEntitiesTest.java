package org.telegram.luckgram.writing;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public class AiWritingEntitiesTest {

    @Test
    public void mentionKeptWhenNameUnchanged() {
        String original = "tell Anna we are late";
        String result = "Please tell Anna that we're running late.";
        assertArrayEquals(new int[]{12}, AiWritingEntities.relocate(original, new int[][]{{5, 9}}, result));
    }

    @Test
    public void rangeDroppedWhenTextChanged() {
        String original = "this is VERY important";
        String result = "This is extremely important.";
        assertArrayEquals(new int[]{-1}, AiWritingEntities.relocate(original, new int[][]{{8, 12}}, result));
    }

    @Test
    public void repeatedEmojiLandOnDifferentOccurrences() {
        String original = "ok 😀 and 😀";
        int a = original.indexOf("😀");
        int b = original.lastIndexOf("😀");
        String result = "Okay 😀, and 😀!";
        int[] got = AiWritingEntities.relocate(original, new int[][]{{a, a + 2}, {b, b + 2}}, result);
        assertArrayEquals(new int[]{result.indexOf("😀"), result.lastIndexOf("😀")}, got);
    }

    @Test
    public void nestedRangesBothFound() {
        String original = "see you, Anna Lee";
        // bold over "you, Anna Lee" and a mention on "Anna Lee"
        int[][] ranges = {{4, 17}, {9, 17}};
        String result = "See you, Anna Lee!";
        assertArrayEquals(new int[]{4, 9}, AiWritingEntities.relocate(original, ranges, result));
    }

    @Test
    public void reorderedTextStillFindsRange() {
        String original = "link: https://t.me/x , thanks Bob";
        int[][] ranges = {{6, 20}, {30, 33}};
        String result = "Thanks, Bob! Here is the link: https://t.me/x";
        assertArrayEquals(new int[]{result.indexOf("https"), result.indexOf("Bob")},
            AiWritingEntities.relocate(original, ranges, result));
    }

    @Test
    public void blankAndInvalidRangesIgnored() {
        assertArrayEquals(new int[]{-1, -1}, AiWritingEntities.relocate("a  b", new int[][]{{1, 3}, {2, 9}}, "a  b"));
    }
}
