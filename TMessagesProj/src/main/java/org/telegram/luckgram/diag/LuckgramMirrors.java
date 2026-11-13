package org.telegram.luckgram.diag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * specs#160: luckgram.cc, wiki, dl and web (the web version) are blocked by name on some networks while cat158.com is not,
 * so each has a backup host with the same content (BuildConfig.LUCKGRAM_SITE_MIRRORS, next to the
 * API entries of specs#81):
 * <ul>
 *   <li>package downloads try the other host when the first fails at the network level
 *       ({@link #downloadUrls});</li>
 *   <li>website / help center links open on the backup host while the API is on a backup entry
 *       ({@link #forSite}): that network blocks luckgram.cc.</li>
 * </ul>
 * Pure Java, unit-tested in {@code LuckgramMirrorsTest}.
 */
public final class LuckgramMirrors {

    /** host (lower case) -> backup host. */
    private final Map<String, String> hosts;

    public LuckgramMirrors(Map<String, String> hosts) {
        this.hosts = Collections.unmodifiableMap(new LinkedHashMap<>(hosts));
    }

    /**
     * {@code "luckgram.cc=lg-www.cat158.com,dl.luckgram.cc=lg-dl.cat158.com"}: pairs separated by
     * commas / whitespace; entries that are not two host names are skipped.
     */
    public static LuckgramMirrors parse(String list) {
        Map<String, String> m = new LinkedHashMap<>();
        if (list != null) {
            for (String part : list.split("[,\\s]+")) {
                int eq = part.indexOf('=');
                if (eq <= 0) continue;
                String from = part.substring(0, eq).trim().toLowerCase(Locale.US);
                String to = part.substring(eq + 1).trim().toLowerCase(Locale.US);
                if (isHost(from) && isHost(to) && !from.equals(to)) {
                    m.put(from, to);
                }
            }
        }
        return new LuckgramMirrors(m);
    }

    static boolean isHost(String h) {
        return h != null && h.matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+");
    }

    public Map<String, String> hosts() {
        return hosts;
    }

    /**
     * {@code url} on its backup host (path, query and fragment kept), or null when {@code url} is
     * not an https URL on one of the mirrored hosts. URLs with user info or a port are left alone.
     */
    public String mirror(String url) {
        if (url == null || !url.regionMatches(true, 0, "https://", 0, 8)) {
            return null;
        }
        int start = 8;
        int end = url.length();
        for (int i = start; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
            if (c == '@' || c == ':') {
                return null;
            }
        }
        String host = url.substring(start, end).toLowerCase(Locale.US);
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        String to = hosts.get(host);
        return to == null ? null : "https://" + to + url.substring(end);
    }

    /** Website / help center link to open: the backup host while the API uses a backup entry. */
    public String forSite(String url, boolean apiOnBackup) {
        if (!apiOnBackup) {
            return url;
        }
        String m = mirror(url);
        return m != null ? m : url;
    }

    /**
     * Hosts to try for a package download, in order: the server's URL, then its backup host (when
     * there is one). While the API is on a backup entry the backup host goes first: the network
     * that blocks the API host blocks dl.luckgram.cc too. Only a network-level failure (no HTTP
     * response, {@link LuckgramEndpoints#isNetworkFailure}) moves on to the next one.
     */
    public List<String> downloadUrls(String url, boolean apiOnBackup) {
        ArrayList<String> out = new ArrayList<>(2);
        String m = mirror(url);
        if (m != null && apiOnBackup) {
            out.add(m);
        }
        out.add(url);
        if (m != null && !apiOnBackup) {
            out.add(m);
        }
        return out;
    }
}
