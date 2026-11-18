package org.telegram.luckgram.crash;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * specs#205: the pure part of crash reporting (no Android types, unit tested): turning a Throwable,
 * an ANR trace or a native tombstone into report text without personal data, the report JSON, and
 * which local files to keep.
 */
public final class CrashLogic {

    public static final String KIND_JAVA = "java";
    public static final String KIND_NATIVE = "native";
    public static final String KIND_ANR = "anr";

    /** Reports kept on the device; older ones are deleted. */
    public static final int KEEP_FILES = 10;
    /** Exit records older than this are not reported the first time the scan runs. */
    public static final long FIRST_SCAN_WINDOW_MS = 3L * 24 * 60 * 60 * 1000;

    private static final int MAX_CAUSES = 8;
    private static final int MAX_FRAMES = 64;
    private static final int MAX_MESSAGE = 300;
    private static final int MAX_NATIVE_FRAMES = 64;

    private CrashLogic() {
    }

    // ---------------------------------------------------------------- scrubbing

    private static final Pattern URL = Pattern.compile("(?i)\\b[a-z][a-z0-9+.-]*://[^\\s\"'<>)]+");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("\\+\\d[\\d -]{6,}\\d");
    private static final Pattern DIGITS = Pattern.compile("\\b\\d{7,}\\b");

    /**
     * Removes what could identify a person from an exception message: URLs, e-mail addresses,
     * phone numbers and runs of 7+ digits (user, chat and phone numbers). Same rules as the server.
     */
    public static String scrub(String s) {
        if (s == null) {
            return null;
        }
        s = URL.matcher(s).replaceAll("<url>");
        s = EMAIL.matcher(s).replaceAll("<email>");
        s = PHONE.matcher(s).replaceAll("<phone>");
        return DIGITS.matcher(s).replaceAll("<n>");
    }

    static String message(String s) {
        if (s == null) {
            return null;
        }
        s = scrub(s.replace('\r', ' ').replace('\n', ' '));
        return s.length() > MAX_MESSAGE ? s.substring(0, MAX_MESSAGE) + "…" : s;
    }

    // ---------------------------------------------------------------- Java

    /**
     * The stack of {@code t} as {@code printStackTrace} writes it, with the messages scrubbed and
     * shortened, at most {@link #MAX_FRAMES} frames per throwable and {@link #MAX_CAUSES} causes.
     */
    public static String javaStack(Throwable t, int maxChars) {
        StringBuilder sb = new StringBuilder();
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        for (int depth = 0; t != null && depth < MAX_CAUSES && !seen.containsKey(t); depth++, t = t.getCause()) {
            seen.put(t, true);
            if (depth > 0) {
                sb.append("Caused by: ");
            }
            sb.append(t.getClass().getName());
            String m = message(t.getMessage());
            if (m != null) {
                sb.append(": ").append(m);
            }
            sb.append('\n');
            StackTraceElement[] st = t.getStackTrace();
            int n = Math.min(st.length, MAX_FRAMES);
            for (int i = 0; i < n; i++) {
                sb.append("\tat ").append(st[i]).append('\n');
            }
            if (st.length > n) {
                sb.append("\t... ").append(st.length - n).append(" more\n");
            }
            if (sb.length() >= maxChars) {
                break;
            }
        }
        return cut(sb.toString(), maxChars);
    }

    // ---------------------------------------------------------------- ANR

    /**
     * An ANR report from the system trace ({@code ApplicationExitInfo.getTraceInputStream()}):
     * the reason and the main thread's stack. Other threads are left out (names and stacks of
     * every thread are long and rarely needed).
     */
    public static String anrText(String description, String trace, int maxChars) {
        StringBuilder sb = new StringBuilder();
        sb.append("ANR: ").append(description != null ? message(description) : "unknown").append('\n');
        String main = trace != null ? mainThread(trace) : null;
        if (main != null) {
            sb.append(main);
        } else {
            sb.append("(no main thread stack)\n");
        }
        return cut(sb.toString(), maxChars);
    }

