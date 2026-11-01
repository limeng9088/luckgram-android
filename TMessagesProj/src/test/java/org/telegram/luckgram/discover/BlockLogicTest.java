package org.telegram.luckgram.discover;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

public class BlockLogicTest {

    @Test
    public void parsesIdsDedupesAndTolerantOfObjects() throws Exception {
        JSONObject j = new JSONObject("{\"items\":[{\"user\":\"a\"},{\"user\":{\"id\":\"b\"}},{\"user\":\"a\"},{\"user\":\"\"},{}],\"next_cursor\":\"c1\"}");
        assertEquals(Arrays.asList("a", "b"), BlockLogic.parseIds(j));
        assertEquals("c1", BlockLogic.nextCursor(j));
        assertNull(BlockLogic.nextCursor(new JSONObject("{\"items\":[]}")));
        assertNull(BlockLogic.nextCursor(new JSONObject("{\"next_cursor\":null}")));
        assertTrue(BlockLogic.parseIds(null).isEmpty());
    }

    @Test
    public void paths() throws Exception {
        assertEquals("/v1/blocks", BlockLogic.listPath(null));
        assertEquals("/v1/blocks?cursor=a%2Bb%3D", BlockLogic.listPath("a+b="));
        assertEquals("/v1/blocks/u%2F1", BlockLogic.deletePath("u/1"));
        assertEquals("u1", BlockLogic.blockBody("u1").getString("user"));
    }

    @Test
    public void nameCache() {
        String c = BlockLogic.putName("", "a", " Alice ");
        c = BlockLogic.putName(c, "b", "Bob");
        assertEquals("Alice", BlockLogic.name(c, "a"));
        c = BlockLogic.removeName(c, "a");
        assertNull(BlockLogic.name(c, "a"));
        assertEquals("Bob", BlockLogic.name(c, "b"));
        assertNull(BlockLogic.name("not json", "b"));
        assertNull(BlockLogic.name(BlockLogic.putName(c, "b", ""), "b"));
    }

    @Test
    public void textsBothLanguages() {
        assertEquals("已拉黑的人", new BlockLogic.Texts(true).title());
        assertEquals("Blocked People", new BlockLogic.Texts(false).title());
    }
}
