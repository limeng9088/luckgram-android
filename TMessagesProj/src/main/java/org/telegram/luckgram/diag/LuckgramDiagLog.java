package org.telegram.luckgram.diag;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * specs#72: the line format of the local diagnostic log, and how old lines are dropped. One line per
 * Luckgram API request or failed Telegram request. Only metadata is written: no message text,
 * prompts, tokens, phone numbers, query strings or full URLs. Pure so it is unit tested.
 */
public final class LuckgramDiagLog {

    public static final long MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;
    public static final int MAX_BYTES = 2 * 1024 * 1024;

    private static final int MAX_FIELD = 64;

    private LuckgramDiagLog() {
    }

    /** One request. Unknown values stay null (or 0 / -1) and are left out of the line. */
    public static final class Entry {
        public long timeMs;
        /** Feature or AI task, e.g. {@code summary}, {@code chat}, {@code media}. */
        public String feature;
        public String method;
        /** Path or URL; reduced by {@link #route} before it is written. */
        public String target;
        /** HTTP status; 0 if no response arrived. */
        public int status;
        /** Exception class name when the request failed without a response. */
        public String exception;
        /** Telegram error code and text, e.g. 400 / CHANNEL_PRIVATE. */
        public int tgCode;
        public String tgError;
        public long durationMs = -1;
        /** {@link LuckgramProxyRoute} label. */
        public String proxy;
        /** The server's {@code X-Request-Id} header. */
        public String requestId;
        /** specs#81: host of the API entry used, and whether another entry failed first. */
        public String endpoint;
        public boolean switched;
    }

    /**
     * Reduces a path or URL to what identifies the endpoint: the query string and fragment (presigned
     * signatures, ids) are dropped; for an absolute URL only the host and path are kept.
     */
    public static String route(String target, String apiHost) {
        if (target == null || target.isEmpty()) {
            return "-";
        }
        String s = target;
        int cut = indexOfAny(s, '?', '#');
        if (cut >= 0) {
            s = s.substring(0, cut);
        }
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            String rest = s.substring(scheme + 3);
            int at = rest.indexOf('@');
            int slash = rest.indexOf('/');
            if (at >= 0 && (slash < 0 || at < slash)) {
                rest = rest.substring(at + 1); // never keep user:password@
                slash = rest.indexOf('/');
            }
            String host = slash >= 0 ? rest.substring(0, slash) : rest;
            String path = slash >= 0 ? rest.substring(slash) : "/";
            s = apiHost != null && host.equalsIgnoreCase(apiHost) ? path : host + path;
        }
        return clean(s, 160);
    }

    /** Formats {@code e} as one line: {@code <epoch ms> <UTC time> key=value ...}. */
    public static String format(Entry e, String apiHost) {
        StringBuilder b = new StringBuilder(160);
        b.append(e.timeMs).append(' ').append(utc(e.timeMs));
        field(b, "feature", e.feature);
        String r = route(e.target, apiHost);
        b.append(" route=").append(e.method != null ? clean(e.method, 8) + " " : "").append(r);
        if (e.status > 0) b.append(" status=").append(e.status);
        field(b, "exception", e.exception);
        if (e.tgCode != 0 || e.tgError != null) {
            b.append(" tg=").append(e.tgCode);
            if (e.tgError != null) b.append(':').append(clean(e.tgError, MAX_FIELD));
        }
        if (e.durationMs >= 0) b.append(" ms=").append(e.durationMs);
        field(b, "proxy", e.proxy);
        field(b, "rid", e.requestId);
        field(b, "endpoint", e.endpoint);
        if (e.switched) b.append(" switched=1");
        return b.toString();
    }

    /**
     * Keeps the lines that are at most {@code maxAgeMs} old and, newest first, fit in
     * {@code maxBytes} (UTF-8, with newlines). Lines without a leading time are dropped. Order kept.
     */
    public static List<String> trim(List<String> lines, long nowMs, long maxAgeMs, int maxBytes) {
        ArrayList<String> kept = new ArrayList<>();
        long bytes = 0;
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i);
            long t = timeOf(line);
            if (t < 0 || nowMs - t > maxAgeMs) {
                continue;
            }
            long size = utf8Length(line) + 1;
            if (bytes + size > maxBytes) {
                break;
            }
            bytes += size;
            kept.add(0, line);
        }
        return kept;
    }

    /** The epoch milliseconds a line starts with, or -1. */
    public static long timeOf(String line) {
        if (line == null) return -1;
        int sp = line.indexOf(' ');
        if (sp <= 0) return -1;
        try {
            return Long.parseLong(line.substring(0, sp));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void field(StringBuilder b, String key, String value) {
        if (value != null && !value.isEmpty()) {
            b.append(' ').append(key).append('=').append(clean(value, MAX_FIELD));
        }
    }

    /** Only characters that can't carry free text across fields; capped. */
    static String clean(String s, int max) {
        StringBuilder b = new StringBuilder(Math.min(s.length(), max));
        for (int i = 0; i < s.length() && b.length() < max; i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '-' || c == '_' || c == '.' || c == '/' || c == ':' || c == '$';
            b.append(ok ? c : '_');
        }
        return b.toString();
    }

    private static int indexOfAny(String s, char a, char b) {
        int i = s.indexOf(a), j = s.indexOf(b);
        if (i < 0) return j;
        if (j < 0) return i;
        return Math.min(i, j);
    }

    private static long utf8Length(String s) {
        long n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x80) n += 1;
            else if (c < 0x800) n += 2;
            else if (Character.isHighSurrogate(c)) {
                n += 4;
                i++;
            } else n += 3;
        }
        return n;
    }

    private static String utc(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }
}
