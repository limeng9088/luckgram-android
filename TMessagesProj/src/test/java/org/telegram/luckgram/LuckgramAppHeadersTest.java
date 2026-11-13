package org.telegram.luckgram;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

public class LuckgramAppHeadersTest {

    @Test
    public void ciBuildIsSent() {
        Map<String, String> h = LuckgramAppHeaders.headers("13.0.3", 189, 71839);
        assertEquals(2, h.size());
        assertEquals("13.0.3", h.get("X-Luckgram-App-Version"));
        assertEquals("189", h.get("X-Luckgram-App-Build"));
    }

    @Test
    public void localBuildFallsBackToVersionCode() {
        assertEquals("7183", LuckgramAppHeaders.headers("13.0.3", 0, 71839).get("X-Luckgram-App-Build"));
    }

    @Test
    public void malformedValuesAreLeftOut() {
        assertTrue(LuckgramAppHeaders.headers(null, 0, 0).isEmpty());
        assertTrue(LuckgramAppHeaders.headers("13.0-beta", 0, 5).isEmpty());
        assertTrue(LuckgramAppHeaders.headers("1.2.3.4.5", -1, -10).isEmpty());
        assertEquals("13.0.3", LuckgramAppHeaders.headers(" 13.0.3 ", 0, 0).get("X-Luckgram-App-Version"));
    }
}
