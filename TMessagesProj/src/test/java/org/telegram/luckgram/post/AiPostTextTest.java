package org.telegram.luckgram.post;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

public class AiPostTextTest {

    @Test
    public void examplesAreLimitedToTwentyAndBudget() {
        ArrayList<String> posts = new ArrayList<>();
        for (int i = 0; i < 30; i++) posts.add("post " + i);
        String ex = AiPostText.examples(posts, 100000);
        assertTrue(ex.contains("post 19"));
        assertFalse(ex.contains("post 20"));
        String small = AiPostText.examples(posts, 40);
        assertTrue(small.length() <= 40);
    }

    @Test
    public void longExamplesAreCutAndEmptyOnesSkipped() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3000; i++) sb.append('a');
        String ex = AiPostText.examples(Arrays.asList("", "  ", sb.toString()), 100000);
        assertEquals("---\n" + sb.substring(0, AiPostText.MAX_EXAMPLE_CHARS) + "…\n", ex);
    }

    @Test
    public void generateAndEditRequests() {
        String ex = AiPostText.examples(Collections.singletonList("🌹 新品上架"), 1000);
        String gen = AiPostText.generate(ex, " 周末促销，玫瑰八折 ");
        assertTrue(gen.startsWith("Example posts from this channel"));
        assertTrue(gen.endsWith("Topic: 周末促销，玫瑰八折\nWrite a new post for the channel."));
        assertEquals("Topic: x\nWrite a new post for the channel.", AiPostText.generate("", "x"));
        String edit = AiPostText.edit("", "草稿\n", AiPostText.translate("English"));
        assertEquals("Current draft:\n草稿\n\nInstruction: Translate it into English. Keep the formatting and emoji.", edit);
    }
}
