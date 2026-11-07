package org.telegram.luckgram.proxyexit;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * specs#117: a small HTTPS GET either directly (no proxy at all) or through one SOCKS5 proxy with
 * optional username/password (RFC 1928 / RFC 1929). The target host name is sent to the proxy, so
 * DNS happens at the proxy and the server sees the proxy's real exit.
 *
 * <p>Pure Java (no Android classes); the handshake and response parsing are unit-tested.
 */
public final class Socks5Https {

    private static final int MAX_RESPONSE = 64 * 1024;

    private Socks5Https() {
    }

    /** GET {@code url} bypassing every proxy; returns the body of a 2xx answer. */
    public static String getDirect(URL url, int timeoutMs) throws IOException {
        return getDirect(url, timeoutMs, MAX_RESPONSE, "Luckgram");
    }

    /** As {@link #getDirect(URL, int)} with a size limit and User-Agent (specs#118 subscriptions). */
    public static String getDirect(URL url, int timeoutMs, int maxBytes, String userAgent) throws IOException {
        HttpURLConnection c = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
        try {
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setUseCaches(false);
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("User-Agent", userAgent);
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new IOException("HTTP " + code);
            return new String(readAll(c.getInputStream(), maxBytes), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    /** GET https {@code url} through a SOCKS5 proxy; returns the body of a 2xx answer. */
    public static String getViaSocks5(String proxyHost, int proxyPort, String user, String password, URL url, int timeoutMs) throws IOException {
        return getViaSocks5(proxyHost, proxyPort, user, password, url, timeoutMs, MAX_RESPONSE, "Luckgram");
    }

    /** As above with a size limit and User-Agent (specs#118 subscriptions). */
    public static String getViaSocks5(String proxyHost, int proxyPort, String user, String password, URL url, int timeoutMs, int maxBytes, String userAgent) throws IOException {
        if (!"https".equalsIgnoreCase(url.getProtocol())) throw new IOException("https only");
        String host = url.getHost();
        int port = url.getPort() == -1 ? 443 : url.getPort();
        Socket raw = new Socket();
        try {
            raw.connect(new InetSocketAddress(proxyHost, proxyPort), timeoutMs);
            raw.setSoTimeout(timeoutMs);
            handshake(raw.getInputStream(), raw.getOutputStream(), host, port, user, password);
            SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, host, port, true);
            tls.setSoTimeout(timeoutMs);
            tls.startHandshake();
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, tls.getSession())) {
                tls.close();
                throw new IOException("certificate does not match " + host);
            }
            String file = url.getFile() == null || url.getFile().isEmpty() ? "/" : url.getFile();
            String req = "GET " + file + " HTTP/1.1\r\n"
                    + "Host: " + host + (port == 443 ? "" : ":" + port) + "\r\n"
                    + "Accept: */*\r\n"
                    + "User-Agent: " + userAgent + "\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream out = tls.getOutputStream();
            out.write(req.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            byte[] resp = readAll(tls.getInputStream(), maxBytes + 16 * 1024);
            tls.close();
            return body(resp);
        } finally {
            try {
                raw.close();
            } catch (IOException ignore) {
            }
        }
    }

    /** SOCKS5 greeting, optional user/password auth, and CONNECT to {@code host:port} by name. */
    static void handshake(InputStream rawIn, OutputStream out, String host, int port, String user, String password) throws IOException {
        DataInputStream in = new DataInputStream(rawIn);
        boolean auth = user != null && !user.isEmpty();
        out.write(auth ? new byte[]{5, 2, 0, 2} : new byte[]{5, 1, 0});
        out.flush();
        if (in.readUnsignedByte() != 5) throw new IOException("not a SOCKS5 proxy");
        int method = in.readUnsignedByte();
        if (method == 2) {
            if (!auth) throw new IOException("proxy wants a password");
            byte[] u = user.getBytes(StandardCharsets.UTF_8);
            byte[] p = (password == null ? "" : password).getBytes(StandardCharsets.UTF_8);
            if (u.length > 255 || p.length > 255) throw new IOException("credentials too long");
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            b.write(1);
            b.write(u.length);
            b.write(u);
            b.write(p.length);
            b.write(p);
            out.write(b.toByteArray());
            out.flush();
            in.readUnsignedByte();
            if (in.readUnsignedByte() != 0) throw new IOException("proxy rejected the username or password");
        } else if (method != 0) {
            throw new IOException("no acceptable SOCKS5 auth method");
        }
        byte[] h = host.getBytes(StandardCharsets.US_ASCII);
        if (h.length > 255) throw new IOException("host too long");
        ByteArrayOutputStream c = new ByteArrayOutputStream();
        c.write(new byte[]{5, 1, 0, 3});
        c.write(h.length);
        c.write(h);
        c.write((port >> 8) & 0xff);
        c.write(port & 0xff);
        out.write(c.toByteArray());
        out.flush();
        if (in.readUnsignedByte() != 5) throw new IOException("bad SOCKS5 reply");
        int rep = in.readUnsignedByte();
        in.readUnsignedByte();
        int atyp = in.readUnsignedByte();
        int len = atyp == 1 ? 4 : atyp == 4 ? 16 : atyp == 3 ? in.readUnsignedByte() : -1;
        if (len < 0) throw new IOException("bad SOCKS5 address type");
        in.readFully(new byte[len + 2]);
        if (rep != 0) throw new IOException("SOCKS5 connect failed (" + rep + ")");
    }

    /** Body of a raw HTTP/1.x response (plain or chunked); throws unless the status is 2xx. */
    static String body(byte[] resp) throws IOException {
        int split = indexOf(resp, new byte[]{'\r', '\n', '\r', '\n'});
        if (split < 0) throw new IOException("incomplete HTTP response");
        String head = new String(resp, 0, split, StandardCharsets.ISO_8859_1);
        String[] lines = head.split("\r\n");
        String[] status = lines[0].split(" ");
        if (status.length < 2 || !status[0].startsWith("HTTP/")) throw new IOException("not HTTP");
        int code;
        try {
            code = Integer.parseInt(status[1]);
        } catch (NumberFormatException e) {
            throw new IOException("bad HTTP status");
        }
        if (code / 100 != 2) throw new IOException("HTTP " + code);
        boolean chunked = false;
        for (int i = 1; i < lines.length; i++) {
            String l = lines[i].toLowerCase(Locale.ROOT);
            if (l.startsWith("transfer-encoding:") && l.contains("chunked")) chunked = true;
        }
        int start = split + 4;
        if (!chunked) return new String(resp, start, resp.length - start, StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int p = start;
        while (true) {
            int eol = indexOf(resp, new byte[]{'\r', '\n'}, p);
            if (eol < 0) throw new IOException("bad chunk");
            String sz = new String(resp, p, eol - p, StandardCharsets.ISO_8859_1);
            int semi = sz.indexOf(';');
            if (semi >= 0) sz = sz.substring(0, semi);
            int n;
            try {
                n = Integer.parseInt(sz.trim(), 16);
            } catch (NumberFormatException e) {
                throw new IOException("bad chunk size");
            }
            if (n == 0) break;
            p = eol + 2;
            if (p + n > resp.length) throw new IOException("truncated chunk");
            out.write(resp, p, n);
            p += n + 2;
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static int indexOf(byte[] a, byte[] pat) {
        return indexOf(a, pat, 0);
    }

    private static int indexOf(byte[] a, byte[] pat, int from) {
        outer:
        for (int i = Math.max(0, from); i <= a.length - pat.length; i++) {
            for (int j = 0; j < pat.length; j++) {
                if (a[i + j] != pat[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        try {
            while ((n = in.read(buf)) != -1) {
                b.write(buf, 0, n);
                if (b.size() > max) throw new IOException("response too large");
            }
        } catch (EOFException ignore) {
        } catch (IOException e) {
            // Some servers drop TLS without close_notify after "Connection: close"; keep what arrived.
            if (b.size() == 0) throw e;
        }
        return b.toByteArray();
    }
}
