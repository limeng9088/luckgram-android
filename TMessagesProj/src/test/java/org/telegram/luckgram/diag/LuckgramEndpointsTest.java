package org.telegram.luckgram.diag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.SSLHandshakeException;

import org.junit.Test;

/** specs#81: several API entries, switch only on network failures, remember, probe, rewrite. */
public class LuckgramEndpointsTest {

    private static final String A = "https://api.luckgram.cc";
    private static final String B = "https://backup.luckgram.cc";

    private static LuckgramEndpoints ep(String remembered) {
        return new LuckgramEndpoints(Arrays.asList(A, B), remembered);
    }

    @Test
    public void parseKeepsOrderDropsJunk() {
        List<String> l = LuckgramEndpoints.parse(" https://api.luckgram.cc/ , http://insecure.luckgram.cc,https://backup.luckgram.cc https://api.luckgram.cc ");
        assertEquals(Arrays.asList(A, B), l);
        assertTrue(LuckgramEndpoints.parse(null).isEmpty());
        assertTrue(LuckgramEndpoints.parse("https://").isEmpty());
    }

    @Test
    public void startsWithFirstAndTriesOthersInOrder() {
        LuckgramEndpoints e = ep(null);
        assertEquals(A, e.current());
        assertEquals(Arrays.asList(A, B), e.attemptOrder());
        assertFalse(e.usingBackup());
    }

    @Test
    public void remembersWorkingEntry() {
        LuckgramEndpoints e = ep(null);
        assertTrue(e.markWorking(B));
        assertEquals(B, e.current());
        assertEquals(Arrays.asList(B, A), e.attemptOrder());
        assertFalse(e.markWorking(B));
        assertFalse(e.markWorking("https://other.luckgram.cc"));
        // persisted value comes back after a restart; unknown values fall back to the first
        assertEquals(B, ep(B + "/").current());
        assertEquals(A, ep("https://gone.luckgram.cc").current());
    }

    @Test
    public void onlyNetworkFailuresSwitch() {
        assertTrue(LuckgramEndpoints.isNetworkFailure(new UnknownHostException("api.luckgram.cc")));
        assertTrue(LuckgramEndpoints.isNetworkFailure(new ConnectException("refused")));
        assertTrue(LuckgramEndpoints.isNetworkFailure(new SocketException("Connection reset")));
        assertTrue(LuckgramEndpoints.isNetworkFailure(new SSLHandshakeException("reset")));
        assertTrue(LuckgramEndpoints.isNetworkFailure(new SocketTimeoutException("connect timed out")));
        assertTrue(LuckgramEndpoints.isNetworkFailure(new IOException("wrapped", new ConnectException("x"))));
        // an HTTP status is not an exception at all; other errors don't switch either
        assertFalse(LuckgramEndpoints.isNetworkFailure(new IOException("cancelled")));
        assertFalse(LuckgramEndpoints.isNetworkFailure(new IllegalStateException("HTTP 503")));
        assertFalse(LuckgramEndpoints.isNetworkFailure(null));
    }

    @Test
    public void probesFirstEntryOnlyWhileOnBackup() {
        LuckgramEndpoints e = ep(null);
        assertFalse(e.shouldProbe(1000));
        e.markWorking(B);
        assertTrue(e.shouldProbe(1000)); // cold start
        e.markProbed(1000);
        assertFalse(e.shouldProbe(1000 + LuckgramEndpoints.PROBE_INTERVAL_MS - 1));
        assertTrue(e.shouldProbe(1000 + LuckgramEndpoints.PROBE_INTERVAL_MS));
        e.markWorking(A);
        assertFalse(e.shouldProbe(1000 + 2 * LuckgramEndpoints.PROBE_INTERVAL_MS));
    }

    @Test
    public void rewritesServerUrlsToCurrentEntry() {
        LuckgramEndpoints e = ep(null);
        String img = A + "/v1/media/abc.jpg?sig=1";
        assertEquals(img, e.rewrite(img));
        e.markWorking(B);
        assertEquals(B + "/v1/media/abc.jpg?sig=1", e.rewrite(img));
        assertEquals(B + "/v1/x", e.rewrite("HTTPS://API.LUCKGRAM.CC/v1/x"));
        // only a whole host matches; other hosts unchanged
        assertEquals("https://api.luckgram.cc.evil.example.com/x", e.rewrite("https://api.luckgram.cc.evil.example.com/x"));
        assertEquals("https://media.luckgram.cc/a.jpg", e.rewrite("https://media.luckgram.cc/a.jpg"));
        assertNull(e.baseOf(null));
        assertEquals(A, e.baseOf(A));
    }

    @Test
    public void entryHosts() {
        LuckgramEndpoints e = ep(null);
        assertTrue(e.isEntryHost("API.luckgram.cc"));
        assertTrue(e.isEntryHost("backup.luckgram.cc"));
        assertFalse(e.isEntryHost("media.luckgram.cc"));
        assertEquals("api.luckgram.cc", LuckgramEndpoints.hostOf(A + ":443/v1"));
    }
}
