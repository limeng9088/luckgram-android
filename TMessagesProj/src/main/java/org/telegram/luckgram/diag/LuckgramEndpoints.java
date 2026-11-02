package org.telegram.luckgram.diag;

import java.io.EOFException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.SSLException;

/**
 * specs#81: the Luckgram API has several entries (same backend). Requests use the entry that worked
 * last; when an entry fails at the network level (DNS, connect, TLS, reset, connect timeout) the
 * same request is retried on the next one. An HTTP response of any status never switches.
 * While a backup is in use the first entry is probed every {@link #PROBE_INTERVAL_MS} (and once
 * per cold start) and taken back when it answers.
 *
 * <p>Pure Java (no Android classes), unit-tested in {@code LuckgramEndpointsTest}.
 */
public final class LuckgramEndpoints {

    public static final long PROBE_INTERVAL_MS = 6L * 60 * 60 * 1000;
    public static final String HEALTH_PATH = "/v1/health";

    private final List<String> bases;
    private int current;
    /** 0 = not probed since the process started, so the first check after a cold start probes. */
    private long lastProbeMs;

    /**
     * @param bases      entries in order of preference; see {@link #parse}
     * @param remembered the entry that worked last (persisted), or null; ignored if not in the list
     */
    public LuckgramEndpoints(List<String> bases, String remembered) {
        if (bases == null || bases.isEmpty()) {
            throw new IllegalArgumentException("no API entry");
        }
        this.bases = Collections.unmodifiableList(new ArrayList<>(bases));
        int i = indexOf(normalize(remembered));
        current = Math.max(i, 0);
    }

    /**
     * Splits a comma / whitespace separated list: https only, trailing slashes removed, duplicates
     * dropped, order kept. Returns an empty list when nothing usable is left.
     */
    public static List<String> parse(String list) {
        ArrayList<String> out = new ArrayList<>();
        if (list == null) return out;
        for (String part : list.split("[,\\s]+")) {
            String b = normalize(part);
            if (b != null && !out.contains(b)) {
                out.add(b);
            }
        }
        return out;
    }

    static String normalize(String base) {
        if (base == null) return null;
        String b = base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (!b.toLowerCase(Locale.US).startsWith("https://") || b.length() <= "https://".length()) {
            return null;
        }
        return b;
    }

    public List<String> all() {
        return bases;
    }

    public String primary() {
        return bases.get(0);
    }

    public synchronized String current() {
        return bases.get(current);
    }

    public synchronized boolean usingBackup() {
        return current != 0;
    }

    /** The entries to try for one request: the current one first, then the others in list order. */
    public synchronized List<String> attemptOrder() {
        ArrayList<String> out = new ArrayList<>(bases.size());
        out.add(bases.get(current));
        for (int i = 0; i < bases.size(); i++) {
            if (i != current) out.add(bases.get(i));
        }
        return out;
    }

    /** {@code base} answered: use it from now on. Returns true if the current entry changed. */
    public synchronized boolean markWorking(String base) {
        int i = indexOf(base);
        if (i < 0 || i == current) return false;
        current = i;
        return true;
    }

    /** Whether the first entry should be probed now (only while a backup is in use). */
    public synchronized boolean shouldProbe(long nowMs) {
        return current != 0 && (lastProbeMs == 0 || nowMs - lastProbeMs >= PROBE_INTERVAL_MS || nowMs < lastProbeMs);
    }

    public synchronized void markProbed(long nowMs) {
        lastProbeMs = nowMs;
    }

    /** The entry {@code url} starts with (scheme and host compared without case), or null. */
    public String baseOf(String url) {
        if (url == null) return null;
        for (String b : bases) {
            if (url.length() < b.length() || !url.regionMatches(true, 0, b, 0, b.length())) continue;
            if (url.length() == b.length()) return b;
            char c = url.charAt(b.length());
            if (c == '/' || c == '?' || c == '#') return b;
        }
        return null;
    }

    /** Whether {@code host} is the host of one of the entries. */
    public boolean isEntryHost(String host) {
        if (host == null) return false;
        for (String b : bases) {
            if (host.equalsIgnoreCase(hostOf(b))) return true;
        }
        return false;
    }

    /**
     * A URL returned by the server (images, avatars, videos) that points at another entry is moved
     * to the current one; other URLs are returned unchanged.
     */
    public String rewrite(String url) {
        String b = baseOf(url);
        if (b == null) return url;
        String cur = current();
        return b.equals(cur) ? url : cur + url.substring(b.length());
    }

    public static String hostOf(String base) {
        if (base == null) return null;
        int s = base.indexOf("://");
        String rest = s >= 0 ? base.substring(s + 3) : base;
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == ':' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        return rest.substring(0, end);
    }

    /**
     * Whether {@code t} means the entry could not be reached (switch to the next one): DNS failure,
     * connection refused / reset / unreachable, TLS failure, connect timeout. Anything else
     * (cancelled, a bad response) is not.
     */
    public static boolean isNetworkFailure(Throwable t) {
        for (int depth = 0; t != null && depth < 8; depth++, t = t.getCause()) {
            if (t instanceof UnknownHostException
                    || t instanceof ConnectException
                    || t instanceof NoRouteToHostException
                    || t instanceof SocketTimeoutException
                    || t instanceof SSLException
                    || t instanceof SocketException
                    || t instanceof EOFException) {
                return true;
            }
        }
        return false;
    }

    private int indexOf(String base) {
        if (base == null) return -1;
        for (int i = 0; i < bases.size(); i++) {
            if (bases.get(i).equalsIgnoreCase(base)) return i;
        }
        return -1;
    }
}
