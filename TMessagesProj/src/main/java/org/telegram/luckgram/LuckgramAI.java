package org.telegram.luckgram;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The only client of the Luckgram API server's AI endpoints. Every AI feature goes through it:
 * device registration, chat (with SSE streaming), image, transcription, quota ({@code /v1/me}),
 * error messages and the one-time consent dialog.
 *
 * <p>Callbacks are always delivered on the UI thread. Every call takes a {@link Context} used to
 * show the consent dialog; if consent has not been given and the user declines (or no context is
 * passed), the callback gets {@link LuckgramAIError#CONSENT_DENIED} and no network request is made.
 *
 * <pre>
 * LuckgramAI.getInstance().chat(getParentActivity(), "summary", messages, true, new LuckgramAI.ChatListener() {
 *     public void onDelta(String delta, String text) { textView.setText(text); }
 *     public void onComplete(String text) { ... }
 *     public void onError(LuckgramAIError e) { LuckgramAI.showError(fragment, e); }
 * });
 * </pre>
 */
public final class LuckgramAI {

    public static final String TASK_CHAT = "chat";
    public static final String TASK_SUMMARY = "summary";
    public static final String TASK_REPLY = "reply";
    public static final String TASK_WRITING = "writing";
    public static final String TASK_AGENT = "agent";
    public static final String TASK_POST = "post";

    private static final String KEY_CONSENT = "ai_consent";
    private static final String KEY_TOKEN = "_device_token";
    private static final String KEY_USE_MOCK = "_ai_use_mock";

    // ---------------------------------------------------------------- public types

    public static final class Message {
        public final String role;
        public final String content;

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public static Message system(String content) {
            return new Message("system", content);
        }

        public static Message user(String content) {
            return new Message("user", content);
        }

        public static Message assistant(String content) {
            return new Message("assistant", content);
        }
    }

    public interface ChatListener {
        /** A streamed piece; {@code text} is everything received so far. Not called when {@code stream=false}. */
        void onDelta(String delta, String text);

        void onComplete(String text);

        void onError(LuckgramAIError error);
    }

    public interface Callback<T> {
        void onResult(T result);

        void onError(LuckgramAIError error);
    }

    public static final class Image {
        /** Temporary link returned by the server (may be null if the server returned bytes). */
        public String url;
        /** Image bytes, if the server returned them inline. */
        public byte[] data;
        public String mimeType;
    }

    public static final class Transcription {
        public String text;
        public String language;
        public double durationSeconds;
    }

    /** Handle returned by every call. */
    public static final class Request {
        private volatile boolean cancelled;
        private volatile Closeable connection;

        public void cancel() {
            cancelled = true;
            Closeable c = connection;
            if (c != null) {
                try {
                    c.close();
                } catch (Exception ignore) {
                }
            }
        }

        public boolean isCancelled() {
            return cancelled;
        }
    }

    // ---------------------------------------------------------------- transport (real HTTP or mock)

    /** Raw HTTP response. {@code body} must be closed by the caller. */
    public static final class HttpResponse implements Closeable {
        public final int status;
        public final String contentType;
        public final InputStream body;
        private final Closeable onClose;

        public HttpResponse(int status, String contentType, InputStream body, Closeable onClose) {
            this.status = status;
            this.contentType = contentType == null ? "" : contentType;
            this.body = body;
            this.onClose = onClose;
        }

        @Override
        public void close() {
            try {
                if (body != null) body.close();
            } catch (Exception ignore) {
            }
            try {
                if (onClose != null) onClose.close();
            } catch (Exception ignore) {
            }
        }
    }

    public interface Transport {
        HttpResponse execute(String method, String path, Map<String, String> headers, byte[] body) throws IOException;
    }

    private static final class HttpTransport implements Transport {
        @Override
        public HttpResponse execute(String method, String path, Map<String, String> headers, byte[] body) throws IOException {
            HttpURLConnection c = (HttpURLConnection) new URL(apiBaseUrl() + path).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(15_000);
            c.setReadTimeout(120_000);
            c.setUseCaches(false);
            for (Map.Entry<String, String> h : headers.entrySet()) {
                c.setRequestProperty(h.getKey(), h.getValue());
            }
            if (body != null) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(body.length);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body);
                }
            }
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            return new HttpResponse(status, c.getContentType(), in, c::disconnect);
        }
    }

    // ---------------------------------------------------------------- singleton

    private static volatile LuckgramAI instance;

    public static LuckgramAI getInstance() {
        if (instance == null) {
            synchronized (LuckgramAI.class) {
                if (instance == null) {
                    instance = new LuckgramAI();
                }
            }
        }
        return instance;
    }

    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "LuckgramAI");
        t.setDaemon(true);
        return t;
    });
    private final Transport httpTransport = new HttpTransport();
    private final LuckgramAIMock mockTransport = new LuckgramAIMock();
    private String token;

    private LuckgramAI() {
    }

    /** API server base URL from the build configuration (debug: api-dev, release: api). */
    public static String apiBaseUrl() {
        return BuildConfig.LUCKGRAM_API_URL;
    }

    /** Debug tools only: route all calls to the in-app mock server instead of the network. */
    public boolean isUsingMock() {
        return LuckgramSettings.isDebugToolsVisible() && LuckgramSettings.getBool(KEY_USE_MOCK, false);
    }

    public void setUseMock(boolean useMock) {
        LuckgramSettings.setBool(KEY_USE_MOCK, useMock);
        synchronized (this) {
            token = null;
        }
    }

    public LuckgramAIMock getMock() {
        return mockTransport;
    }

    private Transport transport() {
        return isUsingMock() ? mockTransport : httpTransport;
    }

    // ---------------------------------------------------------------- consent

    public boolean hasConsent() {
        return LuckgramSettings.getBool(KEY_CONSENT, false);
    }

    /** Withdraws consent; the next AI call asks again. */
    public void revokeConsent() {
        LuckgramSettings.setBool(KEY_CONSENT, false);
    }

    /** Shows the consent dialog if needed; {@code done} gets true only if the user agreed. */
    public void requestConsent(Context context, Utilities.Callback<Boolean> done) {
        if (hasConsent()) {
            done.run(true);
            return;
        }
        if (context == null) {
            done.run(false);
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            final boolean[] decided = {false};
            try {
                new AlertDialog.Builder(context)
                    .setTitle(LuckgramStrings.aiConsentTitle())
                    .setMessage(LuckgramStrings.aiConsentText())
                    .setPositiveButton(LuckgramStrings.aiConsentAgree(), (d, w) -> {
                        decided[0] = true;
                        LuckgramSettings.setBool(KEY_CONSENT, true);
                        done.run(true);
                    })
                    .setNegativeButton(LuckgramStrings.aiConsentDecline(), (d, w) -> {
                        decided[0] = true;
                        done.run(false);
                    })
                    .setOnDismissListener(d -> {
                        if (!decided[0]) {
                            decided[0] = true;
                            done.run(false);
                        }
                    })
                    .show();
            } catch (Exception e) {
                FileLog.e(e);
                if (!decided[0]) {
                    decided[0] = true;
                    done.run(false);
                }
            }
        });
    }

    // ---------------------------------------------------------------- local state

    /** Forget the device token (used on logout of the last account and by the debug page). */
    public void resetLocalState() {
        synchronized (this) {
            token = null;
        }
        LuckgramSecureStore.set(KEY_TOKEN, null);
        LuckgramSecureStore.deleteKey();
    }

    public synchronized boolean hasDeviceToken() {
        return token != null || (!isUsingMock() && LuckgramSecureStore.get(KEY_TOKEN) != null);
    }

    // ---------------------------------------------------------------- public API

    public Request chat(Context context, String task, List<Message> messages, boolean stream, ChatListener listener) {
        Request req = new Request();
        int chars = 0;
        JSONObject body = new JSONObject();
        try {
            JSONArray arr = new JSONArray();
            for (Message m : messages) {
                chars += m.content == null ? 0 : m.content.length();
                arr.put(new JSONObject().put("role", m.role).put("content", m.content == null ? "" : m.content));
            }
            body.put("task", task).put("messages", arr).put("stream", stream);
        } catch (Exception e) {
            deliverError(listener::onError, new LuckgramAIError(LuckgramAIError.INVALID_REQUEST, 0));
            return req;
        }
        if (chars > LuckgramSettings.remoteLong("ai.max_input_chars", 60000)) {
            deliverError(listener::onError, new LuckgramAIError(LuckgramAIError.INPUT_TOO_LARGE, 0));
            return req;
        }
        final byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        withConsent(context, req, listener::onError, () -> {
            try (HttpResponse res = authorized(req, "POST", "/v1/ai/chat", "application/json", payload, stream ? "text/event-stream" : "application/json")) {
                if (res.status >= 300) {
                    throw new ApiException(errorFrom(res));
                }
                if (stream && res.contentType.startsWith("text/event-stream")) {
                    String text = readSse(req, res.body, listener);
                    if (text != null) {
                        AndroidUtilities.runOnUIThread(() -> listener.onComplete(text));
                    }
                } else {
                    JSONObject j = new JSONObject(readString(res.body));
                    String text = j.optString("text", j.optString("content", ""));
                    AndroidUtilities.runOnUIThread(() -> listener.onComplete(text));
                }
            }
        });
        return req;
    }

    public Request image(Context context, String prompt, Callback<Image> callback) {
        Request req = new Request();
        final byte[] payload;
        try {
            payload = new JSONObject().put("prompt", prompt).toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            deliverError(callback::onError, new LuckgramAIError(LuckgramAIError.INVALID_REQUEST, 0));
            return req;
        }
        withConsent(context, req, callback::onError, () -> {
            try (HttpResponse res = authorized(req, "POST", "/v1/ai/image", "application/json", payload, "application/json")) {
                if (res.status >= 300) {
                    throw new ApiException(errorFrom(res));
                }
                JSONObject j = new JSONObject(readString(res.body));
                JSONObject img = j.optJSONObject("image") != null ? j.optJSONObject("image") : j;
                Image out = new Image();
                out.url = optString(img, "url");
                String b64 = optString(img, "b64");
                if (b64 != null) {
                    out.data = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                }
                out.mimeType = img.optString("mime", "image/png");
                if (out.url == null && out.data == null) {
                    throw new ApiException(new LuckgramAIError(LuckgramAIError.UPSTREAM_UNAVAILABLE, res.status));
                }
                AndroidUtilities.runOnUIThread(() -> callback.onResult(out));
            }
        });
        return req;
    }

    /**
     * @param mimeType e.g. {@code audio/ogg}, {@code audio/mp4}, {@code audio/wav}
     * @param language optional ISO-639-1 hint, or null
     */
    public Request transcribe(Context context, byte[] audio, String mimeType, String language, Callback<Transcription> callback) {
        Request req = new Request();
        long maxBytes = LuckgramSettings.remoteLong("ai.transcribe_max_mb", 25) * 1024 * 1024;
        if (audio == null || audio.length == 0 || audio.length > maxBytes) {
            deliverError(callback::onError, new LuckgramAIError(audio == null || audio.length == 0 ? LuckgramAIError.INVALID_REQUEST : LuckgramAIError.INPUT_TOO_LARGE, 0));
            return req;
        }
        String boundary = "luckgram" + Long.toHexString(Utilities.random.nextLong());
        byte[] payload;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(audio.length + 512);
            String ext = extensionFor(mimeType);
            bos.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio." + ext + "\"\r\nContent-Type: " + mimeType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            bos.write(audio);
            bos.write("\r\n".getBytes(StandardCharsets.UTF_8));
            if (!TextUtils.isEmpty(language)) {
                bos.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"language\"\r\n\r\n" + language + "\r\n").getBytes(StandardCharsets.UTF_8));
            }
            bos.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            payload = bos.toByteArray();
        } catch (IOException e) {
            deliverError(callback::onError, new LuckgramAIError(LuckgramAIError.INVALID_REQUEST, 0));
            return req;
        }
        withConsent(context, req, callback::onError, () -> {
            try (HttpResponse res = authorized(req, "POST", "/v1/ai/transcribe", "multipart/form-data; boundary=" + boundary, payload, "application/json")) {
                if (res.status >= 300) {
                    throw new ApiException(errorFrom(res));
                }
                JSONObject j = new JSONObject(readString(res.body));
                Transcription t = new Transcription();
                t.text = j.optString("text", "");
                t.language = optString(j, "language");
                t.durationSeconds = j.optDouble("duration", 0);
                AndroidUtilities.runOnUIThread(() -> callback.onResult(t));
            }
        });
        return req;
    }

    /** {@code GET /v1/me}: quota usage and limits, returned as the raw JSON object. */
    public Request me(Context context, Callback<JSONObject> callback) {
        Request req = new Request();
        withConsent(context, req, callback::onError, () -> {
            try (HttpResponse res = authorized(req, "GET", "/v1/me", null, null, "application/json")) {
                if (res.status >= 300) {
                    throw new ApiException(errorFrom(res));
                }
                JSONObject j = new JSONObject(readString(res.body));
                AndroidUtilities.runOnUIThread(() -> callback.onResult(j));
            }
        });
        return req;
    }

    /** Shows the error's message as a bulletin in {@code fragment}. */
    public static void showError(BaseFragment fragment, LuckgramAIError error) {
        if (fragment == null || error == null || LuckgramAIError.CANCELLED.equals(error.code)) {
            return;
        }
        BulletinFactory.of(fragment).createErrorBulletin(error.getLocalizedMessage()).show();
    }

    // ---------------------------------------------------------------- internals

    private static final class ApiException extends Exception {
        final LuckgramAIError error;

        ApiException(LuckgramAIError error) {
            super(error.code);
            this.error = error;
        }
    }

    private interface Work {
        void run() throws Exception;
    }

    private void withConsent(Context context, Request req, Utilities.Callback<LuckgramAIError> onError, Work work) {
        requestConsent(context, granted -> {
            if (!granted) {
                deliverError(onError, new LuckgramAIError(LuckgramAIError.CONSENT_DENIED, 0));
                return;
            }
            executor.execute(() -> {
                try {
                    if (req.cancelled) {
                        throw new ApiException(new LuckgramAIError(LuckgramAIError.CANCELLED, 0));
                    }
                    work.run();
                } catch (ApiException e) {
                    deliverError(onError, e.error);
                } catch (Exception e) {
                    deliverError(onError, new LuckgramAIError(req.cancelled ? LuckgramAIError.CANCELLED
                        : (e instanceof IOException ? LuckgramAIError.NETWORK_ERROR : LuckgramAIError.UPSTREAM_UNAVAILABLE), 0));
                }
            });
        });
    }

    private static void deliverError(Utilities.Callback<LuckgramAIError> onError, LuckgramAIError e) {
        AndroidUtilities.runOnUIThread(() -> onError.run(e));
    }

    /** Sends an authenticated request; registers first if needed and re-registers once on invalid_token. */
    private HttpResponse authorized(Request req, String method, String path, String contentType, byte[] body, String accept) throws Exception {
        for (int attempt = 0; ; attempt++) {
            String t = ensureToken(req);
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Authorization", "Bearer " + t);
            headers.put("Accept", accept);
            if (contentType != null) headers.put("Content-Type", contentType);
            HttpResponse res = send(req, method, path, headers, body);
            if (res.status == 401 && attempt == 0) {
                res.close();
                synchronized (this) {
                    token = null;
                }
                LuckgramSecureStore.set(KEY_TOKEN, null);
                continue;
            }
            return res;
        }
    }

    private HttpResponse send(Request req, String method, String path, Map<String, String> headers, byte[] body) throws Exception {
        if (req.cancelled) {
            throw new ApiException(new LuckgramAIError(LuckgramAIError.CANCELLED, 0));
        }
        HttpResponse res = transport().execute(method, path, headers, body);
        req.connection = res;
        return res;
    }

    private String ensureToken(Request req) throws Exception {
        synchronized (this) {
            if (token != null) return token;
            if (!isUsingMock()) {
                token = LuckgramSecureStore.get(KEY_TOKEN);
                if (token != null) return token;
            }
            JSONObject reg = new JSONObject()
                .put("platform", "android")
                .put("app_version", BuildConfig.BUILD_VERSION_STRING)
                .put("locale", Locale.getDefault().toLanguageTag());
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/json");
            headers.put("Accept", "application/json");
            try (HttpResponse res = send(req, "POST", "/v1/devices", headers, reg.toString().getBytes(StandardCharsets.UTF_8))) {
                if (res.status >= 300) {
                    throw new ApiException(errorFrom(res));
                }
                JSONObject j = new JSONObject(readString(res.body));
                String t = optString(j, "token");
                if (t == null) t = optString(j, "device_token");
                if (t == null) {
                    throw new ApiException(new LuckgramAIError(LuckgramAIError.UPSTREAM_UNAVAILABLE, res.status));
                }
                token = t;
                if (!isUsingMock()) {
                    LuckgramSecureStore.set(KEY_TOKEN, t);
                }
                return t;
            }
        }
    }

    /** Reads an SSE stream; returns the full text, or null if an error was delivered. */
    private String readSse(Request req, InputStream in, ChatListener listener) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder full = new StringBuilder();
        String event = null;
        StringBuilder data = new StringBuilder();
        String line;
        try {
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) {
                    if (data.length() > 0) {
                        LuckgramAIError err = handleSseEvent(event, data.toString(), full, listener);
                        if (err != null) {
                            deliverError(listener::onError, err);
                            return null;
                        }
                    }
                    event = null;
                    data.setLength(0);
                } else if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) data.append('\n');
                    data.append(line.substring(5).trim());
                }
            }
            if (data.length() > 0) {
                LuckgramAIError err = handleSseEvent(event, data.toString(), full, listener);
                if (err != null) {
                    deliverError(listener::onError, err);
                    return null;
                }
            }
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException e) {
            if (req.cancelled) {
                throw new ApiException(new LuckgramAIError(LuckgramAIError.CANCELLED, 0));
            }
            throw e;
        }
        return full.toString();
    }

    private static LuckgramAIError handleSseEvent(String event, String data, StringBuilder full, ChatListener listener) {
        if ("[DONE]".equals(data)) {
            return null;
        }
        JSONObject j;
        try {
            j = new JSONObject(data);
        } catch (Exception e) {
            return null;
        }
        if ("error".equals(event) || j.has("error")) {
            return parseError(j, 200);
        }
        String delta = optString(j, "delta");
        if (delta == null) {
            // Also accept OpenAI-style chunks: {"choices":[{"delta":{"content":"..."}}]}
            JSONArray choices = j.optJSONArray("choices");
            JSONObject c = choices != null ? choices.optJSONObject(0) : null;
            JSONObject d = c != null ? c.optJSONObject("delta") : null;
            delta = d != null ? optString(d, "content") : null;
        }
        if (!TextUtils.isEmpty(delta)) {
            full.append(delta);
            final String piece = delta, text = full.toString();
            AndroidUtilities.runOnUIThread(() -> listener.onDelta(piece, text));
        }
        return null;
    }

    static LuckgramAIError errorFrom(HttpResponse res) {
        String body = null;
        try {
            body = readString(res.body);
        } catch (Exception ignore) {
        }
        try {
            if (body != null) {
                return parseError(new JSONObject(body), res.status);
            }
        } catch (Exception ignore) {
        }
        return new LuckgramAIError(codeForStatus(res.status), res.status);
    }

    private static LuckgramAIError parseError(JSONObject j, int status) {
        JSONObject e = j.optJSONObject("error");
        String code = e != null ? optString(e, "code") : null;
        if (code == null) code = codeForStatus(status);
        if ("unauthorized".equals(code)) code = LuckgramAIError.INVALID_TOKEN;
        return new LuckgramAIError(code, status);
    }

    private static String codeForStatus(int status) {
        if (status == 401) return LuckgramAIError.INVALID_TOKEN;
        if (status == 413) return LuckgramAIError.INPUT_TOO_LARGE;
        if (status == 429) return LuckgramAIError.RATE_LIMITED;
        if (status >= 400 && status < 500) return LuckgramAIError.INVALID_REQUEST;
        return LuckgramAIError.UPSTREAM_UNAVAILABLE;
    }

    private static String optString(JSONObject j, String key) {
        if (j == null || !j.has(key) || j.isNull(key)) return null;
        String v = j.optString(key, null);
        return TextUtils.isEmpty(v) ? null : v;
    }

    static String readString(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String extensionFor(String mime) {
        if (mime == null) return "ogg";
        switch (mime) {
            case "audio/mpeg":
            case "audio/mp3":
                return "mp3";
            case "audio/mp4":
            case "audio/m4a":
            case "audio/aac":
                return "m4a";
            case "audio/wav":
            case "audio/x-wav":
                return "wav";
            case "audio/webm":
                return "webm";
            default:
                return "ogg";
        }
    }

    /** Mono 16-bit PCM WAV with a 1 s 440 Hz tone; used by the debug self-test. */
    public static byte[] testToneWav() {
        int rate = 16000, samples = rate;
        ByteArrayOutputStream b = new ByteArrayOutputStream(44 + samples * 2);
        int dataLen = samples * 2;
        writeAscii(b, "RIFF");
        writeLE(b, 36 + dataLen, 4);
        writeAscii(b, "WAVEfmt ");
        writeLE(b, 16, 4);
        writeLE(b, 1, 2);
        writeLE(b, 1, 2);
        writeLE(b, rate, 4);
        writeLE(b, rate * 2, 4);
        writeLE(b, 2, 2);
        writeLE(b, 16, 2);
        writeAscii(b, "data");
        writeLE(b, dataLen, 4);
        for (int i = 0; i < samples; i++) {
            writeLE(b, (int) (Math.sin(2 * Math.PI * 440 * i / rate) * 8000), 2);
        }
        return b.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream b, String s) {
        byte[] x = s.getBytes(StandardCharsets.US_ASCII);
        b.write(x, 0, x.length);
    }

    private static void writeLE(ByteArrayOutputStream b, int v, int bytes) {
        for (int i = 0; i < bytes; i++) {
            b.write((v >> (8 * i)) & 0xFF);
        }
    }
}
