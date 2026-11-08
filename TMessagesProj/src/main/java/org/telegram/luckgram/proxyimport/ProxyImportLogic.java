package org.telegram.luckgram.proxyimport;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * specs#118: what a scanned or typed text means for the proxy list, and the proxies in a
 * mihomo / Clash subscription. SOCKS5 and MTProto go into Telegram's list as they are; VLESS,
 * VMess, Trojan and Shadowsocks (specs#119) are {@link #TYPE_XRAY} entries run by the built-in
 * Xray core. Everything else in a subscription is counted as skipped.
 *
 * <p>Pure Java (java.* only), unit-tested in {@code ProxyImportLogicTest}. Same rules as iOS
 * {@code LuckgramProxyImportLogic}.
 */
public final class ProxyImportLogic {

    public static final int KIND_UNKNOWN = 0;
    public static final int KIND_PROXY = 1;
    public static final int KIND_SUBSCRIPTION = 2;

    public static final String TYPE_SOCKS5 = "socks5";
    public static final String TYPE_MTPROTO = "mtproto";
    /** specs#119: a VLESS / VMess / Trojan / SS node; {@link Entry#link} is its canonical share link. */
    public static final String TYPE_XRAY = "xray";

    /** Largest subscription accepted. */
    public static final int MAX_BYTES = 2 * 1024 * 1024;

    private ProxyImportLogic() {
    }

    /** One proxy the app can use. */
    public static final class Entry {
        public final String type;
        public final String host;
        public final int port;
        public final String user;
        public final String password;
        public final String secret;
        /** {@link #TYPE_XRAY} only: canonical share link. */
        public final String link;
        /** {@link #TYPE_XRAY} only: protocol and name from the link. */
        public final String protocol;
        public final String name;

        public Entry(String type, String host, int port, String user, String password, String secret) {
            this(type, host, port, user, password, secret, null);
        }

        private Entry(String type, String host, int port, String user, String password, String secret, org.telegram.luckgram.xray.XrayLogic.Node node) {
            this.type = type;
            this.host = host == null ? "" : host.trim();
            this.port = port;
            this.user = user == null ? "" : user;
            this.password = password == null ? "" : password;
            this.secret = secret == null ? "" : secret;
            this.link = node == null ? "" : node.link;
            this.protocol = node == null ? "" : node.protocol;
            this.name = node == null ? "" : node.name;
        }

        public static Entry xray(org.telegram.luckgram.xray.XrayLogic.Node node) {
            return new Entry(TYPE_XRAY, node.host, node.port, null, null, null, node);
        }

        /** Identity used to recognise the same proxy across refreshes. */
        public String key() {
            if (TYPE_XRAY.equals(type)) {
                int hash = link.indexOf('#');
                return "xray|" + (hash >= 0 ? link.substring(0, hash) : link);
            }
            return type + "|" + host.toLowerCase(Locale.ROOT) + "|" + port + "|" + user + "|" + password + "|" + secret;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Entry && ((Entry) o).key().equals(key());
        }

        @Override
        public int hashCode() {
            return key().hashCode();
        }

        @Override
        public String toString() {
            return key();
        }
    }

    /** Proxies found in a subscription, plus how many entries could not be used. */
    public static final class Result {
        public final List<Entry> proxies;
        public final int skipped;

        Result(List<Entry> proxies, int skipped) {
            this.proxies = proxies;
            this.skipped = skipped;
        }
    }

    /** The proxies to add and the keys to remove when a subscription is refreshed. */
    public static final class Diff {
        public final List<Entry> add = new ArrayList<>();
        public final List<String> remove = new ArrayList<>();
    }

    // ------------------------------------------------------------------ input kind

    public static int kind(String raw) {
        if (raw == null) return KIND_UNKNOWN;
        String text = raw.trim();
        if (text.isEmpty() || text.contains("\n") || text.contains(" ")) return KIND_UNKNOWN;
        if (parseLink(text) != null) return KIND_PROXY;
        String lower = text.toLowerCase(Locale.ROOT);
        if ((lower.startsWith("https://") || lower.startsWith("http://")) && !isTelegramLink(lower) && hostOf(text) != null) {
            return KIND_SUBSCRIPTION;
        }
        return KIND_UNKNOWN;
    }

    private static boolean isTelegramLink(String lower) {
        String host = hostOf(lower);
        return host != null && (host.equals("t.me") || host.equals("telegram.me") || host.equals("telegram.dog") || host.endsWith(".t.me"));
    }

    /** Host of an http(s) URL, or null. */
    public static String hostOf(String url) {
        int i = url.indexOf("://");
        if (i < 0) return null;
        String rest = url.substring(i + 3);
        int end = rest.length();
        for (char c : new char[]{'/', '?', '#'}) {
            int j = rest.indexOf(c);
            if (j >= 0 && j < end) end = j;
        }
        String auth = rest.substring(0, end);
        int at = auth.lastIndexOf('@');
        if (at >= 0) auth = auth.substring(at + 1);
        if (auth.startsWith("[")) {
            int close = auth.indexOf(']');
            return close > 1 ? auth.substring(1, close) : null;
        }
        int colon = auth.lastIndexOf(':');
        if (colon >= 0) auth = auth.substring(0, colon);
        return auth.isEmpty() ? null : auth.toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ single links

    /**
     * A proxy link: {@code tg://socks|proxy?...}, {@code https://t.me/socks|proxy?...} (also
     * telegram.me / telegram.dog), {@code socks5://[user:pass@]host:port} ({@code socks://},
     * {@code socks5h://}), or a {@code vless:// vmess:// trojan:// ss://} share link (specs#119).
     * Null when it is none of these or is incomplete.
     */
    public static Entry parseLink(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        if (org.telegram.luckgram.xray.XrayLogic.looksLikeLink(text)) {
            org.telegram.luckgram.xray.XrayLogic.Node node = org.telegram.luckgram.xray.XrayLogic.parse(text);
            return node == null ? null : Entry.xray(node);
        }
        for (String p : new String[]{"socks5h://", "socks5://", "socks://"}) {
            if (lower.startsWith(p)) return parseSocksUrl(text.substring(p.length()));
        }
        String path = null;
        String query = null;
        if (lower.startsWith("tg:")) {
            String rest = text.substring(3);
            if (rest.startsWith("//")) rest = rest.substring(2);
            int q = rest.indexOf('?');
            path = (q >= 0 ? rest.substring(0, q) : rest).toLowerCase(Locale.ROOT);
            query = q >= 0 ? rest.substring(q + 1) : "";
        } else if (lower.startsWith("https://") || lower.startsWith("http://") || lower.startsWith("t.me/") || lower.startsWith("telegram.me/")) {
            String url = lower.startsWith("http") ? text : "https://" + text;
            if (!isTelegramLink(url.toLowerCase(Locale.ROOT))) return null;
            String rest = url.substring(url.indexOf("://") + 3);
            int slash = rest.indexOf('/');
            if (slash < 0) return null;
            rest = rest.substring(slash + 1);
            int q = rest.indexOf('?');
            path = (q >= 0 ? rest.substring(0, q) : rest).toLowerCase(Locale.ROOT);
            query = q >= 0 ? rest.substring(q + 1) : "";
        }
        if (path == null) return null;
        if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (!path.equals("socks") && !path.equals("proxy")) return null;
        Map<String, String> params = queryParams(query);
        String server = params.get("server");
        int port = parsePort(params.get("port"));
        if (server == null || server.isEmpty() || port <= 0) return null;
        if (path.equals("socks")) {
            return new Entry(TYPE_SOCKS5, server, port, params.get("user"), params.get("pass"), null);
        }
        String secret = params.get("secret");
        if (secret == null || secret.isEmpty()) return null;
        return new Entry(TYPE_MTPROTO, server, port, null, null, secret);
    }

    private static Entry parseSocksUrl(String rest) {
        int end = rest.length();
        for (char c : new char[]{'/', '?', '#'}) {
            int j = rest.indexOf(c);
            if (j >= 0 && j < end) end = j;
        }
        String auth = rest.substring(0, end);
        String user = null;
        String pass = null;
        int at = auth.lastIndexOf('@');
        if (at >= 0) {
            String cred = auth.substring(0, at);
            auth = auth.substring(at + 1);
            int c = cred.indexOf(':');
            user = decode(c >= 0 ? cred.substring(0, c) : cred);
            pass = c >= 0 ? decode(cred.substring(c + 1)) : "";
        }
        String host;
        String portStr;
        if (auth.startsWith("[")) {
            int close = auth.indexOf(']');
            if (close < 0) return null;
            host = auth.substring(1, close);
            portStr = auth.length() > close + 2 && auth.charAt(close + 1) == ':' ? auth.substring(close + 2) : null;
        } else {
            int colon = auth.lastIndexOf(':');
            if (colon < 0) return null;
            host = auth.substring(0, colon);
            portStr = auth.substring(colon + 1);
        }
        int port = parsePort(portStr);
        if (host.isEmpty() || port <= 0) return null;
        return new Entry(TYPE_SOCKS5, host, port, user, pass, null);
    }

    private static Map<String, String> queryParams(String query) {
        Map<String, String> out = new LinkedHashMap<>();
        if (query == null) return out;
        int hash = query.indexOf('#');
        if (hash >= 0) query = query.substring(0, hash);
        for (String part : query.split("&")) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            String k = decode(eq >= 0 ? part.substring(0, eq) : part).toLowerCase(Locale.ROOT);
            String v = eq >= 0 ? decode(part.substring(eq + 1)) : "";
            if (!out.containsKey(k)) out.put(k, v);
        }
        return out;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    static int parsePort(String s) {
        if (s == null) return -1;
        try {
            int p = Integer.parseInt(s.trim());
            return p > 0 && p < 65536 ? p : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ subscriptions

    /** Proxies in a subscription body: a Clash / mihomo YAML config, or a (base64) list of links. */
    public static Result parseSubscription(String body) {
        if (body == null) return new Result(new ArrayList<>(), 0);
        String text = body.replace("\r\n", "\n").replace('\r', '\n');
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        if (hasProxiesSection(text)) return parseClash(text);
        String decoded = decodeBase64(text.trim());
        return parseLinkList(decoded != null ? decoded : text);
    }

    private static boolean hasProxiesSection(String text) {
        for (String line : text.split("\n", -1)) {
            if (line.startsWith("proxies:")) return true;
        }
        return false;
    }

    private static String decodeBase64(String s) {
        if (s.isEmpty() || s.contains("://")) return null;
        String clean = s.replaceAll("\\s", "");
        if (!clean.matches("[A-Za-z0-9+/=_-]+")) return null;
        clean = clean.replace('-', '+').replace('_', '/');
        while (clean.length() % 4 != 0) clean += "=";
        byte[] out = base64(clean);
        return out == null ? null : new String(out, StandardCharsets.UTF_8);
    }

    /** Standard base64 with padding (java.util.Base64 needs API 26). Null when invalid. */
    static byte[] base64(String s) {
        if (s.length() % 4 != 0) return null;
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(s.length() * 3 / 4);
        for (int i = 0; i < s.length(); i += 4) {
            int[] v = new int[4];
            int pad = 0;
            for (int j = 0; j < 4; j++) {
                char c = s.charAt(i + j);
                if (c == '=') {
                    if (i + 4 != s.length() || j < 2) return null;
                    v[j] = 0;
                    pad++;
                } else {
                    if (pad > 0) return null;
                    v[j] = alphabet.indexOf(c);
                    if (v[j] < 0) return null;
                }
            }
            int n = (v[0] << 18) | (v[1] << 12) | (v[2] << 6) | v[3];
            out.write((n >> 16) & 0xff);
            if (pad < 2) out.write((n >> 8) & 0xff);
            if (pad < 1) out.write(n & 0xff);
        }
        return out.toByteArray();
    }

    private static Result parseLinkList(String text) {
        Set<Entry> found = new LinkedHashSet<>();
        int skipped = 0;
        for (String line : text.split("\n")) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("#") || !l.contains("://") && !l.startsWith("tg:")) continue;
            Entry e = parseLink(l);
            if (e != null) {
                found.add(e);
            } else {
                skipped++;
            }
        }
        return new Result(new ArrayList<>(found), skipped);
    }

    /** The {@code proxies:} list of a Clash / mihomo config, block or flow style. */
    static Result parseClash(String text) {
        List<Map<String, String>> items = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        boolean inProxies = false;
        Map<String, String> cur = null;
        int itemIndent = -1;
        int keyIndent = -1;
        // nested maps of the current item: indent of each open key and its dotted prefix
        List<int[]> openIndent = new ArrayList<>();
        List<String> openPrefix = new ArrayList<>();
        for (String line : lines) {
            if (line.trim().isEmpty() || line.trim().startsWith("#")) continue;
            int indent = line.length() - line.replaceAll("^ +", "").length();
            String t = line.trim();
            if (indent == 0 && !t.startsWith("-")) {
                inProxies = t.startsWith("proxies:");
                cur = null;
                itemIndent = -1;
                continue;
            }
            if (!inProxies) continue;
            if (t.startsWith("- ") || t.equals("-")) {
                if (itemIndent < 0) itemIndent = indent;
                if (indent == itemIndent) {
                    String after = t.substring(1);
                    String rest = after.trim();
                    keyIndent = indent + 1 + (after.length() - after.replaceAll("^ +", "").length());
                    cur = new LinkedHashMap<>();
                    items.add(cur);
                    openIndent.clear();
                    openPrefix.clear();
                    if (rest.startsWith("{")) {
                        parseFlowMap(rest, cur, "");
                        cur = null;
                    } else if (!rest.isEmpty()) {
                        String opened = putPair(rest, cur, "");
                        if (opened != null) {
                            openIndent.add(new int[]{keyIndent});
                            openPrefix.add(opened);
                        }
                    }
                    continue;
                }
            }
            // a key of the current block item; nested maps become dotted keys (ws-opts.path)
            if (cur == null || indent < keyIndent || t.startsWith("-")) continue;
            while (!openIndent.isEmpty() && openIndent.get(openIndent.size() - 1)[0] >= indent) {
                openIndent.remove(openIndent.size() - 1);
                openPrefix.remove(openPrefix.size() - 1);
            }
            if (indent > keyIndent && openIndent.isEmpty()) continue; // under a list or scalar
            String prefix = openPrefix.isEmpty() ? "" : openPrefix.get(openPrefix.size() - 1);
            String opened = putPair(t, cur, prefix);
            if (opened != null) {
                openIndent.add(new int[]{indent});
                openPrefix.add(opened);
            }
        }
        Set<Entry> found = new LinkedHashSet<>();
        int skipped = 0;
        for (Map<String, String> m : items) {
            Entry e = fromClash(m);
            if (e != null) {
                found.add(e);
            } else {
                skipped++;
            }
        }
        return new Result(new ArrayList<>(found), skipped);
    }

    private static Entry fromClash(Map<String, String> m) {
        String type = m.get("type");
        if (type == null) return null;
        if (!type.equalsIgnoreCase("socks5")) {
            String link = org.telegram.luckgram.xray.XrayLogic.fromClash(m);
            org.telegram.luckgram.xray.XrayLogic.Node node = link == null ? null : org.telegram.luckgram.xray.XrayLogic.parse(link);
            return node == null ? null : Entry.xray(node);
        }
        String tls = m.get("tls");
        if (tls != null && tls.equalsIgnoreCase("true")) return null; // Telegram has no SOCKS5 over TLS
        String server = m.get("server");
        int port = parsePort(m.get("port"));
        if (server == null || server.isEmpty() || port <= 0) return null;
        return new Entry(TYPE_SOCKS5, server, port, m.get("username"), m.get("password"), null);
    }

    /**
     * {@code key: value} into {@code into} as {@code prefix + key}; a flow map value is flattened.
     * Returns the prefix for the keys below when the value is empty (a block map opens), else null.
     */
    private static String putPair(String t, Map<String, String> into, String prefix) {
        int colon = keyColon(t);
        if (colon <= 0) return null;
        String key = prefix + unquote(t.substring(0, colon).trim());
        String value = t.substring(colon + 1).trim();
        if (value.isEmpty() || value.startsWith("#")) return key + ".";
        if (value.startsWith("{")) {
            parseFlowMap(value, into, key + ".");
            return null;
        }
        if (value.startsWith("[")) return null; // lists are not used
        into.put(key, unquote(stripComment(value)));
        return null;
    }

    /** Index of the ':' that ends a key (outside quotes, followed by a space or the end). */
    private static int keyColon(String t) {
        char quote = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == ':' && (i + 1 == t.length() || t.charAt(i + 1) == ' ')) {
                return i;
            }
        }
        return -1;
    }

    private static String stripComment(String v) {
        if (v.startsWith("\"") || v.startsWith("'")) return v;
        int h = v.indexOf(" #");
        return h >= 0 ? v.substring(0, h).trim() : v;
    }

    /** {@code {a: 1, b: "x, y", c: {d: e}}} → a, b, c.d (with {@code prefix} in front). */
    static void parseFlowMap(String s, Map<String, String> into, String prefix) {
        int start = s.indexOf('{');
        int depth = 0;
        char quote = 0;
        StringBuilder part = new StringBuilder();
        for (int i = start + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                part.append(c);
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                part.append(c);
            } else if (c == '{' || c == '[') {
                depth++;
                part.append(c);
            } else if (c == '}' || c == ']') {
                if (depth == 0) {
                    putPair(part.toString().trim(), into, prefix);
                    return;
                }
                depth--;
                part.append(c);
            } else if (c == ',' && depth == 0) {
                putPair(part.toString().trim(), into, prefix);
                part.setLength(0);
            } else {
                part.append(c);
            }
        }
        putPair(part.toString().trim(), into, prefix);
    }

    static String unquote(String v) {
        if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"') {
            return v.substring(1, v.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        if (v.length() >= 2 && v.charAt(0) == '\'' && v.charAt(v.length() - 1) == '\'') {
            return v.substring(1, v.length() - 1).replace("''", "'");
        }
        return v;
    }

    // ------------------------------------------------------------------ refresh

    /**
     * Keys a subscription owns after a refresh: the entries it still lists that it owned before or
     * adds now. A proxy that was already in the list (added by hand) is not owned, so deleting the
     * subscription never removes it.
     */
    public static Set<String> owned(Set<String> previouslyOwned, List<Entry> now, Diff diff) {
        Set<String> added = new java.util.HashSet<>();
        for (Entry e : diff.add) added.add(e.key());
        Set<String> out = new LinkedHashSet<>();
        for (Entry e : now) {
            if (previouslyOwned.contains(e.key()) || added.contains(e.key())) out.add(e.key());
        }
        return out;
    }

    /**
     * What a refresh changes: proxies now in the subscription that were not imported before are
     * added; keys imported before that are gone are removed. {@code existing} are the keys of every
     * proxy in the list (a proxy already added by hand is not added twice).
     */
    public static Diff diff(Set<String> previouslyImported, List<Entry> now, Set<String> existing) {
        Diff d = new Diff();
        Set<String> nowKeys = new LinkedHashSet<>();
        for (Entry e : now) {
            nowKeys.add(e.key());
            if (!existing.contains(e.key())) d.add.add(e);
        }
        for (String k : previouslyImported) {
            if (!nowKeys.contains(k)) d.remove.add(k);
        }
        return d;
    }
}
