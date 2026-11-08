package org.telegram.luckgram.xray;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class XrayLogicTest {

    private static final String UUID = "1b0c5e6a-0000-4000-8000-000000000001";

    @Test
    public void vlessLink() {
        XrayLogic.Node n = XrayLogic.parse("vless://" + UUID + "@edge.example.com:443?encryption=none&security=tls&sni=edge.example.com&type=ws&path=%2Fws#SG%201");
        assertNotNull(n);
        assertEquals(XrayLogic.VLESS, n.protocol);
        assertEquals("edge.example.com", n.host);
        assertEquals(443, n.port);
        assertEquals("SG 1", n.name);
        assertTrue(n.link.startsWith("vless://" + UUID + "@edge.example.com:443?"));
        assertTrue(n.link.endsWith("#SG%201"));
        // renaming keeps the identity
        XrayLogic.Node renamed = XrayLogic.parse(n.link.replace("#SG%201", "#other"));
        assertEquals(n.key(), renamed.key());
    }

    @Test
    public void incompleteLinks() {
        assertNull(XrayLogic.parse("vless://edge.example.com:443"));
        assertNull(XrayLogic.parse("vless://" + UUID + "@edge.example.com"));
        assertNull(XrayLogic.parse("trojan://@h:443"));
        assertNull(XrayLogic.parse("vless://" + UUID + "@h:70000"));
        assertNull(XrayLogic.parse("http://example.com"));
    }

    @Test
    public void ipv6Host() {
        XrayLogic.Node n = XrayLogic.parse("trojan://pw@[2001:db8::1]:443?security=tls#t");
        assertEquals("2001:db8::1", n.host);
        assertTrue(n.link.contains("@[2001:db8::1]:443"));
    }

    @Test
    public void vmessV2rayNJson() throws Exception {
        JSONObject o = new JSONObject()
                .put("v", "2").put("ps", "US 1").put("add", "v.example.com").put("port", "8443")
                .put("id", UUID).put("aid", "0").put("net", "ws").put("host", "cdn.example.com")
                .put("path", "/v").put("tls", "tls").put("sni", "cdn.example.com");
        String link = "vmess://" + java.util.Base64.getEncoder().encodeToString(o.toString().getBytes(StandardCharsets.UTF_8));
        XrayLogic.Node n = XrayLogic.parse(link);
        assertNotNull(n);
        assertEquals(XrayLogic.VMESS, n.protocol);
        assertEquals("v.example.com", n.host);
        assertEquals(8443, n.port);
        assertEquals("US 1", n.name);
        // canonical form is the URL libXray parses
        assertTrue(n.link, n.link.startsWith("vmess://" + UUID + "@v.example.com:8443?encryption=auto&security=tls&sni=cdn.example.com&type=ws&host=cdn.example.com&path=%2Fv"));
        // legacy alterId > 0 is not supported by Xray
        o.put("aid", "64");
        assertNull(XrayLogic.parse("vmess://" + java.util.Base64.getEncoder().encodeToString(o.toString().getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    public void shadowsocksForms() {
        String userInfo = XrayLogic.base64UrlNoPad("aes-128-gcm:pass".getBytes(StandardCharsets.UTF_8));
        XrayLogic.Node sip002 = XrayLogic.parse("ss://" + userInfo + "@198.51.100.5:8388#SS%201");
        assertEquals(XrayLogic.SS, sip002.protocol);
        assertEquals(8388, sip002.port);
        assertEquals("SS 1", sip002.name);

        XrayLogic.Node plain = XrayLogic.parse("ss://aes-128-gcm:pass@198.51.100.5:8388");
        assertEquals(sip002.key(), plain.key());

        String legacy = java.util.Base64.getEncoder().encodeToString("aes-128-gcm:pass@198.51.100.5:8388".getBytes(StandardCharsets.UTF_8));
        XrayLogic.Node old = XrayLogic.parse("ss://" + legacy + "#x");
        assertEquals(sip002.key(), old.key());

        assertNull(XrayLogic.parse("ss://" + userInfo + "@198.51.100.5:8388/?plugin=obfs-local%3Bobfs%3Dhttp"));
    }

    private static Map<String, String> clash(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @Test
    public void clashVlessWsTls() {
        String link = XrayLogic.fromClash(clash(
                "name", "SG·hk 1001", "type", "vless", "server", "agg.example.com", "port", "443",
                "uuid", UUID, "tls", "true", "servername", "sni.example.com", "client-fingerprint", "chrome",
                "network", "ws", "ws-opts.path", "/ray?ed=2048", "ws-opts.headers.Host", "sni.example.com", "udp", "true"));
        assertEquals("vless://" + UUID + "@agg.example.com:443?encryption=none&security=tls&sni=sni.example.com&fp=chrome&type=ws&host=sni.example.com&path=%2Fray%3Fed%3D2048#SG%C2%B7hk%201001", link);
        XrayLogic.Node n = XrayLogic.parse(link);
        assertEquals("SG·hk 1001", n.name);
        assertEquals(link, n.link);
    }

    @Test
    public void clashReality() {
        String link = XrayLogic.fromClash(clash(
                "name", "r", "type", "vless", "server", "r.example.com", "port", "443", "uuid", UUID,
                "flow", "xtls-rprx-vision", "tls", "true", "servername", "www.example.com",
                "reality-opts.public-key", "PUBKEY", "reality-opts.short-id", "ab12", "client-fingerprint", "chrome"));
        assertTrue(link, link.contains("flow=xtls-rprx-vision") && link.contains("security=reality") && link.contains("pbk=PUBKEY")
                && link.contains("sid=ab12") && link.contains("sni=www.example.com") && link.contains("type=tcp"));
    }

    @Test
    public void clashOthers() {
        String vmess = XrayLogic.fromClash(clash("name", "m", "type", "vmess", "server", "m.example.com", "port", "443",
                "uuid", UUID, "alterId", "0", "cipher", "auto", "tls", "true", "network", "grpc", "grpc-opts.grpc-service-name", "svc"));
        assertTrue(vmess, vmess.startsWith("vmess://" + UUID + "@m.example.com:443?encryption=auto&security=tls&type=grpc&serviceName=svc"));
        assertNull(XrayLogic.fromClash(clash("type", "vmess", "server", "m", "port", "443", "uuid", UUID, "alterId", "64")));

        String trojan = XrayLogic.fromClash(clash("name", "t", "type", "trojan", "server", "t.example.com", "port", "443", "password", "pw", "sni", "t.example.com"));
        assertEquals("trojan://pw@t.example.com:443?security=tls&sni=t.example.com&type=tcp#t", trojan);

        String ss = XrayLogic.fromClash(clash("name", "s", "type", "ss", "server", "198.51.100.9", "port", "8388", "cipher", "chacha20-ietf-poly1305", "password", "pw"));
        assertEquals(XrayLogic.SS, XrayLogic.parse(ss).protocol);
        assertNull(XrayLogic.fromClash(clash("type", "ss", "server", "h", "port", "1", "cipher", "aes-128-gcm", "password", "p", "plugin", "obfs")));

        assertNull(XrayLogic.fromClash(clash("type", "hysteria2", "server", "h", "port", "443", "password", "p")));
        assertNull(XrayLogic.fromClash(clash("type", "vless", "server", "h", "port", "443", "uuid", UUID, "network", "kcp")));
        assertNull(XrayLogic.fromClash(clash("type", "vless", "server", "h", "port", "443")));
    }

    @Test
    public void config() throws Exception {
        String ob = "{\"protocol\":\"vless\",\"tag\":\"name from link\",\"settings\":{\"address\":\"a\",\"port\":443}}";
        List<XrayLogic.Run> runs = Arrays.asList(new XrayLogic.Run(31001, ob), new XrayLogic.Run(31002, ob));
        JSONObject c = new JSONObject(XrayLogic.buildConfig(runs, "lg", "secret"));
        JSONArray in = c.getJSONArray("inbounds");
        assertEquals(2, in.length());
        JSONObject first = in.getJSONObject(0);
        assertEquals("127.0.0.1", first.getString("listen"));
        assertEquals(31001, first.getInt("port"));
        assertEquals("socks", first.getString("protocol"));
        assertEquals("password", first.getJSONObject("settings").getString("auth"));
        assertEquals("secret", first.getJSONObject("settings").getJSONArray("accounts").getJSONObject(0).getString("pass"));
        JSONArray out = c.getJSONArray("outbounds");
        assertEquals("block", out.getJSONObject(0).getString("tag")); // unmatched traffic is dropped
        assertEquals("out-1", out.getJSONObject(2).getString("tag"));
        JSONObject rule = c.getJSONObject("routing").getJSONArray("rules").getJSONObject(1);
        assertEquals("in-1", rule.getJSONArray("inboundTag").getString(0));
        assertEquals("out-1", rule.getString("outboundTag"));
        assertEquals("warning", c.getJSONObject("log").getString("loglevel"));
    }

    @Test
    public void firstOutbound() {
        assertEquals("vless", new JSONObject(XrayLogic.firstOutbound("{\"outbounds\":[{\"protocol\":\"vless\"},{\"protocol\":\"x\"}]}")).getString("protocol"));
        assertNull(XrayLogic.firstOutbound("{\"outbounds\":[]}"));
        assertNull(XrayLogic.firstOutbound("nope"));
    }

    @Test
    public void ports() {
        assertEquals(Arrays.asList(30000, 30002, 30003), XrayLogic.pickPorts(30000, new HashSet<>(Arrays.asList(30001)), 3));
        assertEquals(Arrays.asList(59999, 20000), XrayLogic.pickPorts(59999, new HashSet<>(), 2));
        assertEquals(Arrays.asList(20000), XrayLogic.pickPorts(5, new HashSet<>(), 1));
    }

    @Test
    public void base64() {
        for (String s : new String[]{"", "f", "fo", "foo", "foob", "fooba", "foobar", "中文"}) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            String url = XrayLogic.base64UrlNoPad(b);
            assertEquals(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b), url);
            if (!s.isEmpty()) {
                assertEquals(s, XrayLogic.base64Text(url));
                assertEquals(s, XrayLogic.base64Text(java.util.Base64.getEncoder().encodeToString(b)));
            }
        }
        assertNull(XrayLogic.base64Text("@@@"));
        assertFalse(XrayLogic.looksLikeLink("socks5://h:1"));
        assertTrue(XrayLogic.looksLikeLink(" VLESS://x"));
    }
}