    /** The {@code "main"} thread block of a dalvik/ART trace, without its header line. */
    static String mainThread(String trace) {
        String[] lines = trace.replace("\r", "").split("\n");
        StringBuilder sb = null;
        for (String line : lines) {
            if (sb == null) {
                if (line.startsWith("\"main\"")) {
                    sb = new StringBuilder();
                    sb.append("thread main").append(state(line)).append('\n');
                }
                continue;
            }
            if (line.trim().isEmpty()) {
                break;
            }
            String l = line.trim();
            // stack lines ("at ...", "native: #00 pc ...", "- waiting on ...", "- locked ...")
            if (l.startsWith("at ") || l.startsWith("native:") || l.startsWith("- ")) {
                sb.append("  ").append(scrub(l)).append('\n');
            }
        }
        return sb != null && sb.length() > 0 ? sb.toString() : null;
    }

    private static String state(String header) {
        // "main" prio=5 tid=1 Blocked
        String[] p = header.trim().split("\\s+");
        String last = p[p.length - 1];
        return last.startsWith("tid=") || last.startsWith("prio=") || last.startsWith("\"") ? "" : " (" + last + ")";
    }

    // ---------------------------------------------------------------- native (tombstone)

    /**
     * A native crash report from the Android 12+ tombstone protobuf
     * ({@code ApplicationExitInfo.getTraceInputStream()} for REASON_CRASH_NATIVE): signal, abort
     * message and the crashing thread's backtrace. Registers, memory dumps, logcat and open files
     * are not read, so no memory contents leave the device. Returns null if nothing could be read.
     */
    public static String tombstoneText(String description, byte[] proto, int maxChars) {
        Tombstone t = proto != null ? parseTombstone(proto) : null;
        StringBuilder sb = new StringBuilder();
        if (t != null && t.signalName != null) {
            sb.append("signal ").append(t.signalNumber).append(" (").append(t.signalName).append(")");
            if (t.codeName != null) {
                sb.append(", code ").append(t.code).append(" (").append(t.codeName).append(")");
            }
            if (t.hasFaultAddress) {
                sb.append(", fault addr 0x").append(Long.toHexString(t.faultAddress));
            }
            sb.append('\n');
        } else {
            sb.append("native: ").append(description != null ? message(description) : "crash").append('\n');
        }
        if (t != null && t.abortMessage != null && !t.abortMessage.isEmpty()) {
            sb.append("abort: ").append(message(t.abortMessage)).append('\n');
        }
        Thread crashing = t != null ? t.threads.get(t.tid) : null;
        if (crashing == null && t != null && t.threads.size() == 1) {
            crashing = t.threads.values().iterator().next();
        }
        if (crashing != null) {
            if (crashing.name != null) {
                sb.append("thread ").append(scrub(crashing.name)).append('\n');
            }
            int i = 0;
            for (Frame f : crashing.frames) {
                if (i >= MAX_NATIVE_FRAMES) {
                    sb.append("    ... ").append(crashing.frames.size() - i).append(" more\n");
                    break;
                }
                sb.append(String.format(Locale.US, "#%02d pc %016x  %s", i, f.relPc, f.fileName != null ? basename(f.fileName) : "???"));
                if (f.functionName != null && !f.functionName.isEmpty()) {
                    sb.append(" (").append(f.functionName).append('+').append(f.functionOffset).append(')');
                }
                if (f.buildId != null && !f.buildId.isEmpty()) {
                    sb.append(" (BuildId: ").append(f.buildId).append(')');
                }
                sb.append('\n');
                i++;
            }
        }
        if (t == null && description == null) {
            return null;
        }
        return cut(sb.toString(), maxChars);
    }

    static String basename(String path) {
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }

    /** The fields of a tombstone that a report uses. */
    static final class Tombstone {
        int tid;
        int signalNumber;
        String signalName;
        int code;
        String codeName;
        boolean hasFaultAddress;
        long faultAddress;
        String abortMessage;
        final Map<Integer, Thread> threads = new java.util.HashMap<>();
    }

