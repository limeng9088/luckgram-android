package org.telegram.luckgram.proxyimport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import org.junit.Test;

/** specs#118: proxy links, subscription parsing and refresh diff. */
public class ProxyImportLogicTest {

    private static final String S = ProxyImportLogic.TYPE_SOCKS5;

    @Test
    public void kinds() {
        assertEquals(ProxyImportLogic.KIND_PROXY, ProxyImportLogic.kind("tg://socks?server=1.2.3.4&port=1080"));
        assertEquals(ProxyImportLogic.KIND_PROXY, ProxyImportLogic.kind(" https://t.me/proxy?server=a.b&port=443&secret=ee00 "));
        assertEquals(ProxyImportLogic.KIND_PROXY, ProxyImportLogic.kind("socks5://u:p@1.2.3.4:1080"));
        assertEquals(ProxyImportLogic.KIND_SUBSCRIPTION, ProxyImportLogic.kind("https://agg.example.com/clash/123"));
        assertEquals(ProxyImportLogic.KIND_SUBSCRIPTION, ProxyImportLogic.kind("http://10.0.0.1:8080/sub"));
        assertEquals(ProxyImportLogic.KIND_UNKNOWN, ProxyImportLogic.kind("https://t.me/durov"));
        assertEquals(ProxyImportLogic.KIND_UNKNOWN, ProxyImportLogic.kind("tg://socks?server=1.2.3.4"));
        assertEquals(ProxyImportLogic.KIND_UNKNOWN, ProxyImportLogic.kind("hello world"));
        assertEquals(ProxyImportLogic.KIND_UNKNOWN, ProxyImportLogic.kind(null));
    }

    @Test
    public void tgLinks() {
        ProxyImportLogic.Entry e = ProxyImportLogic.parseLink("tg://socks?server=38.182.96.3&port=20201&user=lg%20sg&pass=a%2Bb%26c");
        assertEquals(S, e.type);
        assertEquals("38.182.96.3", e.host);
        assertEquals(20201, e.port);
        assertEquals("lg sg", e.user);
        assertEquals("a+b&c", e.password);
        ProxyImportLogic.Entry m = ProxyImportLogic.parseLink("https://t.me/proxy?server=mtp.example.org&port=443&secret=eeabcdef");
        assertEquals(ProxyImportLogic.TYPE_MTPROTO, m.type);
        assertEquals("eeabcdef", m.secret);
        assertEquals("example.com", ProxyImportLogic.parseLink("telegram.me/socks?server=example.com&port=1").host);
        assertNull(ProxyImportLogic.parseLink("tg://proxy?server=a&port=1")); // MTProto without secret
        assertNull(ProxyImportLogic.parseLink("tg://socks?server=a&port=99999"));
        assertNull(ProxyImportLogic.parseLink("https://example.com/socks?server=a&port=1"));
    }

    @Test
    public void socksUrls() {
        ProxyImportLogic.Entry e = ProxyImportLogic.parseLink("socks5://us%40er:p%3Ass@proxy.example.com:1080/");
        assertEquals("us@er", e.user);
        assertEquals("p:ss", e.password);
        assertEquals("proxy.example.com", e.host);
        assertEquals(1080, e.port);
        ProxyImportLogic.Entry v6 = ProxyImportLogic.parseLink("socks5h://[2001:db8::1]:1080");
        assertEquals("2001:db8::1", v6.host);
        assertEquals("", v6.user);
        assertEquals(1081, ProxyImportLogic.parseLink("socks://h:1081").port);
        assertNull(ProxyImportLogic.parseLink("socks5://host-without-port"));
    }

    private static final String CLASH = String.join("\n",
            "mixed-port: 7890",
            "proxies:",
            "  - {name: 'SG·vless', type: vless, server: agg.example.com, port: 443, uuid: x, ws-opts: {path: /a, headers: {Host: agg.example.com}}}",
            "  - {name: \"s5, flow\", type: socks5, server: 198.51.100.24, port: 1080, username: u1, password: 'p, 1'}",
            "  - name: s5-block",
            "    type: socks5",
            "    server: '198.51.100.77'",
            "    port: 1081",
            "    username: \"u2\"",
            "    password: p2 # comment",
            "    udp: true",
            "  - name: s5-tls",
            "    type: socks5",
            "    server: 192.0.2.1",
            "    port: 443",
            "    tls: true",
            "  - name: ss",
            "    type: ss",
            "    server: 192.0.2.2",
            "    port: 8388",
            "    plugin-opts:",
            "      mode: websocket",
            "      type: socks5",
            "proxy-groups:",
            "  - {name: g, type: select, proxies: [s5-block]}",
            "rules:",
            "  - MATCH,g");

