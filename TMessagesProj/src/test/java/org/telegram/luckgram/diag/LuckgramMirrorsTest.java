package org.telegram.luckgram.diag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;

import org.junit.Test;

/** specs#160: backup hosts for the website, help center and package downloads. */
public class LuckgramMirrorsTest {

    private static final String LIST = "luckgram.cc=lg-www.cat158.com,www.luckgram.cc=lg-www.cat158.com,"
            + "wiki.luckgram.cc=lg-wiki.cat158.com,dl.luckgram.cc=lg-dl.cat158.com,web.luckgram.cc=lgweb.cat158.com";

    private static final LuckgramMirrors M = LuckgramMirrors.parse(LIST);

    @Test
    public void parse() {
        assertEquals(5, M.hosts().size());
        assertEquals("lgweb.cat158.com", M.hosts().get("web.luckgram.cc"));
        assertEquals("lg-dl.cat158.com", M.hosts().get("dl.luckgram.cc"));
        // junk is skipped, case folded, whitespace allowed
        LuckgramMirrors j = LuckgramMirrors.parse(" WIKI.Luckgram.cc=LG-WIKI.cat158.com  x=, =y, a.b=a.b, bad host=x.y, https://a.b=c.d ");
        assertEquals(Collections.singletonMap("wiki.luckgram.cc", "lg-wiki.cat158.com"), j.hosts());
        assertTrue(LuckgramMirrors.parse(null).hosts().isEmpty());
        assertTrue(LuckgramMirrors.parse("").hosts().isEmpty());
    }

    @Test
    public void builtInListMatchesTheSpec() throws Exception {
        // the list compiled into the app (gradle.properties -> BuildConfig.LUCKGRAM_SITE_MIRRORS)
        Properties p = new Properties();
        java.io.File f = new java.io.File("../gradle.properties");
        if (!f.isFile()) f = new java.io.File("gradle.properties");
        try (InputStream in = new FileInputStream(f)) {
            p.load(in);
        }
        LuckgramMirrors built = LuckgramMirrors.parse(p.getProperty("LUCKGRAM_SITE_MIRRORS"));
        assertEquals(M.hosts(), built.hosts());
    }

    @Test
    public void mirror() {
        assertEquals("https://lg-dl.cat158.com/android/android-13.0.3-379/luckgram-13.0.3-arm64-v8a.apk",
                M.mirror("https://dl.luckgram.cc/android/android-13.0.3-379/luckgram-13.0.3-arm64-v8a.apk"));
        assertEquals("https://lg-www.cat158.com/privacy-policy/", M.mirror("https://luckgram.cc/privacy-policy/"));
        assertEquals("https://lg-www.cat158.com/en/?a=1#top", M.mirror("https://www.luckgram.cc/en/?a=1#top"));
        assertEquals("https://lg-wiki.cat158.com/getting-started/install.html", M.mirror("https://WIKI.luckgram.cc/getting-started/install.html"));
        // web version (specs#160 follow-up): deep links keep path, query and fragment
        assertEquals("https://lgweb.cat158.com/#@luckgram", M.mirror("https://web.luckgram.cc/#@luckgram"));
        assertEquals("https://lgweb.cat158.com/k/?tgaddr=x", M.mirror("https://web.luckgram.cc/k/?tgaddr=x"));
        assertEquals("https://lg-www.cat158.com", M.mirror("https://luckgram.cc"));
        assertEquals("https://lg-www.cat158.com?x", M.mirror("https://luckgram.cc?x"));
        assertEquals("https://lg-www.cat158.com/", M.mirror("HTTPS://luckgram.cc./"));
        // not mirrored: other hosts, the API (specs#81 has its own entries), http, look-alikes, user info, ports
        assertNull(M.mirror("https://api.luckgram.cc/v1/version"));
        assertNull(M.mirror("https://telegram.org/faq"));
        assertNull(M.mirror("http://luckgram.cc/"));
        assertNull(M.mirror("https://luckgram.cc.example.com/"));
        assertNull(M.mirror("https://user@luckgram.cc/"));
        assertNull(M.mirror("https://luckgram.cc:8443/"));
        assertNull(M.mirror("https://lg-www.cat158.com/"));
        assertNull(M.mirror(null));
        assertNull(M.mirror(""));
    }

    @Test
    public void forSite() {
        String wiki = "https://wiki.luckgram.cc/faq/";
        assertEquals(wiki, M.forSite(wiki, false));
        assertEquals("https://lg-wiki.cat158.com/faq/", M.forSite(wiki, true));
        assertEquals("https://t.me/luckgram", M.forSite("https://t.me/luckgram", true));
        assertEquals("https://web.luckgram.cc/", M.forSite("https://web.luckgram.cc/", false));
        assertEquals("https://lgweb.cat158.com/", M.forSite("https://web.luckgram.cc/", true));
        assertNull(M.forSite(null, true));
    }

    @Test
    public void downloadUrls() {
        String dl = "https://dl.luckgram.cc/android/android-13.0.3-379/a.apk";
        String bk = "https://lg-dl.cat158.com/android/android-13.0.3-379/a.apk";
        assertEquals(Arrays.asList(dl, bk), M.downloadUrls(dl, false));
        // API already on its backup entry: that network blocks luckgram.cc, go to the backup first
        assertEquals(Arrays.asList(bk, dl), M.downloadUrls(dl, true));
        // a URL without a backup host is tried alone
        String gh = "https://example.com/releases/download/x/a.apk";
        assertEquals(Collections.singletonList(gh), M.downloadUrls(gh, false));
        assertEquals(Collections.singletonList(gh), M.downloadUrls(gh, true));
    }
}
