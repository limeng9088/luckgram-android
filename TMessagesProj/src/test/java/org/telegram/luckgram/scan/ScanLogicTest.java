package org.telegram.luckgram.scan;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ScanLogicTest {

    @Test
    public void login() {
        assertEquals(ScanLogic.KIND_LOGIN, ScanLogic.kind("tg://login?token=AQIDBA"));
    }

    @Test
    public void proxies() {
        assertEquals(ScanLogic.KIND_PROXY, ScanLogic.kind("tg://proxy?server=1.2.3.4&port=443&secret=ee00"));
        assertEquals(ScanLogic.KIND_PROXY, ScanLogic.kind("tg://socks?server=1.2.3.4&port=1080"));
        assertEquals(ScanLogic.KIND_PROXY, ScanLogic.kind("https://t.me/proxy?server=1.2.3.4&port=443&secret=dd00"));
        assertEquals(ScanLogic.KIND_PROXY, ScanLogic.kind("t.me/socks?server=h&port=1"));
        assertEquals(ScanLogic.KIND_PROXY, ScanLogic.kind("HTTPS://TELEGRAM.ME/proxy?server=h&port=1&secret=x"));
    }

    @Test
    public void telegramLinks() {
        assertEquals(ScanLogic.KIND_TELEGRAM, ScanLogic.kind("https://t.me/luckgram"));
        assertEquals(ScanLogic.KIND_TELEGRAM, ScanLogic.kind("t.me/+8613800000000"));
        assertEquals(ScanLogic.KIND_TELEGRAM, ScanLogic.kind("https://t.me/+AbCdEf"));
        assertEquals(ScanLogic.KIND_TELEGRAM, ScanLogic.kind("tg://resolve?domain=luckgram"));
        assertEquals(ScanLogic.KIND_TELEGRAM, ScanLogic.kind("tg://user?id=123"));
        assertEquals(ScanLogic.KIND_TELEGRAM, ScanLogic.kind("https://www.t.me/luckgram"));
        // a proxy-looking username is still a user
        assertEquals(ScanLogic.KIND_TELEGRAM, ScanLogic.kind("https://t.me/proxybot"));
    }

    @Test
    public void webAndText() {
        assertEquals(ScanLogic.KIND_WEB, ScanLogic.kind("https://example.com/a?b=1"));
        assertEquals(ScanLogic.KIND_WEB, ScanLogic.kind("http://t.me.example.com/x"));
        assertEquals(ScanLogic.KIND_WEB, ScanLogic.kind("https://t.me"));
        assertEquals(ScanLogic.KIND_TEXT, ScanLogic.kind("hello world"));
        assertEquals(ScanLogic.KIND_TEXT, ScanLogic.kind("WIFI:S:home;T:WPA;P:secret;;"));
        assertEquals(ScanLogic.KIND_TEXT, ScanLogic.kind(""));
        assertEquals(ScanLogic.KIND_TEXT, ScanLogic.kind(null));
    }

    @Test
    public void urls() {
        assertEquals("https://t.me/luckgram", ScanLogic.url("t.me/luckgram"));
        assertEquals("https://t.me/luckgram", ScanLogic.url(" https://t.me/luckgram "));
        assertEquals("tg://resolve?domain=x", ScanLogic.url("tg://resolve?domain=x"));
    }

    @Test
    public void proxyStates() {
        assertEquals(ScanLogic.PROXY_OFF, ScanLogic.proxyState(false, true));
        assertEquals(ScanLogic.PROXY_CONNECTING, ScanLogic.proxyState(true, false));
        assertEquals(ScanLogic.PROXY_CONNECTED, ScanLogic.proxyState(true, true));
        assertEquals("连接中", new ScanLogic.Texts(true).proxyState(ScanLogic.PROXY_CONNECTING));
    }
}