    @Test
    public void clashOnlySocks5() {
        ProxyImportLogic.Result r = ProxyImportLogic.parseSubscription(CLASH);
        assertEquals(2, r.proxies.size());
        assertEquals("198.51.100.24", r.proxies.get(0).host);
        assertEquals("p, 1", r.proxies.get(0).password);
        assertEquals("198.51.100.77", r.proxies.get(1).host);
        assertEquals(1081, r.proxies.get(1).port);
        assertEquals("u2", r.proxies.get(1).user);
        assertEquals("p2", r.proxies.get(1).password);
        assertEquals(3, r.skipped); // vless, socks5 over TLS, ss (its nested "type: socks5" ignored)
    }

    @Test
    public void allVless() {
        String body = "proxies:\n- {name: a, type: vless, server: x, port: 443}\n- {name: b, type: vless, server: y, port: 443}\n";
        ProxyImportLogic.Result r = ProxyImportLogic.parseSubscription(body);
        assertEquals(0, r.proxies.size());
        assertEquals(2, r.skipped);
    }

    @Test
    public void base64LinkList() {
        String list = "tg://socks?server=198.51.100.1&port=1080\nvless://abc@x:443\nsocks5://u:p@198.51.100.2:1080\n\n# c\nsocks5://u:p@198.51.100.2:1080\n";
        String b64 = java.util.Base64.getEncoder().encodeToString(list.getBytes(StandardCharsets.UTF_8));
        ProxyImportLogic.Result r = ProxyImportLogic.parseSubscription(b64);
        assertEquals(2, r.proxies.size());
        assertEquals(1, r.skipped);
        ProxyImportLogic.Result plain = ProxyImportLogic.parseSubscription(list);
        assertEquals(2, plain.proxies.size());
        String noPad = b64.replace("=", "").replace('+', '-').replace('/', '_');
        assertEquals(2, ProxyImportLogic.parseSubscription(noPad).proxies.size());
    }

    @Test
    public void base64Decoder() {
        for (String s : new String[]{"", "f", "fo", "foo", "foob", "fooba", "foobar"}) {
            String enc = java.util.Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
            assertEquals(s, new String(ProxyImportLogic.base64(enc), StandardCharsets.UTF_8));
        }
        assertNull(ProxyImportLogic.base64("abc"));
        assertNull(ProxyImportLogic.base64("a=bc"));
    }

    @Test
    public void refreshDiff() {
        ProxyImportLogic.Entry a = new ProxyImportLogic.Entry(S, "198.51.100.1", 1, "", "", "");
        ProxyImportLogic.Entry b = new ProxyImportLogic.Entry(S, "198.51.100.2", 1, "", "", "");
        ProxyImportLogic.Entry c = new ProxyImportLogic.Entry(S, "198.51.100.3", 1, "", "", "");
        List<ProxyImportLogic.Entry> now = Arrays.asList(b, c);
        ProxyImportLogic.Diff d = ProxyImportLogic.diff(new HashSet<>(Arrays.asList(a.key(), b.key())), now,
                new HashSet<>(Arrays.asList(a.key(), b.key())));
        assertEquals(Collections.singletonList(c), d.add);
        assertEquals(Collections.singletonList(a.key()), d.remove);
        // c already added by hand: not added again
        ProxyImportLogic.Diff d2 = ProxyImportLogic.diff(new HashSet<>(), now, new HashSet<>(Arrays.asList(c.key())));
        assertEquals(Collections.singletonList(b), d2.add);
        assertTrue(d2.remove.isEmpty());
    }

    @Test
    public void hosts() {
        assertEquals("agg.example.com", ProxyImportLogic.hostOf("https://agg.example.com/clash/1?x=y"));
        assertEquals("a.b", ProxyImportLogic.hostOf("https://u:p@A.B:8443/"));
        assertNull(ProxyImportLogic.hostOf("nope"));
    }
}
