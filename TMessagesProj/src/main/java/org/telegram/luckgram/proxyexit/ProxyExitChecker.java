package org.telegram.luckgram.proxyexit;

import android.os.SystemClock;

import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.utils.proxy.ProxySettings;

import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * specs#117: checks and caches the exit IP / country of the direct connection and of each SOCKS5
 * proxy for the proxy list. All state is touched on the UI thread only; network work runs on a
 * small pool (at most 4 at once). Nothing is stored on disk or logged.
 */
public final class ProxyExitChecker {

    public static final String DIRECT = "direct";
    private static final int TIMEOUT_MS = 10_000;

    public static final class Entry {
        public ExitInfo info;
        public boolean checking;
        public boolean failed;
        long checkedAt;
    }

    private static final HashMap<String, Entry> cache = new HashMap<>();
    private static final HashMap<String, List<Runnable>> waiting = new HashMap<>();
    private static ExecutorService pool;

    private ProxyExitChecker() {
    }

    /** True when the exit of this proxy can be checked (SOCKS5 only; MTProto cannot carry HTTP). */
    public static boolean supports(ProxySettings s) {
        return s != null && s.getType() == ProxySettings.Type.SOCKS5 && !s.getAddress().isEmpty() && s.getPort() > 0;
    }

    /** Cache key; includes the credentials' hash so an edited proxy is checked again. */
    public static String key(ProxySettings s) {
        if (!supports(s)) return null;
        return "s5:" + s.getAddress().toLowerCase(Locale.ROOT) + ":" + s.getPort() + ":" + s.getUser() + ":" + s.getPassword().hashCode();
    }

    public static Entry get(String key) {
        return key == null ? null : cache.get(key);
    }

    /**
     * Starts a check unless a fresh result or a running check exists. {@code proxy} null means the
     * direct connection. {@code onDone} runs on the UI thread when this key's check finishes.
     */
    public static void check(String key, ProxySettings proxy, boolean force, Runnable onDone) {
        if (key == null) return;
        Entry e = cache.get(key);
        long now = SystemClock.elapsedRealtime();
        if (e != null && e.checking) {
            if (onDone != null) addWaiter(key, onDone);
            return;
        }
        if (e != null && !force && !e.failed && ExitInfo.isFresh(e.checkedAt, now)) {
            return;
        }
        if (e == null) {
            e = new Entry();
            cache.put(key, e);
        }
        e.checking = true;
        if (onDone != null) addWaiter(key, onDone);
        final Entry entry = e;
        final String base = LuckgramAI.apiBaseUrl();
        executor().execute(() -> {
            ExitInfo info = null;
            try {
                URL url = new URL(base + ExitInfo.PATH);
                String body = proxy == null
                        ? Socks5Https.getDirect(url, TIMEOUT_MS)
                        : Socks5Https.getViaSocks5(proxy.getAddress(), proxy.getPort(), proxy.getUser(), proxy.getPassword(), url, TIMEOUT_MS);
                info = ExitInfo.parse(body);
            } catch (Throwable ignore) {
            }
            final ExitInfo result = info;
            AndroidUtilities.runOnUIThread(() -> {
                entry.checking = false;
                entry.info = result;
                entry.failed = result == null;
                entry.checkedAt = SystemClock.elapsedRealtime();
                List<Runnable> w = waiting.remove(key);
                if (w != null) {
                    for (Runnable r : w) r.run();
                }
            });
        });
    }

    private static void addWaiter(String key, Runnable r) {
        List<Runnable> l = waiting.get(key);
        if (l == null) {
            l = new ArrayList<>();
            waiting.put(key, l);
        }
        l.add(r);
    }

    private static synchronized ExecutorService executor() {
        if (pool == null) pool = Executors.newFixedThreadPool(4);
        return pool;
    }

    /** Text for a row: checking / failed / "🇸🇬 新加坡 · 1.2.3.4"; "" when never checked. */
    public static String text(Entry e) {
        if (e == null) return "";
        if (e.checking && e.info == null) return checkingText();
        if (e.info != null) return exitPrefix() + e.info.label(locale());
        if (e.failed) return failedText();
        return "";
    }

    /** Country names follow the Luckgram language rule: Chinese or English. */
    static Locale locale() {
        return LuckgramStrings.isChinese() ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH;
    }

    public static String directTitle() {
        return LuckgramStrings.get("直连（不使用代理）", "Direct (no proxy)");
    }

    public static String exitPrefix() {
        return LuckgramStrings.get("出口：", "Exit: ");
    }

    public static String checkingText() {
        return LuckgramStrings.get("正在检测出口…", "Checking exit…");
    }

    public static String failedText() {
        return LuckgramStrings.get("出口检测失败", "Exit check failed");
    }
}
