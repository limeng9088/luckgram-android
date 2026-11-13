package org.telegram.luckgram.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class UpdateDownloadLogicTest {

    private static final String SHA = "91a90e09aabbccddeeff00112233445566778899aabbccddeeff001122334455";
    private static final long H = 60 * 60_000L;

    @Test
    public void verify() {
        assertEquals(UpdateDownloadLogic.Verdict.OK, UpdateDownloadLogic.verify(SHA, 10, SHA.toUpperCase(), 10));
        assertEquals(UpdateDownloadLogic.Verdict.OK, UpdateDownloadLogic.verify(SHA, 0, SHA, 10));
        assertEquals(UpdateDownloadLogic.Verdict.SHA_MISMATCH, UpdateDownloadLogic.verify(SHA, 10, SHA.replace('9', '8'), 10));
        assertEquals(UpdateDownloadLogic.Verdict.SIZE_MISMATCH, UpdateDownloadLogic.verify(SHA, 11, SHA, 10));
        // no sha256 from the server (fields not live yet): accepted but not checked
        assertEquals(UpdateDownloadLogic.Verdict.NOT_CHECKED, UpdateDownloadLogic.verify("", 0, SHA, 10));
        assertEquals(UpdateDownloadLogic.Verdict.NOT_CHECKED, UpdateDownloadLogic.verify("garbage", 0, SHA, 10));
        assertEquals(UpdateDownloadLogic.Verdict.SIZE_MISMATCH, UpdateDownloadLogic.verify("", 5, SHA, 10));
        assertTrue(UpdateDownloadLogic.accepted(UpdateDownloadLogic.Verdict.NOT_CHECKED));
        assertFalse(UpdateDownloadLogic.accepted(UpdateDownloadLogic.Verdict.SHA_MISMATCH));
    }

    @Test
    public void hexAndSha() {
        assertEquals("00ff10", UpdateDownloadLogic.hex(new byte[]{0, (byte) 0xff, 0x10}));
        assertTrue(UpdateDownloadLogic.isSha256(SHA));
        assertFalse(UpdateDownloadLogic.isSha256(SHA.substring(1)));
        assertFalse(UpdateDownloadLogic.isSha256(null));
        assertTrue(UpdateDownloadLogic.isHttps("https://dl.luckgram.cc/a.apk"));
        assertTrue(UpdateDownloadLogic.isHttps("HTTPS://dl.luckgram.cc/a.apk"));
        assertFalse(UpdateDownloadLogic.isHttps("http://dl.luckgram.cc/a.apk"));
        assertFalse(UpdateDownloadLogic.isHttps("https://"));
    }

    @Test
    public void autoDownload() {
        long now = 1_000_000_000_000L;
        String url = "https://dl.luckgram.cc/a.apk";
        assertTrue(UpdateDownloadLogic.shouldAutoDownload(true, url, true, true, false, false, now, 0));
        assertFalse("switch off", UpdateDownloadLogic.shouldAutoDownload(true, url, false, true, false, false, now, 0));
        assertFalse("metered", UpdateDownloadLogic.shouldAutoDownload(true, url, true, false, false, false, now, 0));
        assertFalse("no update", UpdateDownloadLogic.shouldAutoDownload(false, url, true, true, false, false, now, 0));
        assertFalse("ready", UpdateDownloadLogic.shouldAutoDownload(true, url, true, true, true, false, now, 0));
        assertFalse("running", UpdateDownloadLogic.shouldAutoDownload(true, url, true, true, false, true, now, 0));
        assertFalse("http", UpdateDownloadLogic.shouldAutoDownload(true, "http://x/a.apk", true, true, false, false, now, 0));
        assertFalse("failed recently", UpdateDownloadLogic.shouldAutoDownload(true, url, true, true, false, false, now, now - H));
        assertTrue(UpdateDownloadLogic.shouldAutoDownload(true, url, true, true, false, false, now, now - 6 * H));
        assertTrue("clock went back", UpdateDownloadLogic.shouldAutoDownload(true, url, true, true, false, false, now, now + H));
    }

    @Test
    public void cleanup() {
        String keep = UpdateDownloadLogic.fileName("13.0.4", 330);
        assertEquals("luckgram-update-13.0.4-330.apk", keep);
        String[] names = {keep, keep + ".part", "luckgram-update-13.0.3-324.apk", "luckgram-update-13.0.3-324.apk.part", "other.txt"};
        assertEquals(Arrays.asList("luckgram-update-13.0.3-324.apk", "luckgram-update-13.0.3-324.apk.part"), UpdateDownloadLogic.toDelete(names, keep));
        // installed (no update on offer): every package goes, foreign files stay
        assertEquals(Arrays.asList(keep, keep + ".part", "luckgram-update-13.0.3-324.apk", "luckgram-update-13.0.3-324.apk.part"), UpdateDownloadLogic.toDelete(names, null));
        assertEquals(Collections.emptyList(), UpdateDownloadLogic.toDelete(null, keep));
        assertFalse(UpdateDownloadLogic.fileName("13.0.4/../x", 0).contains("/"));
    }

    @Test
    public void space() {
        assertTrue(UpdateDownloadLogic.enoughSpace(100L << 20, 50L << 20));
        assertFalse(UpdateDownloadLogic.enoughSpace(60L << 20, 50L << 20));
        assertTrue(UpdateDownloadLogic.enoughSpace(30L << 20, 0));
    }

    @Test
    public void stateCarriesShaAndSizeOnlyForLatest() throws Exception {
        JSONObject j = new JSONObject("{\"latest\":\"13.0.4\",\"build\":330,\"min\":\"13.0.0\",\"url\":\"https://dl.luckgram.cc/a.apk\",\"sha256\":\"" + SHA.toUpperCase() + "\",\"size\":123}");
        UpdateLogic.Info info = UpdateLogic.deserialize(UpdateLogic.serialize(UpdateLogic.parse(j)));
        assertEquals(SHA, info.sha256);
        assertEquals(123, info.size);
        UpdateLogic.State s = UpdateLogic.evaluate(info, "13.0.3", 324, false);
        assertTrue(s.updateAvailable);
        assertEquals(SHA, s.sha256);
        assertEquals(123, s.size);
        assertEquals(330, s.build);
        // old server: no sha256 / size, still works
        UpdateLogic.State old = UpdateLogic.evaluate(UpdateLogic.parse(new JSONObject("{\"latest\":\"13.0.4\",\"url\":\"https://dl.luckgram.cc/a.apk\",\"sha256\":null}")), "13.0.3", 324, false);
        assertTrue(old.updateAvailable);
        assertEquals("", old.sha256);
        assertEquals(0, old.size);
        // a bad sha256 is dropped rather than failing every download
        assertEquals("", UpdateLogic.parse(new JSONObject("{\"sha256\":\"abc\"}")).sha256);
    }

    @Test
    public void textsCarryBuild() {
        UpdateDownloadLogic.Texts zh = new UpdateDownloadLogic.Texts(true);
        UpdateDownloadLogic.Texts en = new UpdateDownloadLogic.Texts(false);
        // same version name as installed, only the build moved: the build must be visible
        assertEquals("13.0.3（379）", zh.label("13.0.3", 379));
        assertEquals("13.0.3 (379)", en.label("13.0.3", 379));
        assertEquals("13.0.3", zh.label("13.0.3", 0));
        assertEquals("有新版本 13.0.3（379） · 更新", zh.availableRow("13.0.3", 379));
        assertEquals("New version 13.0.3 (379) · Update", en.availableRow("13.0.3", 379));
        assertEquals("新版本 13.0.3（379） 已下载 · 安装", zh.readyRow("13.0.3", 379));
        assertEquals("New version 13.0.3 (379) downloaded · Install", en.readyRow("13.0.3", 379));
        assertEquals("正在下载新版本 13.0.3（379） · 45%", zh.downloadingRow("13.0.3", 379, 45));
        assertEquals("正在下载新版本 13.0.3（379）", zh.downloadingRow("13.0.3", 379, -1));
        assertEquals("正在下载 13.0.3（379）", zh.downloadingTitle("13.0.3", 379));
        assertEquals("新版本 13.0.3（379）", zh.notesTitle("13.0.3", 379));
        assertEquals("Version 13.0.3 (379)", en.notesTitle("13.0.3", 379));
        assertEquals("当前版本 13.0.3（359） 已停止支持，请更新到 13.0.3（379） 后继续使用。",
                zh.forceText("13.0.3", 359, "13.0.3", 379));
        assertEquals("Version 13.0.3 (359) is no longer supported. Update to 13.1.0 (400) to continue.",
                en.forceText("13.0.3", 359, "13.1.0", 400));
        // unknown builds (local build, server without build): version only, as before
        assertEquals("有新版本 13.1.0 · 更新", zh.availableRow("13.1.0", 0));
        assertEquals("当前版本 13.0.3 已停止支持，请更新到 13.1.0 后继续使用。", zh.forceText("13.0.3", 0, "13.1.0", 0));
    }
}
