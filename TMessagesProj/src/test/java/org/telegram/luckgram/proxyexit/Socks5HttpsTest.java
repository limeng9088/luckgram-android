package org.telegram.luckgram.proxyexit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

/** specs#117: SOCKS5 handshake bytes (RFC 1928/1929) and HTTP response parsing. */
public class Socks5HttpsTest {

    private static byte[] b(int... v) {
        byte[] r = new byte[v.length];
        for (int i = 0; i < v.length; i++) r[i] = (byte) v[i];
        return r;
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    private static final byte[] CONNECT_OK = b(5, 0, 0, 1, 1, 2, 3, 4, 0x01, 0xBB);

    @Test
    public void handshakeWithPassword() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] server = cat(b(5, 2), b(1, 0), CONNECT_OK);
        Socks5Https.handshake(new ByteArrayInputStream(server), out, "api.luckgram.cc", 443, "u", "pw");
        byte[] host = "api.luckgram.cc".getBytes(StandardCharsets.US_ASCII);
        byte[] expected = cat(b(5, 2, 0, 2), b(1, 1, 'u', 2, 'p', 'w'), b(5, 1, 0, 3, host.length), host, b(1, 0xBB));
        assertArrayEquals(expected, out.toByteArray());
    }

    @Test
    public void handshakeWithoutPassword() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Socks5Https.handshake(new ByteArrayInputStream(cat(b(5, 0), CONNECT_OK)), out, "a.b", 8443, "", "");
        assertArrayEquals(cat(b(5, 1, 0), b(5, 1, 0, 3, 3, 'a', '.', 'b', 0x20, 0xFB)), out.toByteArray());
    }

    @Test
    public void domainReplyIsConsumed() throws IOException {
        byte[] reply = b(5, 0, 0, 3, 3, 'x', 'y', 'z', 0, 80);
        Socks5Https.handshake(new ByteArrayInputStream(cat(b(5, 0), reply)), new ByteArrayOutputStream(), "a.b", 443, null, null);
    }

    @Test
    public void wrongPasswordFails() {
        expectFail(cat(b(5, 2), b(1, 1)), "u", "bad");
    }

    @Test
    public void passwordRequiredButMissingFails() {
        expectFail(b(5, 2), "", "");
    }

    @Test
    public void connectRefusedFails() {
        expectFail(cat(b(5, 0), b(5, 5, 0, 1, 0, 0, 0, 0, 0, 0)), "", "");
    }

    @Test
    public void notSocks5Fails() {
        expectFail(b('H', 'T'), "", "");
    }

    private static void expectFail(byte[] server, String user, String pass) {
        try {
            Socks5Https.handshake(new ByteArrayInputStream(server), new ByteArrayOutputStream(), "a.b", 443, user, pass);
            fail("expected IOException");
        } catch (IOException expected) {
        }
    }

    @Test
    public void plainBody() throws IOException {
        String r = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 11\r\n\r\n{\"ip\":\"1\"}\n";
        assertEquals("{\"ip\":\"1\"}\n", Socks5Https.body(r.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void chunkedBody() throws IOException {
        String r = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\n{\"ip\r\n6;x=1\r\n\":\"1\"}\r\n0\r\n\r\n";
        assertEquals("{\"ip\":\"1\"}", Socks5Https.body(r.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void non2xxFails() {
        try {
            Socks5Https.body("HTTP/1.1 429 Too Many Requests\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            fail();
        } catch (IOException expected) {
        }
        try {
            Socks5Https.body("garbage".getBytes(StandardCharsets.UTF_8));
            fail();
        } catch (IOException expected) {
        }
    }
}
