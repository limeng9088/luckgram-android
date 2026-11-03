package org.telegram.luckgram.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class CloudBackupLogicTest {

    private static final long DAY = 24 * 60 * 60 * 1000L;

    @Test
    public void uploadBodyCarriesOnlyCiphertextAndCounters() throws Exception {
        JSONObject body = CloudBackupLogic.uploadBody("ZW5jcnlwdGVk", 3, 1762135200000L);
        assertEquals("ZW5jcnlwdGVk", body.getString("data"));
        assertEquals(3, body.getInt("accounts"));
        assertEquals("2025-11-03T02:00:00Z", body.getString("created_at"));
        assertEquals(1, body.getInt("format_version"));
        assertEquals(4, body.length());
    }

    @Test
    public void parseMeta() throws Exception {
        CloudBackupLogic.Meta meta = CloudBackupLogic.parseMeta(new JSONObject(
                "{\"backup\":{\"accounts\":2,\"created_at\":\"2025-11-03T02:00:00Z\",\"format_version\":1,\"data\":\"QUJD\"}}"));
        assertEquals(2, meta.accounts);
        assertEquals(1762135200000L, meta.createdAt);
        assertEquals("QUJD", meta.data);

        CloudBackupLogic.Meta bare = CloudBackupLogic.parseMeta(new JSONObject("{\"accounts\":1,\"uploaded_at\":\"2025-11-03T02:00:00.123Z\"}"));
        assertEquals(1, bare.accounts);
        assertEquals(1762135200123L, bare.createdAt);
        assertNull(bare.data);

        assertNull(CloudBackupLogic.parseMeta(new JSONObject("{\"backup\":null}")));
        assertNull(CloudBackupLogic.parseMeta(new JSONObject("{}")));
        assertNull(CloudBackupLogic.parseMeta(null));
    }

    @Test
    public void notFound() {
        assertTrue(CloudBackupLogic.isNotFound("not_found", 404));
        assertTrue(CloudBackupLogic.isNotFound("whatever", 404));
        assertFalse(CloudBackupLogic.isNotFound("not_pro", 403));
    }

    @Test
    public void autoBackupNeedsSwitchAndRememberedPassword() {
        long now = 1_000 * DAY;
        assertFalse(CloudBackupLogic.autoBackupDue(false, true, 0, now));
        assertFalse(CloudBackupLogic.autoBackupDue(true, false, 0, now));
        assertTrue(CloudBackupLogic.autoBackupDue(true, true, 0, now));
        assertFalse(CloudBackupLogic.autoBackupDue(true, true, now - 6 * DAY, now));
        assertTrue(CloudBackupLogic.autoBackupDue(true, true, now - 7 * DAY, now));
        assertTrue(CloudBackupLogic.autoBackupDue(true, true, now + DAY, now));
    }

    @Test
    public void badDatesAreZero() {
        assertEquals(0, CloudBackupLogic.parseRfc3339(""));
        assertEquals(0, CloudBackupLogic.parseRfc3339("yesterday"));
    }
}
