package org.telegram.luckgram;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/** GET /v1/config parsing (issue #56). The response below is the dev server's real shape. */
public class LuckgramRemoteConfigTest {

    private static final String DEV_RESPONSE = "{\"version\":\"7\",\"ttl\":3600,"
        + "\"values\":{\"ai.transcribe_max_mb\":4,\"quota.free.chat_per_day\":50,\"ai.max_input_chars\":60000,"
        + "\"features.round_video_from_gallery.enabled\":false,\"features.quick_replies.enabled\":true,"
        + "\"features.quick_replies.rollout_percent\":50,\"billing.enabled\":false,\"quota.reset_timezone\":\"Asia/Shanghai\"},"
        + "\"features\":{\"round_video_from_gallery\":false,\"quick_replies\":false,\"user_notes\":true}}";

    @Test
    public void devResponse() throws Exception {
        LuckgramRemoteConfig c = LuckgramRemoteConfig.fromResponse(new JSONObject(DEV_RESPONSE), 1000);
        assertFalse(c.isAllowed("round_video_from_gallery"));
        assertEquals(4, c.getLong("ai.transcribe_max_mb", 0));
        assertEquals(50, c.getLong("quota.free.chat_per_day", 0));
        assertEquals("Asia/Shanghai", c.getString("quota.reset_timezone", null));
        assertFalse(c.getBool("billing.enabled", true));
        assertEquals("7", c.version);
        assertEquals(3600, c.ttlSeconds);
    }

    @Test
    public void perDeviceFeaturesWinOverValues() throws Exception {
        LuckgramRemoteConfig c = LuckgramRemoteConfig.fromResponse(new JSONObject(DEV_RESPONSE), 1000);
        // values say enabled=true but the device fell outside the 50% rollout
        assertFalse(c.isAllowed("quick_replies"));
        assertTrue(c.isAllowed("user_notes"));
    }

    @Test
    public void unknownFeatureAndMissingValueUseDefaults() throws Exception {
        LuckgramRemoteConfig c = LuckgramRemoteConfig.fromResponse(new JSONObject(DEV_RESPONSE), 1000);
        assertTrue(c.isAllowed("not_in_config"));
        assertEquals(123, c.getLong("no.such.key", 123));
        assertTrue(LuckgramRemoteConfig.EMPTY.isAllowed("round_video_from_gallery"));
        assertEquals(4, LuckgramRemoteConfig.EMPTY.getLong("ai.transcribe_max_mb", 4));
    }

    @Test
    public void featuresMissingFallsBackToValuesEnabled() throws Exception {
        LuckgramRemoteConfig c = LuckgramRemoteConfig.fromResponse(new JSONObject("{\"values\":{\"features.x.enabled\":false}}"), 1);
        assertFalse(c.isAllowed("x"));
        assertNull(c.version);
    }

    @Test
    public void serializeRoundTripKeepsEverything() throws Exception {
        LuckgramRemoteConfig c = LuckgramRemoteConfig.fromResponse(new JSONObject(DEV_RESPONSE), 5000);
        LuckgramRemoteConfig d = LuckgramRemoteConfig.deserialize(c.serialize());
        assertFalse(d.isAllowed("round_video_from_gallery"));
        assertEquals(4, d.getLong("ai.transcribe_max_mb", 0));
        assertEquals("7", d.version);
        assertEquals(3600, d.ttlSeconds);
        assertEquals(5000, d.fetchedAt);
        assertFalse(d.isExpired(5000 + 3599_000L));
        assertTrue(d.isExpired(5000 + 3600_000L));
    }

    @Test
    public void garbageIsEmpty() {
        assertTrue(LuckgramRemoteConfig.deserialize("not json").isAllowed("a"));
        assertTrue(LuckgramRemoteConfig.deserialize(null).isExpired(1));
    }
}
