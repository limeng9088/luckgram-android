package org.telegram.luckgram.xray;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * specs#119: VLESS / VMess / Trojan / Shadowsocks share links for the built-in Xray core.
 *
 * <p>Every node is kept as one canonical share link in the form libXray parses
 * ({@code scheme://id@host:port?query#name}); v2rayN VMess JSON links, legacy {@code ss://} links
 * and Clash / mihomo entries are converted to it. The Xray config that turns each node into a
 * loopback SOCKS5 inbound is built here too.
 *
 * <p>Pure Java (java.* and org.json only), unit-tested in {@code XrayLogicTest}. Same rules as iOS
 * {@code LuckgramXrayLogic}.
 */
public final class XrayLogic {

    public static final String VLESS = "vless";
    public static final String VMESS = "vmess";
    public static final String TROJAN = "trojan";
    public static final String SS = "ss";

    private XrayLogic() {
    }

    /** One node: what the list shows and how it is recognised across refreshes. */
    public static final class Node {
        public final String protocol;
        public final String host;
        public final int port;
        public final String name;
        /** Canonical share link. */
        public final String link;

        Node(String protocol, String host, int port, String name, String link) {
            this.protocol = protocol;
            this.host = host;
            this.port = port;
            this.name = name == null ? "" : name;
            this.link = link;
        }

        /** The link without its name: renaming a node in a subscription keeps it the same node. */
        public String key() {
            int hash = link.indexOf('#');
            return "xray|" + (hash >= 0 ? link.substring(0, hash) : link);
        }
    }

    /** "VLESS", "VMess", "Trojan", "SS". */
    public static String label(String protocol) {
        if (VLESS.equals(protocol)) return "VLESS";
        if (VMESS.equals(protocol)) return "VMess";
        if (TROJAN.equals(protocol)) return "Trojan";
        if (SS.equals(protocol)) return "SS";
        return protocol == null ? "" : protocol.toUpperCase(Locale.ROOT);
    }

    /** Whether the text starts with a scheme handled here (it may still be incomplete). */
    public static boolean looksLikeLink(String raw) {
        if (raw == null) return false;
        String l = raw.trim().toLowerCase(Locale.ROOT);
        return l.startsWith("vless://") || l.startsWith("vmess://") || l.startsWith("trojan://") || l.startsWith("ss://");
    }

    // ------------------------------------------------------------------ share links

    /** A share link as a node, or null when it is not one of the four or is incomplete. */
    public static Node parse(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.startsWith("vmess://")) {
            Node n = parseVmessJson(text.substring(8));
            if (n != null) return n;
            return parseUrl(VMESS, text);
        }
        if (lower.startsWith("ss://")) return parseSs(text);
        if (lower.startsWith("vless://")) return parseUrl(VLESS, text);
        if (lower.startsWith("trojan://")) return parseUrl(TROJAN, text);
        return null;
    }

    /** {@code scheme://id@host:port?query#name}: id, host and port are required. */
    private static Node parseUrl(String protocol, String text) {
        String rest = text.substring(text.indexOf("://") + 3);
        String name = "";
        int hash = rest.indexOf('#');
        if (hash >= 0) {
            name = decode(rest.substring(hash + 1));
            rest = rest.substring(0, hash);
        }
        String query = "";
        int q = rest.indexOf('?');
        if (q >= 0) {
            query = rest.substring(q + 1);
            rest = rest.substring(0, q);
        }
        if (rest.endsWith("/")) rest = rest.substring(0, rest.length() - 1);
        int at = rest.lastIndexOf('@');
        if (at <= 0) return null;
        String id = rest.substring(0, at);
        String[] hp = hostPort(rest.substring(at + 1));
        if (hp == null || decode(id).isEmpty()) return null;
        int port = parsePort(hp[1]);
        if (port <= 0) return null;
        StringBuilder link = new StringBuilder(protocol).append("://").append(id).append('@').append(hostForUrl(hp[0])).append(':').append(port);
        if (!query.isEmpty()) link.append('?').append(query);
        if (!name.isEmpty()) link.append('#').append(encode(name));
        return new Node(protocol, hp[0], port, name, link.toString());
    }

    /** SIP002 ({@code ss://base64(method:pass)@host:port} or plain {@code method:pass}) and the legacy {@code ss://base64(method:pass@host:port)}. */
    private static Node parseSs(String text) {
        String rest = text.substring(5);
        String name = "";
        int hash = rest.indexOf('#');
        if (hash >= 0) {
            name = decode(rest.substring(hash + 1));
            rest = rest.substring(0, hash);
        }
        String query = "";
        int q = rest.indexOf('?');
        if (q >= 0) {
            query = rest.substring(q + 1);
            rest = rest.substring(0, q);
        }
        if (rest.endsWith("/")) rest = rest.substring(0, rest.length() - 1);
        if (query.toLowerCase(Locale.ROOT).contains("plugin=")) return null; // obfs / v2ray-plugin: not in Xray
        String userInfo;
        String hostPort;
        int at = rest.lastIndexOf('@');
        if (at < 0) {
            String decoded = base64Text(rest);
            if (decoded == null) return null;
            int at2 = decoded.lastIndexOf('@');
            if (at2 <= 0) return null;
            userInfo = decoded.substring(0, at2);
            hostPort = decoded.substring(at2 + 1);
        } else {
            String u = decode(rest.substring(0, at));
            userInfo = u.contains(":") ? u : base64Text(u);
            hostPort = rest.substring(at + 1);
        }
        if (userInfo == null) return null;
        int colon = userInfo.indexOf(':');
        if (colon <= 0 || colon == userInfo.length() - 1) return null;
        String[] hp = hostPort(hostPort);
        if (hp == null) return null;
        int port = parsePort(hp[1]);
        if (port <= 0) return null;
        String link = "ss://" + base64UrlNoPad(userInfo.getBytes(StandardCharsets.UTF_8)) + "@" + hostForUrl(hp[0]) + ":" + port
                + (name.isEmpty() ? "" : "#" + encode(name));
        return new Node(SS, hp[0], port, name, link);
    }

    /** v2rayN: {@code vmess://base64({"add","port","id","net","host","path","tls","sni",...})}. */
    private static Node parseVmessJson(String b64) {
        String json = base64Text(b64.contains("#") ? b64.substring(0, b64.indexOf('#')) : b64);
        if (json == null || !json.trim().startsWith("{")) return null;
        try {
            JSONObject o = new JSONObject(json);
            Map<String, String> q = new LinkedHashMap<>();
            String net = o.optString("net", "tcp");
            String host = o.optString("host");
            String path = o.optString("path");
            String type = o.optString("type");
            q.put("encryption", nonEmpty(o.optString("scy"), "auto"));
            String tls = o.optString("tls");
            q.put("security", "tls".equalsIgnoreCase(tls) ? "tls" : "reality".equalsIgnoreCase(tls) ? "reality" : "none");
            putIf(q, "sni", o.optString("sni"));
            putIf(q, "fp", o.optString("fp"));
            putIf(q, "alpn", o.optString("alpn"));
            q.put("type", net.isEmpty() ? "tcp" : net);
            if ("grpc".equals(net)) {
                putIf(q, "serviceName", path);
            } else {
                putIf(q, "host", host);
                putIf(q, "path", path);
            }
            if (("tcp".equals(net) || net.isEmpty()) && "http".equals(type)) q.put("headerType", "http");
            // alterId > 0 is legacy VMess (MD5 auth); Xray only supports AEAD (alterId 0).
            if (o.optInt("aid", 0) > 0) return null;
            String link = buildLink(VMESS, o.optString("id"), o.optString("add"), parsePort(o.optString("port")), q, o.optString("ps"));
            return link == null ? null : parseUrl(VMESS, link);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ Clash / mihomo

    /**
     * A Clash / mihomo proxy (nested maps flattened to dotted keys, e.g. {@code ws-opts.path},
     * {@code ws-opts.headers.Host}) as a canonical share link, or null when it cannot be used.
     */
    public static String fromClash(Map<String, String> m) {
        String type = lower(m.get("type"));
        String server = m.get("server");
        int port = parsePort(m.get("port"));
        String name = m.get("name");
        if (server == null || server.isEmpty() || port <= 0) return null;
        Map<String, String> q = new LinkedHashMap<>();
        switch (type) {
            case VLESS: {
                String uuid = m.get("uuid");
                q.put("encryption", nonEmpty(m.get("encryption"), "none"));
                putIf(q, "flow", m.get("flow"));
                if (!security(m, q)) return null;
                if (!transport(m, q)) return null;
                return buildLink(VLESS, uuid, server, port, q, name);
            }
            case VMESS: {
                if (parseInt(m.get("alterId")) > 0) return null; // legacy MD5 auth
                q.put("encryption", nonEmpty(m.get("cipher"), "auto"));
                if (!security(m, q)) return null;
                if (!transport(m, q)) return null;
                return buildLink(VMESS, m.get("uuid"), server, port, q, name);
            }
            case TROJAN: {
                q.put("security", "tls");
                putIf(q, "sni", nonEmpty(m.get("sni"), m.get("servername")));
                putIf(q, "fp", m.get("client-fingerprint"));
                if (m.containsKey("reality-opts.public-key")) {
                    q.put("security", "reality");
                    putIf(q, "pbk", m.get("reality-opts.public-key"));
                    putIf(q, "sid", m.get("reality-opts.short-id"));
                }
                if (!transport(m, q)) return null;
                return buildLink(TROJAN, m.get("password"), server, port, q, name);
            }
            case SS: {
                if (m.containsKey("plugin")) return null; // obfs / v2ray-plugin / shadow-tls: not in Xray
                String cipher = m.get("cipher");
                String password = m.get("password");
                if (cipher == null || cipher.isEmpty() || password == null || password.isEmpty()) return null;
                return "ss://" + base64UrlNoPad((cipher + ":" + password).getBytes(StandardCharsets.UTF_8)) + "@" + hostForUrl(server) + ":" + port
                        + (name == null || name.isEmpty() ? "" : "#" + encode(name));
            }
            default:
                return null;
        }
    }

    /** tls / reality → security, sni, fp, alpn, pbk, sid. False when the combination is unusable. */
    private static boolean security(Map<String, String> m, Map<String, String> q) {
        boolean reality = m.containsKey("reality-opts.public-key");
        boolean tls = "true".equalsIgnoreCase(m.get("tls"));
        if (reality) {
            q.put("security", "reality");
            putIf(q, "pbk", m.get("reality-opts.public-key"));
            putIf(q, "sid", m.get("reality-opts.short-id"));
        } else {
            q.put("security", tls ? "tls" : "none");
        }
        if (tls || reality) {
            putIf(q, "sni", nonEmpty(m.get("servername"), m.get("sni")));
            putIf(q, "fp", m.get("client-fingerprint"));
        }
        return true;
    }

    /** network → type, host, path, serviceName. False for a transport Xray cannot use. */
    private static boolean transport(Map<String, String> m, Map<String, String> q) {
        String network = lower(nonEmpty(m.get("network"), "tcp"));
        switch (network) {
            case "tcp":
                q.put("type", "tcp");
                return true;
            case "ws":
                q.put("type", "ws");
                putIf(q, "host", nonEmpty(m.get("ws-opts.headers.Host"), m.get("ws-opts.headers.host")));
                putIf(q, "path", m.get("ws-opts.path"));
                return true;
            case "grpc":
                q.put("type", "grpc");
                putIf(q, "serviceName", m.get("grpc-opts.grpc-service-name"));
                return true;
            case "h2":
            case "http":
                q.put("type", "http");
                putIf(q, "path", m.get("h2-opts.path"));
                return true;
            case "xhttp":
                q.put("type", "xhttp");
                putIf(q, "host", m.get("xhttp-opts.host"));
                putIf(q, "path", m.get("xhttp-opts.path"));
                putIf(q, "mode", m.get("xhttp-opts.mode"));
                return true;
            default:
                return false;
        }
    }

    private static String buildLink(String protocol, String id, String host, int port, Map<String, String> q, String name) {
        if (id == null || id.isEmpty() || host == null || host.isEmpty() || port <= 0) return null;
        StringBuilder b = new StringBuilder(protocol).append("://").append(encode(id)).append('@').append(hostForUrl(host)).append(':').append(port);
        char sep = '?';
        for (Map.Entry<String, String> e : q.entrySet()) {
            b.append(sep).append(e.getKey()).append('=').append(encode(e.getValue()));
            sep = '&';
        }
        if (name != null && !name.isEmpty()) b.append('#').append(encode(name));
        return b.toString();
    }

    // ------------------------------------------------------------------ Xray config

    /** One node to run: its local SOCKS5 port and the outbound libXray built from its link. */
    public static final class Run {
        public final int localPort;
        public final String outboundJson;

        public Run(int localPort, String outboundJson) {
            this.localPort = localPort;
            this.outboundJson = outboundJson;
        }
    }

    /**
     * The whole Xray config: one {@code 127.0.0.1} SOCKS5 inbound per node, with the same
     * username / password on each, routed to that node's outbound. Anything unmatched is dropped.
     */
    public static String buildConfig(List<Run> runs, String user, String password) throws Exception {
        JSONArray inbounds = new JSONArray();
        JSONArray outbounds = new JSONArray();
        JSONArray rules = new JSONArray();
        outbounds.put(new JSONObject().put("tag", "block").put("protocol", "blackhole"));
        for (int i = 0; i < runs.size(); i++) {
            Run r = runs.get(i);
            String in = "in-" + i;
            String out = "out-" + i;
            JSONObject account = new JSONObject().put("user", user).put("pass", password);
            inbounds.put(new JSONObject()
                    .put("tag", in)
                    .put("listen", "127.0.0.1")
                    .put("port", r.localPort)
                    .put("protocol", "socks")
                    .put("settings", new JSONObject()
                            .put("auth", "password")
                            .put("accounts", new JSONArray().put(account))
                            .put("udp", true)
                            .put("ip", "127.0.0.1")));
            JSONObject ob = new JSONObject(r.outboundJson);
            ob.put("tag", out);
            ob.remove("sendThrough");
            outbounds.put(ob);
            rules.put(new JSONObject().put("type", "field").put("inboundTag", new JSONArray().put(in)).put("outboundTag", out));
        }
        return new JSONObject()
                .put("log", new JSONObject().put("loglevel", "warning").put("access", "none"))
                .put("inbounds", inbounds)
                .put("outbounds", outbounds)
                .put("routing", new JSONObject().put("domainStrategy", "AsIs").put("rules", rules))
                .toString();
    }

    /** The first outbound of a {@code convertShareLinksToXrayJson} result, or null. */
    public static String firstOutbound(String convertedJson) {
        try {
            JSONArray a = new JSONObject(convertedJson).optJSONArray("outbounds");
            return a != null && a.length() > 0 ? a.getJSONObject(0).toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** {@code n} free ports counting up from {@code base} (wrapping inside 20000–59999), skipping {@code used}. */
    public static List<Integer> pickPorts(int base, Set<Integer> used, int n) {
        List<Integer> out = new ArrayList<>();
        int p = base < 20000 || base > 59999 ? 20000 : base;
        for (int tries = 0; out.size() < n && tries < 40000; tries++) {
            if (!used.contains(p) && !out.contains(p)) out.add(p);
            p = p >= 59999 ? 20000 : p + 1;
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private static String[] hostPort(String s) {
        if (s.startsWith("[")) {
            int close = s.indexOf(']');
            if (close < 0 || s.length() < close + 3 || s.charAt(close + 1) != ':') return null;
            return new String[]{s.substring(1, close), s.substring(close + 2)};
        }
        int colon = s.lastIndexOf(':');
        if (colon <= 0) return null;
        return new String[]{s.substring(0, colon), s.substring(colon + 1)};
    }

    private static String hostForUrl(String host) {
        return host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    }

    static int parsePort(String s) {
        int p = parseInt(s);
        return p > 0 && p < 65536 ? p : -1;
    }

    private static int parseInt(String s) {
        if (s == null) return -1;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String lower(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String nonEmpty(String a, String b) {
        return a != null && !a.isEmpty() ? a : b;
    }

    private static void putIf(Map<String, String> q, String k, String v) {
        if (v != null && !v.isEmpty()) q.put(k, v);
    }

    static String decode(String s) {
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    private static final String B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    /** Base64 or base64url, padding optional, as UTF-8 text; null when invalid. */
    static String base64Text(String s) {
        String clean = s.trim().replaceAll("\\s", "").replace('-', '+').replace('_', '/');
        int eq = clean.indexOf('=');
        if (eq >= 0) clean = clean.substring(0, eq);
        if (clean.isEmpty() || !clean.matches("[A-Za-z0-9+/]+") || clean.length() % 4 == 1) return null;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buf = 0;
        int bits = 0;
        for (int i = 0; i < clean.length(); i++) {
            buf = (buf << 6) | B64.indexOf(clean.charAt(i));
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((buf >> bits) & 0xff);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    static String base64UrlNoPad(byte[] data) {
        StringBuilder b = new StringBuilder();
        int i = 0;
        for (; i + 2 < data.length; i += 3) {
            int n = ((data[i] & 0xff) << 16) | ((data[i + 1] & 0xff) << 8) | (data[i + 2] & 0xff);
            b.append(B64.charAt(n >> 18)).append(B64.charAt((n >> 12) & 63)).append(B64.charAt((n >> 6) & 63)).append(B64.charAt(n & 63));
        }
        if (data.length - i == 1) {
            int n = (data[i] & 0xff) << 16;
            b.append(B64.charAt(n >> 18)).append(B64.charAt((n >> 12) & 63));
        } else if (data.length - i == 2) {
            int n = ((data[i] & 0xff) << 16) | ((data[i + 1] & 0xff) << 8);
            b.append(B64.charAt(n >> 18)).append(B64.charAt((n >> 12) & 63)).append(B64.charAt((n >> 6) & 63));
        }
        return b.toString().replace('+', '-').replace('/', '_');
    }
}
