package org.telegram.luckgram;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-app mock of the Luckgram API server (same paths, bodies and error format as
 * docs/api-server.md). Used by the debug page until the real server is live; it never touches
 * the network. {@link #requestCount()} lets tests check that nothing was sent.
 */
public final class LuckgramAIMock implements LuckgramAI.Transport {

    /** Host used for absolute URLs (temporary image files) served by the mock. */
    public static final String HOST = "luckgram-mock.invalid";

    private static final String TINY_PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger chats = new AtomicInteger();
    private volatile String forcedError;
    private volatile int chatLimit = 50;
    private final AtomicInteger transcribeCalls = new AtomicInteger();

    /** Number of /v1/ai/transcribe requests so far (one per uploaded segment). */
    public int transcribeCount() {
        return transcribeCalls.get();
    }

    public int requestCount() {
        return requests.get();
    }

    /** Next request (other than registration) fails with this error code. */
    public void failNext(String code) {
        forcedError = code;
    }

    public void setChatLimit(int limit) {
        chatLimit = limit;
        chats.set(0);
    }

    @Override
    public LuckgramAI.HttpResponse execute(String method, String path, Map<String, String> headers, byte[] body) throws IOException {
        requests.incrementAndGet();
        String query = "";
        if (path.startsWith("https://" + HOST)) {
            path = path.substring(("https://" + HOST).length());
        }
        int q = path.indexOf('?');
        if (q >= 0) {
            query = path.substring(q + 1);
            path = path.substring(0, q);
        }
        // CloudFront OAC: a request with a body and no / a wrong payload hash is rejected with 403.
        if (body != null && !LuckgramAI.sha256Hex(body).equals(headers.get("x-amz-content-sha256"))) {
            return new LuckgramAI.HttpResponse(403, "application/json", new ByteArrayInputStream("{\"message\":\"Forbidden\"}".getBytes(StandardCharsets.UTF_8)), null);
        }
        try {
            if ("POST".equals(method) && "/v1/devices".equals(path)) {
                JSONObject req = new JSONObject(new String(body, StandardCharsets.UTF_8));
                if (!req.has("platform")) return error(400, "invalid_request");
                return json(200, new JSONObject().put("token", "mock_" + Long.toHexString(System.nanoTime())));
            }
            String auth = headers.get("Authorization");
            if (auth == null || !auth.startsWith("Bearer mock_")) {
                return error(401, "invalid_token");
            }
            String forced = forcedError;
            if (forced != null) {
                forcedError = null;
                return error("quota_exceeded".equals(forced) || "rate_limited".equals(forced) ? 429 : "content_rejected".equals(forced) ? 422 : 503, forced);
            }
            switch (path) {
                case "/v1/ai/chat": {
                    JSONObject req = new JSONObject(new String(body, StandardCharsets.UTF_8));
                    JSONArray messages = req.getJSONArray("messages");
                    if (chats.incrementAndGet() > chatLimit) return error(429, "quota_exceeded");
                    String reply = (LuckgramStrings.isChinese() ? "Mock 回复：收到 " + messages.length() + " 条消息，task = " : "Mock reply: got " + messages.length() + " message(s), task = ")
                        + req.optString("task") + ".";
                    if (!req.optBoolean("stream")) {
                        return json(200, new JSONObject().put("text", reply));
                    }
                    return sse(reply);
                }
                case "/v1/ai/image":
                    return json(200, new JSONObject()
                        .put("url", "https://" + HOST + "/v1/ai/image/files/mock.png")
                        .put("mime", "image/png")
                        .put("expires_at", "2099-01-01T00:00:00Z"));
                case "/v1/ai/image/files/mock.png":
                    return new LuckgramAI.HttpResponse(200, "image/png", new ByteArrayInputStream(android.util.Base64.decode(TINY_PNG, android.util.Base64.DEFAULT)), null);
                case "/v1/ai/transcribe": {
                    String ctype = headers.get("Content-Type");
                    if (ctype == null || !ctype.startsWith("audio/") || body == null || body.length == 0) return error(400, "invalid_request");
                    if (body.length > LuckgramSettings.remoteLong("ai.transcribe_max_mb", 4) * 1024 * 1024) return error(413, "input_too_large");
                    int n = transcribeCalls.incrementAndGet();
                    String lang = query.startsWith("language=") ? query.substring(9) : "zh";
                    if (!lang.matches("[a-z]{2}")) return error(400, "invalid_request");
                    String text = (LuckgramStrings.isChinese() ? "第" + n + "段（" + body.length + " 字节）。" : "Segment " + n + " (" + body.length + " bytes).");
                    return json(200, new JSONObject().put("text", text).put("language", lang).put("duration_seconds", body.length / 32000.0).put("model", "mock"));
                }
                case "/v1/me":
                    return json(200, new JSONObject().put("plan", "free").put("quota", new JSONObject()
                        .put("chat", new JSONObject().put("used", chats.get()).put("limit", chatLimit))
                        .put("image", new JSONObject().put("used", 0).put("limit", 5))
                        .put("transcribe_minutes", new JSONObject().put("used", 0).put("limit", 30))));
                default:
                    return error(404, "not_found");
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            return error(400, "invalid_request");
        }
    }

    private static LuckgramAI.HttpResponse json(int status, JSONObject o) {
        return new LuckgramAI.HttpResponse(status, "application/json", new ByteArrayInputStream(o.toString().getBytes(StandardCharsets.UTF_8)), null);
    }

    private static LuckgramAI.HttpResponse error(int status, String code) {
        try {
            return json(status, new JSONObject().put("error", new JSONObject().put("code", code).put("message", code)));
        } catch (Exception e) {
            return new LuckgramAI.HttpResponse(status, "text/plain", new ByteArrayInputStream(new byte[0]), null);
        }
    }

    /** Streams the reply two characters at a time, 40 ms apart, like the real server. */
    private static LuckgramAI.HttpResponse sse(String reply) throws IOException {
        PipedInputStream in = new PipedInputStream(64 * 1024);
        PipedOutputStream out = new PipedOutputStream(in);
        Thread t = new Thread(() -> {
            try {
                for (int i = 0; i < reply.length(); i += 2) {
                    String piece = reply.substring(i, Math.min(reply.length(), i + 2));
                    out.write(("data: " + new JSONObject().put("delta", piece) + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    Thread.sleep(40);
                }
                out.write("data: {\"done\":true}\n\n".getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignore) {
            } finally {
                try {
                    out.close();
                } catch (IOException ignore) {
                }
            }
        }, "LuckgramAIMock-sse");
        t.setDaemon(true);
        t.start();
        return new LuckgramAI.HttpResponse(200, "text/event-stream; charset=utf-8", in, null);
    }
}
