package org.telegram.luckgram.diag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class LuckgramDiagTest {

    private static final String API = "api.luckgram.cc";

    // ---------------------------------------------------------------- proxy choice

    @Test
    public void proxyOffIsDirect() {
        assertEquals(LuckgramProxyRoute.DIRECT, LuckgramProxyRoute.decide(false, "SOCKS5", "1.2.3.4", 1080));
        assertEquals(LuckgramProxyRoute.DIRECT, LuckgramProxyRoute.decide(true, null, null, 0));
    }

    @Test
    public void socks5GoesThroughProxy() {
        assertEquals(LuckgramProxyRoute.SOCKS5, LuckgramProxyRoute.decide(true, "SOCKS5", "proxy.example", 1080));
    }

    @Test
    public void invalidSocks5IsDirect() {
        assertEquals(LuckgramProxyRoute.DIRECT, LuckgramProxyRoute.decide(true, "SOCKS5", " ", 1080));
        assertEquals(LuckgramProxyRoute.DIRECT, LuckgramProxyRoute.decide(true, "SOCKS5", "h", 0));
        assertEquals(LuckgramProxyRoute.DIRECT, LuckgramProxyRoute.decide(true, "SOCKS5", "h", 70000));
    }

    @Test
    public void mtprotoAndWebGoDirectAndSaySo() {
        assertEquals(LuckgramProxyRoute.MTPROTO_DIRECT, LuckgramProxyRoute.decide(true, "MTPROTO", "h", 443));
        assertEquals(LuckgramProxyRoute.WEB_DIRECT, LuckgramProxyRoute.decide(true, "WEB", "h", 0));
    }

    // ---------------------------------------------------------------- redaction

    @Test
    public void routeDropsQueryAndApiHost() {
        assertEquals("/v1/ai/chat", LuckgramDiagLog.route("https://api.luckgram.cc/v1/ai/chat?x=1#f", API));
        assertEquals("/v1/config", LuckgramDiagLog.route("/v1/config?token=secret", API));
    }

    @Test
    public void routeKeepsOnlyHostAndPathOfOtherUrls() {
        String r = LuckgramDiagLog.route("https://bucket.s3.amazonaws.com/square/p/ab/1.jpg?X-Amz-Signature=deadbeef&X-Amz-Credential=AKIA", API);
        assertEquals("bucket.s3.amazonaws.com/square/p/ab/1.jpg", r);
        assertFalse(r.contains("Signature"));
        assertEquals("host.example/", LuckgramDiagLog.route("https://user:pass@host.example", API));
    }

    @Test
    public void formatHasOnlyMetadata() {
        LuckgramDiagLog.Entry e = new LuckgramDiagLog.Entry();
        e.timeMs = 1761955200000L; // 2025-11-01T00:00:00Z
        e.feature = "summary";
        e.method = "POST";
        e.target = "https://api.luckgram.cc/v1/ai/chat?prompt=hello world";
        e.status = 502;
        e.durationMs = 1234;
        e.proxy = LuckgramProxyRoute.SOCKS5;
        e.requestId = "req-9f3a\nInjected: line";
        String line = LuckgramDiagLog.format(e, API);
        assertEquals("1761955200000 2025-11-01T00:00:00.000Z feature=summary route=POST /v1/ai/chat status=502 ms=1234 proxy=socks5 rid=req-9f3a_Injected:_line", line);
        assertFalse(line.contains("hello"));
        assertFalse(line.contains("\n"));
    }

    @Test
    public void formatFailureAndTelegramError() {
        LuckgramDiagLog.Entry e = new LuckgramDiagLog.Entry();
        e.timeMs = 1761955200000L;
        e.feature = "summary";
        e.target = "messages.getReplies";
        e.tgCode = 420;
        e.tgError = "FLOOD_WAIT_30";
        String line = LuckgramDiagLog.format(e, API);
        assertTrue(line, line.endsWith("feature=summary route=messages.getReplies tg=420:FLOOD_WAIT_30"));
        e.tgCode = 0;
        e.tgError = null;
        e.exception = "SocketTimeoutException";
        e.proxy = LuckgramProxyRoute.MTPROTO_DIRECT;
        assertTrue(LuckgramDiagLog.format(e, API).endsWith("exception=SocketTimeoutException proxy=mtproto-direct"));
    }

    // ---------------------------------------------------------------- retention

    @Test
    public void trimDropsOldLinesAndKeepsNewestWithinSize() {
        long now = 10L * 24 * 60 * 60 * 1000;
        List<String> lines = new ArrayList<>(Arrays.asList(
            (now - LuckgramDiagLog.MAX_AGE_MS - 1) + " old",
            "garbage",
            (now - 3000) + " a",
            (now - 2000) + " b",
            (now - 1000) + " c"));
        assertEquals(Arrays.asList((now - 3000) + " a", (now - 2000) + " b", (now - 1000) + " c"),
            LuckgramDiagLog.trim(lines, now, LuckgramDiagLog.MAX_AGE_MS, 1000));
        int oneLine = ((now - 1000) + " c").length() + 1;
        assertEquals(Arrays.asList((now - 2000) + " b", (now - 1000) + " c"),
            LuckgramDiagLog.trim(lines, now, LuckgramDiagLog.MAX_AGE_MS, oneLine * 2));
    }

    // ---------------------------------------------------------------- Telegram errors

    @Test
    public void telegramErrorKinds() {
        assertEquals(TelegramErrorKind.FLOOD_WAIT, TelegramErrorKind.of(420, "FLOOD_WAIT_30"));
        assertEquals(30, TelegramErrorKind.floodWaitSeconds("FLOOD_WAIT_30"));
        assertEquals(TelegramErrorKind.PRIVATE, TelegramErrorKind.of(400, "CHANNEL_PRIVATE"));
        assertEquals(TelegramErrorKind.NO_PERMISSION, TelegramErrorKind.of(400, "CHAT_ADMIN_REQUIRED"));
        assertEquals(TelegramErrorKind.NO_PERMISSION, TelegramErrorKind.of(403, "SOMETHING_NEW"));
        assertEquals(TelegramErrorKind.NOT_FOUND, TelegramErrorKind.of(400, "MSG_ID_INVALID"));
        assertEquals(TelegramErrorKind.OTHER, TelegramErrorKind.of(400, "SOMETHING_NEW"));
        assertEquals(TelegramErrorKind.NETWORK, TelegramErrorKind.of(-1000, "Timeout"));
        assertEquals(TelegramErrorKind.NETWORK, TelegramErrorKind.of(0, null));
        assertEquals(0, TelegramErrorKind.floodWaitSeconds("CHANNEL_PRIVATE"));
    }
}
