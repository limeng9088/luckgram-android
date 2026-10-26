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

    private static final String TINY_PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger chats = new AtomicInteger();
    private volatile String forcedError;
    private volatile int chatLimit = 50;

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
                    return json(200, new JSONObject().put("image", new JSONObject().put("b64", TINY_PNG).put("mime", "image/png")));
                case "/v1/ai/transcribe": {
                    String head = new String(body, 0, Math.min(body.length, 512), StandardCharsets.ISO_8859_1);
                    if (!head.contains("name=\"file\"")) return error(400, "invalid_request");
                    return json(200, new JSONObject().put("text", LuckgramStrings.isChinese() ? "这是一段 mock 转写结果。" : "This is a mock transcription.").put("language", "zh").put("duration", 1.0));
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
