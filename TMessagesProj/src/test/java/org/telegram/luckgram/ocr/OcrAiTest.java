package org.telegram.luckgram.ocr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class OcrAiTest {

    @Test
    public void offeredOnlyWhenOnAndDeviceFoundNothing() {
        assertTrue(OcrAi.offer(true, false));
        assertFalse(OcrAi.offer(true, true));
        assertFalse(OcrAi.offer(false, false));
    }

    @Test
    public void body() throws Exception {
        assertEquals("abc", OcrAi.body("abc", "ja").getString("image"));
        assertEquals("ja", OcrAi.body("abc", "ja").getString("lang_hint"));
        assertFalse(OcrAi.body("abc", null).has("lang_hint"));
    }

    @Test
    public void parse() throws Exception {
        JSONObject j = new JSONObject("{\"language\":\"th\",\"blocks\":["
            + "{\"text\":\"สวัสดี\\r\\nครับ\",\"language\":\"th\",\"box\":{\"x\":0.1,\"y\":0.2,\"width\":0.5,\"height\":0.1}},"
            + "{\"text\":\"  \"},"
            + "{\"text\":\"x\",\"box\":{\"x\":0.9,\"y\":-1,\"width\":0.5,\"height\":2}}]}");
        OcrResult r = OcrAi.parse(j, 800, 600);
        assertEquals(OcrResult.SOURCE_AI, r.source);
        assertEquals("th", r.language);
        assertEquals(2, r.blocks.size());
        assertEquals("สวัสดี\nครับ", r.blocks.get(0).text);
        assertEquals(2, r.blocks.get(0).lines.size());
        assertEquals(0.1f, r.blocks.get(1).box.width, 1e-6);
        assertEquals(1f, r.blocks.get(1).box.height, 1e-6);
        assertNull(r.blocks.get(1).language);
        assertEquals("สวัสดี\nครับ\nx", r.text());
        assertTrue(OcrAi.parse(null, 1, 1).blocks.isEmpty());
    }
}