    static final class Thread {
        String name;
        final List<Frame> frames = new ArrayList<>();
    }

    static final class Frame {
        long relPc;
        String functionName;
        long functionOffset;
        String fileName;
        String buildId;
    }

    /** Reads the tombstone (AOSP system/core/debuggerd/proto/tombstone.proto); null if malformed. */
    static Tombstone parseTombstone(byte[] b) {
        try {
            Tombstone t = new Tombstone();
            Proto p = new Proto(b, 0, b.length);
            while (p.more()) {
                int tag = p.tag();
                switch (tag >>> 3) {
                    case 6: t.tid = (int) p.varint(tag); break;
                    case 10: signal(p.message(tag), t); break;
                    case 14: t.abortMessage = p.string(tag); break;
                    case 16: threadEntry(p.message(tag), t); break;
                    default: p.skip(tag);
                }
            }
            return t;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void signal(Proto p, Tombstone t) {
        while (p.more()) {
            int tag = p.tag();
            switch (tag >>> 3) {
                case 1: t.signalNumber = (int) p.varint(tag); break;
                case 2: t.signalName = p.string(tag); break;
                case 3: t.code = (int) p.varint(tag); break;
                case 4: t.codeName = p.string(tag); break;
                case 8: t.hasFaultAddress = p.varint(tag) != 0; break;
                case 9: t.faultAddress = p.varint(tag); break;
                default: p.skip(tag);
            }
        }
    }

    /** map&lt;uint32, Thread&gt; entry: key = 1, value = 2. */
    private static void threadEntry(Proto p, Tombstone t) {
        int key = 0;
        Thread th = null;
        while (p.more()) {
            int tag = p.tag();
            switch (tag >>> 3) {
                case 1: key = (int) p.varint(tag); break;
                case 2: th = thread(p.message(tag)); break;
                default: p.skip(tag);
            }
        }
        if (th != null) {
            t.threads.put(key, th);
        }
    }

    private static Thread thread(Proto p) {
        Thread th = new Thread();
        while (p.more()) {
            int tag = p.tag();
            switch (tag >>> 3) {
                case 2: th.name = p.string(tag); break;
                case 4: th.frames.add(frame(p.message(tag))); break;
                default: p.skip(tag); // registers, memory dumps, notes: not read
            }
        }
        return th;
    }

    private static Frame frame(Proto p) {
        Frame f = new Frame();
        while (p.more()) {
            int tag = p.tag();
            switch (tag >>> 3) {
                case 1: f.relPc = p.varint(tag); break;
                case 4: f.functionName = p.string(tag); break;
                case 5: f.functionOffset = p.varint(tag); break;
                case 6: f.fileName = p.string(tag); break;
                case 8: f.buildId = p.string(tag); break;
                default: p.skip(tag);
            }
        }
        return f;
    }

    /** Minimal protobuf reader over [pos, end); throws IllegalStateException on bad input. */
    static final class Proto {
        private final byte[] b;
        private int pos;
        private final int end;

        Proto(byte[] b, int pos, int end) {
            this.b = b;
            this.pos = pos;
            this.end = end;
        }

        boolean more() {
            return pos < end;
        }

        int tag() {
            long v = raw();
            if (v <= 0 || v > Integer.MAX_VALUE) {
                throw new IllegalStateException("tag");
            }
            return (int) v;
        }

        private long raw() {
            long v = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (pos >= end) {
                    throw new IllegalStateException("eof");
                }
                int c = b[pos++] & 0xff;
                v |= (long) (c & 0x7f) << shift;
                if ((c & 0x80) == 0) {
                    return v;
                }
            }
            throw new IllegalStateException("varint");
        }

        long varint(int tag) {
            if ((tag & 7) != 0) {
                skip(tag);
                return 0;
            }
            return raw();
        }

        private int length() {
            long n = raw();
            if (n < 0 || n > end - pos) {
                throw new IllegalStateException("length");
            }
            return (int) n;
        }

        Proto message(int tag) {
            if ((tag & 7) != 2) {
                skip(tag);
                return new Proto(b, 0, 0);
            }
            int n = length();
            Proto m = new Proto(b, pos, pos + n);
            pos += n;
            return m;
        }

        String string(int tag) {
            if ((tag & 7) != 2) {
                skip(tag);
                return null;
            }
            int n = length();
            String s = new String(b, pos, Math.min(n, 4096), StandardCharsets.UTF_8);
            pos += n;
            return s;
        }

        void skip(int tag) {
            switch (tag & 7) {
                case 0: raw(); break;
                case 1: advance(8); break;
                case 2: advance(length()); break;
                case 5: advance(4); break;
                default: throw new IllegalStateException("wire type");
            }
        }

        private void advance(int n) {
            if (n > end - pos) {
                throw new IllegalStateException("eof");
            }
            pos += n;
        }
    }

    // ---------------------------------------------------------------- report

    /** Device and app facts of a report; plain strings, filled by the Android side. */
    public static final class Facts {
        public String appVersion;
        public int build;
        public String abi;
        public Map<String, String> device;
        public Map<String, String> os;
        public Map<String, String> context;
    }

    /** The body of {@code POST /v1/crash}. */
    public static JSONObject report(String kind, long occurredAtMs, Facts f, String thread, String stack) throws JSONException {
        JSONObject j = new JSONObject();
        j.put("kind", kind);
        j.put("occurred_at", rfc3339(occurredAtMs));
        j.put("app_version", f.appVersion);
        j.put("build", f.build);
        if (f.abi != null) j.put("abi", f.abi);
        if (f.device != null) j.put("device", new JSONObject(f.device));
        if (f.os != null) j.put("os", new JSONObject(f.os));
        if (f.context != null) j.put("context", new JSONObject(f.context));
        if (thread != null) j.put("thread", scrub(thread));
        j.put("stack", stack);
        return j;
    }

    public static String rfc3339(long ms) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(new Date(ms));
    }

