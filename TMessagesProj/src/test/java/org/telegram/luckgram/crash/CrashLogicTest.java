package org.telegram.luckgram.crash;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CrashLogicTest {

    // ---------------------------------------------------------------- scrubbing

    @Test
    public void scrubRemovesPersonalData() {
        String s = CrashLogic.scrub("chat 1234567890 at https://t.me/+AbCdEf user a.b@example.com phone +86 138 0013 8000 line 42");
        assertFalse(s, s.contains("1234567890"));
        assertFalse(s, s.contains("t.me"));
        assertFalse(s, s.contains("example.com"));
        assertFalse(s, s.contains("138 0013"));
        assertTrue(s, s.contains("line 42"));
    }

    @Test
    public void javaStackKeepsFramesAndScrubsMessages() {
        Exception cause = new NullPointerException("user 98765432 missing");
        Exception e = new IllegalStateException("open https://example.org/x?id=1", cause);
        String s = CrashLogic.javaStack(e, 100_000);
        assertTrue(s, s.startsWith("java.lang.IllegalStateException: open <url>\n\tat "));
        assertTrue(s, s.contains("Caused by: java.lang.NullPointerException: user <n> missing"));
        assertTrue(s, s.contains("CrashLogicTest.javaStackKeepsFramesAndScrubsMessages(CrashLogicTest.java:"));
    }

    @Test
    public void javaStackSurvivesCauseLoopsAndLimits() {
        Exception a = new RuntimeException("a");
        Exception b = new RuntimeException("b", a);
        a.initCause(b);
        String s = CrashLogic.javaStack(b, 100_000);
        assertEquals(2, s.split("RuntimeException", -1).length - 1);
        assertTrue(CrashLogic.javaStack(new RuntimeException(new String(new char[5000]).replace('\0', 'x')), 200).length() <= 200);
    }

    // ---------------------------------------------------------------- ANR

    @Test
    public void anrKeepsOnlyMainThread() {
        String trace = "----- pid 123 at 2026-11-18 -----\n"
            + "\"Signal Catcher\" daemon prio=10 tid=2 Runnable\n  at x.y.Z.secret(Z.java:1)\n\n"
            + "\"main\" prio=5 tid=1 Blocked\n"
            + "  | group=\"main\" sCount=1\n"
            + "  at org.telegram.messenger.MessagesStorage.getChat(MessagesStorage.java:100)\n"
            + "  - waiting to lock <0x0abc> (a java.lang.Object) held by thread 15\n"
            + "  native: #00 pc 000000000004f5b0  /apex/com.android.runtime/lib64/bionic/libc.so (syscall+32)\n"
            + "  at android.os.Looper.loop(Looper.java:288)\n"
            + "\n\"Thread-15\" prio=5 tid=15 Native\n  at a.b.C.d(C.java:1)\n";
        String s = CrashLogic.anrText("Input dispatching timed out (chat 12345678)", trace, 100_000);
        assertTrue(s, s.startsWith("ANR: Input dispatching timed out (chat <n>)\nthread main (Blocked)\n"));
        assertTrue(s, s.contains("  at org.telegram.messenger.MessagesStorage.getChat(MessagesStorage.java:100)\n"));
        assertTrue(s, s.contains("native: #00 pc"));
        assertFalse(s, s.contains("secret"));
        assertFalse(s, s.contains("a.b.C.d"));
        assertTrue(CrashLogic.anrText(null, null, 1000).contains("no main thread stack"));
    }

    // ---------------------------------------------------------------- tombstone

    private static void varint(ByteArrayOutputStream o, long v) {
        while ((v & ~0x7fL) != 0) {
            o.write((int) ((v & 0x7f) | 0x80));
            v >>>= 7;
        }
        o.write((int) v);
    }

    private static void num(ByteArrayOutputStream o, int field, long v) {
        varint(o, (long) field << 3);
        varint(o, v);
    }

    private static void bytes(ByteArrayOutputStream o, int field, byte[] b) {
        varint(o, ((long) field << 3) | 2);
        varint(o, b.length);
        o.write(b, 0, b.length);
    }

    private static void str(ByteArrayOutputStream o, int field, String s) {
        bytes(o, field, s.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] frame(long relPc, String fn, long off, String file, String buildId) {
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        num(f, 1, relPc);
        num(f, 2, relPc + 0x7000000000L); // pc: not used
        if (fn != null) str(f, 4, fn);
        num(f, 5, off);
        str(f, 6, file);
        str(f, 8, buildId);
        return f.toByteArray();
    }

    private static byte[] thread(int id, String name, byte[]... frames) {
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        num(t, 1, id);
        str(t, 2, name);
        bytes(t, 3, new byte[]{0x0a, 0x02, 'x', '0', 0x10, 0x05}); // a register: skipped
        for (byte[] f : frames) bytes(t, 4, f);
        bytes(t, 5, "memory contents 1234567890".getBytes(StandardCharsets.UTF_8)); // memory dump: never read
        return t.toByteArray();
    }

    private static byte[] entry(int key, byte[] thread) {
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        num(e, 1, key);
        bytes(e, 2, thread);
        return e.toByteArray();
    }

    private static byte[] tombstone() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        num(o, 1, 3); // arch
        str(o, 2, "OPPO/PDEM10/fingerprint");
        num(o, 5, 4321); // pid
        num(o, 6, 4400); // tid of the crashing thread
        ByteArrayOutputStream sig = new ByteArrayOutputStream();
        num(sig, 1, 11);
        str(sig, 2, "SIGSEGV");
        num(sig, 3, 1);
        str(sig, 4, "SEGV_MAPERR");
        num(sig, 8, 1);
        num(sig, 9, 0x10);
        bytes(o, 10, sig.toByteArray());
        str(o, 14, "Check failed: chat 1234567890");
        bytes(o, 16, entry(4321, thread(4321, "main", frame(0x100, "pollOnce", 4, "/system/lib64/libutils.so", "aa"))));
        bytes(o, 16, entry(4400, thread(4400, "tgnet",
            frame(0x12a4b0, "_ZN10Datacenter7connectEv", 44, "/data/app/~~Xy==/org.telegram.messenger-Ab==/lib/arm64/libtmessages.49.so", "0123abcd"),
            frame(0xc1a2c, "abort", 164, "/apex/com.android.runtime/lib64/bionic/libc.so", "ffff"))));
        bytes(o, 17, new byte[]{1, 2, 3}); // memory mappings: skipped
        bytes(o, 18, "logcat text".getBytes(StandardCharsets.UTF_8)); // log buffers: skipped
        return o.toByteArray();
    }

    @Test
    public void tombstoneGivesSignalAndCrashingThread() {
        String s = CrashLogic.tombstoneText("crash", tombstone(), 100_000);
        assertNotNull(s);
        assertTrue(s, s.startsWith("signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x10\n"));
        assertTrue(s, s.contains("abort: Check failed: chat <n>\n"));
        assertTrue(s, s.contains("thread tgnet\n"));
        assertTrue(s, s.contains("#00 pc 000000000012a4b0  libtmessages.49.so (_ZN10Datacenter7connectEv+44) (BuildId: 0123abcd)\n"));
        assertTrue(s, s.contains("#01 pc 00000000000c1a2c  libc.so (abort+164)"));
        assertFalse("other threads left out", s.contains("pollOnce"));
        assertFalse("memory not read", s.contains("memory contents"));
        assertFalse("log buffers not read", s.contains("logcat"));
        assertFalse("install path not sent", s.contains("/data/app"));
    }

    @Test
    public void brokenTombstoneFallsBackToDescription() {
        byte[] t = tombstone();
        byte[] cut = Arrays.copyOf(t, t.length / 2);
        String s = CrashLogic.tombstoneText("crash 12345678", cut, 100_000);
        assertNotNull(s);
        assertTrue(s, s.startsWith("native: crash <n>"));
        assertTrue(CrashLogic.tombstoneText("crash", new byte[]{(byte) 0xff, (byte) 0xff}, 1000).startsWith("native: crash"));
        assertTrue(CrashLogic.tombstoneText("x", null, 1000).startsWith("native: x"));
        assertNull(CrashLogic.tombstoneText(null, null, 1000));
    }

    // ---------------------------------------------------------------- report and files

    @Test
    public void reportJson() throws Exception {
        CrashLogic.Facts f = new CrashLogic.Facts();
        f.appVersion = "13.0.3";
        f.build = 420;
        f.abi = "arm64-v8a";
        Map<String, String> d = new LinkedHashMap<>();
        d.put("model", "PDEM10");
        f.device = d;
        JSONObject j = CrashLogic.report(CrashLogic.KIND_JAVA, 1763431140000L, f, "main", "stack");
        assertEquals("java", j.getString("kind"));
        assertEquals("2025-11-18T01:59:00Z", j.getString("occurred_at"));
        assertEquals(420, j.getInt("build"));
        assertEquals("PDEM10", j.getJSONObject("device").getString("model"));
        assertFalse(j.has("os"));
    }

    @Test
    public void cutUtf8KeepsWholeCharacters() {
        assertEquals("ab", CrashLogic.cutUtf8("ab", 10));
        assertEquals("a", CrashLogic.cutUtf8("a中", 3)); // 中 is 3 bytes
        assertEquals("a中", CrashLogic.cutUtf8("a中b", 4));
    }

    @Test
    public void keepsNewestTenFiles() {
        List<String> names = new java.util.ArrayList<>();
        for (int i = 0; i < 13; i++) names.add(CrashLogic.fileName(1000L + i, i % 2 == 0 ? "java" : "anr"));
        names.add("1.json.tmp");
        Collections.shuffle(names);
        List<String> del = CrashLogic.toDelete(names, CrashLogic.KEEP_FILES);
        assertEquals(Arrays.asList(CrashLogic.fileName(1000, "java"), CrashLogic.fileName(1001, "anr"), CrashLogic.fileName(1002, "java")), del);
        assertTrue(CrashLogic.isReportFile("0000000001000-native.json"));
        assertFalse(CrashLogic.isReportFile("0000000001000-other.json"));
    }

    @Test
    public void exitRecordsOnlyOnce() {
        long now = 10L * 24 * 3600 * 1000;
        assertTrue(CrashLogic.shouldReportExit(now - 3600_000, 0, now));
        assertFalse("first scan skips old records", CrashLogic.shouldReportExit(now - 4L * 24 * 3600 * 1000, 0, now));
        assertFalse(CrashLogic.shouldReportExit(500, 500, now));
        assertTrue(CrashLogic.shouldReportExit(501, 500, now));
    }

    @Test
    public void uploadOutcome() {
        assertTrue(CrashLogic.deleteAfterUpload(200));
        assertTrue(CrashLogic.deleteAfterUpload(400));
        assertTrue(CrashLogic.deleteAfterUpload(413));
        assertFalse(CrashLogic.deleteAfterUpload(429));
        assertFalse(CrashLogic.deleteAfterUpload(503));
        assertFalse(CrashLogic.deleteAfterUpload(0));
    }
}
