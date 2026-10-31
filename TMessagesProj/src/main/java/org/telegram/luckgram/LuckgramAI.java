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
import java.security.MessageDigest;
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
        /** When the temporary link expires (ISO 8601, from the server), or null. */
        public String expiresAt;
    }

    public static final class Transcription {
        public String text;
        public String language;
        public double durationSeconds;
        /** Number of segments the file was split into (1 unless it was over ai.transcribe_max_mb). */
        public int segments = 1;
        /** Segments transcribed successfully; less than {@link #segments} when {@link #partial}. */
        public int completedSegments;
        /**
         * True when a later segment failed (e.g. {@code quota_exceeded}) after earlier ones succeeded:
         * {@link #text} holds the completed part and {@link #error} says why the rest is missing (#55).
         * The server charges each successful segment, so that text is kept instead of discarded.
         */
        public boolean partial;
        public LuckgramAIError error;
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
            // path is relative to the API server, or an absolute https URL (temporary files).
            HttpURLConnection c = (HttpURLConnection) new URL(path.startsWith("https://") ? path : apiBaseUrl() + path).openConnection();
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

    /**
     * Blocking authenticated GET for non-AI endpoints (remote config, task S4). Registers the device
     * if needed but never asks for AI consent: no user content is sent. Call off the main thread;
     * the caller closes the response.
     */
    public HttpResponse getBlocking(String path, Map<String, String> extraHeaders) throws Exception {
        return authorized(new Request(), "GET", path, null, null, "application/json", extraHeaders);
    }

    /**
     * Authenticated JSON request for the account endpoints (S6 link, S7 billing, S8 announcements).
     * Registers the device if needed but never asks for AI consent: nothing but the device's own
     * state is sent. {@code body} may be null. The callback runs on the UI thread.
     */
    public Request requestJson(String method, String path, JSONObject body, Callback<JSONObject> callback) {
        Request req = new Request();
        executor.execute(() -> {
            try {
                byte[] payload = body != null ? body.toString().getBytes(StandardCharsets.UTF_8) : null;
                Map<String, String> extra = new LinkedHashMap<>();
                extra.put("X-Luckgram-App-Version", BuildConfig.BUILD_VERSION_STRING);
                try (HttpResponse res = authorized(req, method, path, payload != null ? "application/json" : null, payload, "application/json", extra)) {
                    if (res.status >= 300) {
                        throw new ApiException(errorFrom(res));
                    }
                    String text = readString(res.body);
                    JSONObject j = text.isEmpty() ? new JSONObject() : new JSONObject(text);
                    AndroidUtilities.runOnUIThread(() -> {
                        if (!req.cancelled) callback.onResult(j);
                    });
                }
            } catch (ApiException e) {
                if (!req.cancelled) deliverError(callback::onError, e.error);
            } catch (Exception e) {
                if (!req.cancelled) deliverError(callback::onError, new LuckgramAIError(e instanceof IOException ? LuckgramAIError.NETWORK_ERROR : LuckgramAIError.UPSTREAM_UNAVAILABLE, 0));
            }
        });
        return req;
    }

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
            Image out = new Image();
            try (HttpResponse res = authorized(req, "POST", "/v1/ai/image", "application/json", payload, "application/json")) {
                if (res.status >= 300) {
                    throw new ApiException(errorFrom(res));
                }
                // docs/api-server.md §3.1: {"url","mime","expires_at"}
                JSONObject j = new JSONObject(readString(res.body));
                JSONObject img = j.optJSONObject("image") != null ? j.optJSONObject("image") : j;
                out.url = optString(img, "url");
                out.mimeType = img.optString("mime", "image/png");
                out.expiresAt = optString(img, "expires_at");
                String b64 = optString(img, "b64");
                if (b64 != null) {
                    out.data = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                }
            }
            if (out.data == null && out.url != null) {
                out.data = download(req, out.url);
            }
            if (out.data == null) {
                throw new ApiException(new LuckgramAIError(LuckgramAIError.UPSTREAM_UNAVAILABLE, 0));
            }
            AndroidUtilities.runOnUIThread(() -> callback.onResult(out));
        });
        return req;
    }

    /**
     * Downloads a temporary file returned by the API server. The device token is sent only to the
     * API server's own host (§3.1: "带同样的 Bearer 头"), never to a third-party host.
     */
    private byte[] download(Request req, String url) throws Exception {
        boolean ownHost = isApiHost(url);
        HttpResponse res;
        if (ownHost) {
            res = authorized(req, "GET", url, null, null, "*/*");
        } else {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Accept", "*/*");
            res = send(req, "GET", url, headers, null);
        }
        try {
            if (res.status >= 300) {
                throw new ApiException(errorFrom(res));
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = res.body.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            res.close();
        }
    }

    private boolean isApiHost(String url) {
        try {
            String host = new URL(url).getHost();
            String apiHost = isUsingMock() ? LuckgramAIMock.HOST : new URL(apiBaseUrl()).getHost();
            return host != null && host.equalsIgnoreCase(apiHost);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Transcribes an audio file. The request body is the audio itself (§3.1 item 5). Files larger
     * than {@code ai.transcribe_max_mb} are split by {@link LuckgramAudioSplitter}, uploaded one
     * after another and their texts joined in order. Formats that can't be split (MP4/M4A, WebM,
     * FLAC) fail with {@code input_too_large} when over the limit.
     *
     * @param mimeType e.g. {@code audio/ogg}, {@code audio/mpeg}, {@code audio/mp4}, {@code audio/wav}
     * @param language optional ISO-639-1 (2-letter) hint, or null; other values are ignored
     */
    public Request transcribe(Context context, byte[] audio, String mimeType, String language, Callback<Transcription> callback) {
        Request req = new Request();
        if (audio == null || audio.length == 0 || mimeType == null || !mimeType.startsWith("audio/")) {
            deliverError(callback::onError, new LuckgramAIError(LuckgramAIError.INVALID_REQUEST, 0));
            return req;
        }
        int maxBytes = (int) Math.min(Integer.MAX_VALUE, LuckgramSettings.remoteLong("ai.transcribe_max_mb", 4) * 1024 * 1024);
        List<byte[]> segments = LuckgramAudioSplitter.split(audio, mimeType, maxBytes);
        if (segments == null || segments.isEmpty()) {
            deliverError(callback::onError, new LuckgramAIError(LuckgramAIError.INPUT_TOO_LARGE, 0));
            return req;
        }
        String path = "/v1/ai/transcribe";
        // The server accepts ISO-639-1 (2 letters) only; anything else -> let it auto-detect (#49).
        if (language != null && language.matches("[a-z]{2}")) {
            path += "?language=" + language;
        }
        final String finalPath = path;
        withConsent(context, req, callback::onError, () -> {
            Transcription t = new Transcription();
            t.segments = segments.size();
            StringBuilder text = new StringBuilder();
            for (byte[] segment : segments) {
                try (HttpResponse res = authorized(req, "POST", finalPath, mimeType, segment, "application/json")) {
                    if (res.status >= 300) {
                        throw new ApiException(errorFrom(res));
                    }
                    JSONObject j = new JSONObject(readString(res.body));
                    appendTranscript(text, j.optString("text", ""));
                    if (t.language == null) {
                        t.language = optString(j, "language");
                    }
                    t.durationSeconds += j.optDouble("duration_seconds", j.optDouble("duration", 0));
                    t.completedSegments++;
                } catch (Exception e) {
                    // Nothing done yet, or cancelled by the caller: a plain error, as before.
                    if (t.completedSegments == 0 || req.cancelled) {
                        throw e;
                    }
                    t.partial = true;
                    t.error = e instanceof ApiException ? ((ApiException) e).error
                        : new LuckgramAIError(e instanceof IOException ? LuckgramAIError.NETWORK_ERROR : LuckgramAIError.UPSTREAM_UNAVAILABLE, 0);
                    break;
                }
            }
            t.text = text.toString();
            AndroidUtilities.runOnUIThread(() -> callback.onResult(t));
        });
        return req;
    }

    /** Joins segment texts; no space between CJK characters. */
    static void appendTranscript(StringBuilder out, String piece) {
        piece = piece.trim();
        if (piece.isEmpty()) return;
        if (out.length() > 0 && !isCjk(out.charAt(out.length() - 1)) && !isCjk(piece.charAt(0))) {
            out.append(' ');
        }
        out.append(piece);
    }

    private static boolean isCjk(char c) {
        Character.UnicodeBlock b = Character.UnicodeBlock.of(c);
        return b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS || b == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
            || b == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS || b == Character.UnicodeBlock.HIRAGANA
            || b == Character.UnicodeBlock.KATAKANA || b == Character.UnicodeBlock.HANGUL_SYLLABLES;
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
        return authorized(req, method, path, contentType, body, accept, null);
    }

    private HttpResponse authorized(Request req, String method, String path, String contentType, byte[] body, String accept, Map<String, String> extraHeaders) throws Exception {
        for (int attempt = 0; ; attempt++) {
            String t = ensureToken(req);
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Authorization", "Bearer " + t);
            headers.put("Accept", accept);
            if (contentType != null) headers.put("Content-Type", contentType);
            if (extraHeaders != null) headers.putAll(extraHeaders);
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
        if (body != null) {
            // CloudFront OAC -> Lambda Function URL requires the payload hash (docs/api-server.md §3.1 item 1).
            headers.put("x-amz-content-sha256", sha256Hex(body));
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
                .put("locale", registrationLocale());
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

    /**
     * "language[-Script][-REGION]" only, e.g. "zh-Hans-CN" (same as iOS). Unicode extensions such as
     * "-u-mu-celsius" (Android 14 regional preferences) are dropped; the server rejects them (#48).
     */
    static String registrationLocale() {
        Locale l = Locale.getDefault();
        try {
            return new Locale.Builder().setLanguage(l.getLanguage()).setScript(l.getScript()).setRegion(l.getCountry()).build().toLanguageTag();
        } catch (Exception e) {
            String lang = l.getLanguage();
            return lang == null || lang.isEmpty() ? "und" : lang;
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

    static String sha256Hex(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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

    /** Mono 16-bit 16 kHz PCM WAV with a 440 Hz tone; used by the debug self-test. */
    public static byte[] testToneWav(int seconds) {
        int rate = 16000, samples = rate * seconds;
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
