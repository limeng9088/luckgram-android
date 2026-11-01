package org.telegram.luckgram.discover;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class DiscoverMockTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void nearbyRoundTrip() throws Exception {
        DiscoverMock m = new DiscoverMock();
        assertNull(m.handle("GET", "/v1/ai/chat", "", null));
        // more than 2 decimals is rejected (the client must coarsen)
        assertEquals(400, m.handle("PUT", "/v1/nearby/me", "", b("{\"lat\":31.2304,\"lon\":121.47,\"username\":\"me\"}")).status);
        DiscoverMock.Reply put = m.handle("PUT", "/v1/nearby/me", "", b("{\"lat\":31.23,\"lon\":121.47,\"username\":\"me\"}"));
        assertEquals(200, put.status);
        assertTrue(put.body.getBoolean("visible"));
        assertTrue(m.handle("GET", "/v1/nearby/me", "", null).body.getBoolean("visible"));

        JSONArray items = m.handle("GET", "/v1/nearby", "lat=31.23&lon=121.47", null).body.getJSONArray("items");
        assertEquals(7, items.length());
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.getJSONObject(i);
            assertFalse(o.has("lat"));
            assertFalse(o.has("distance"));
        }
        assertEquals(200, m.handle("POST", "/v1/blocks", "", b("{\"user\":\"m1\"}")).status);
        assertEquals(6, m.handle("GET", "/v1/nearby", "lat=31.23&lon=121.47", null).body.getJSONArray("items").length());
        assertEquals(200, m.handle("DELETE", "/v1/blocks/m1", "", null).status);

        assertEquals(200, m.handle("DELETE", "/v1/nearby/me", "", null).status);
        assertFalse(m.handle("GET", "/v1/nearby/me", "", null).body.getBoolean("visible"));
    }

    @Test
    public void notLinkedAndRateLimit() throws Exception {
        DiscoverMock m = new DiscoverMock();
        m.handle("DELETE", "/v1/link", "", null);
        DiscoverMock.Reply r = m.handle("GET", "/v1/nearby", "lat=1.00&lon=1.00", null);
        assertEquals(403, r.status);
        assertEquals("not_linked", r.body.getJSONObject("error").getString("code"));
        m.setLinked(true);
        for (int i = 0; i < 30; i++) m.handle("GET", "/v1/nearby", "lat=1.00&lon=1.00", null);
        r = m.handle("GET", "/v1/nearby", "lat=1.00&lon=1.00", null);
        assertEquals(429, r.status);
        assertTrue(r.body.getJSONObject("error").getLong("retry_after_seconds") > 0);
    }

    @Test
    public void countsDecimals() {
        assertEquals(2, DiscoverMock.decimals("31.23"));
        assertEquals(0, DiscoverMock.decimals("31"));
        assertEquals(99, DiscoverMock.decimals("1.0E-5"));
    }
}