    /** {@code s} cut to at most {@code maxBytes} UTF-8 bytes (the server's size limit). */
    public static String cutUtf8(String s, int maxBytes) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length <= maxBytes) {
            return s;
        }
        int n = maxBytes;
        while (n > 0 && (b[n] & 0xc0) == 0x80) {
            n--; // do not split a character
        }
        return new String(b, 0, n, StandardCharsets.UTF_8);
    }

    private static String cut(String s, int maxChars) {
        return s.length() > maxChars ? s.substring(0, maxChars) : s;
    }

    // ---------------------------------------------------------------- files

    /** Name of a pending report file: {@code <ms>-<kind>.json}, so names sort by time. */
    public static String fileName(long ms, String kind) {
        return String.format(Locale.US, "%013d-%s.json", ms, kind);
    }

    public static boolean isReportFile(String name) {
        return name != null && name.matches("\\d{13}-(java|native|anr)\\.json");
    }

    /** The report files to delete so that at most {@code keep} newest remain. */
    public static List<String> toDelete(List<String> names, int keep) {
        ArrayList<String> sorted = new ArrayList<>();
        for (String n : names) {
            if (isReportFile(n)) sorted.add(n);
        }
        Collections.sort(sorted);
        return new ArrayList<>(sorted.subList(0, Math.max(0, sorted.size() - keep)));
    }

    /** Whether to report a system exit record: newer than the last scan, or recent on the first scan. */
    public static boolean shouldReportExit(long timestamp, long lastSeen, long now) {
        if (lastSeen <= 0) {
            return now - timestamp <= FIRST_SCAN_WINDOW_MS;
        }
        return timestamp > lastSeen;
    }

    /** What to do with a pending report after an upload attempt. */
    public static boolean deleteAfterUpload(int status) {
        // 2xx: stored (or dropped by the daily limit); 400 / 413: never acceptable, so stop retrying.
        return (status >= 200 && status < 300) || status == 400 || status == 413;
    }
}
