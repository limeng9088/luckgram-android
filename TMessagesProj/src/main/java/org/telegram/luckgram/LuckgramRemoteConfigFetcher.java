package org.telegram.luckgram;

import org.json.JSONObject;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.FileLog;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fetches {@code GET /v1/config} (task S4) into {@link LuckgramSettings#mergeRemoteConfig}.
 *
 * <ul>
 *   <li>Runs when the app comes to the foreground and the cached config is older than its
 *       {@code ttl} ({@link LuckgramSettings#isRemoteConfigExpired()}); sends {@code If-None-Match}
 *       with {@link LuckgramSettings#remoteConfigVersion()}, so an unchanged config is a 304.</li>
 *   <li>A failed fetch (offline, server down) keeps the cached config and is retried after
 *       {@link LuckgramRemoteConfigLogic#RETRY_AFTER_FAILURE_SECONDS}; without a cache every feature
 *       uses its built-in default.</li>
 *   <li>Only registers the anonymous device (platform, version, locale); no user content is sent,
 *       so no AI consent is needed.</li>
 * </ul>
 */
public final class LuckgramRemoteConfigFetcher {

    private static final String APP_VERSION_HEADER = "X-Luckgram-App-Version";

    private static final AtomicBoolean inFlight = new AtomicBoolean();
    private static volatile long lastFailureMs;

    private LuckgramRemoteConfigFetcher() {
    }

    /** Refreshes in the background if the cached config has expired. */
    public static void refreshIfStale() {
        LuckgramRemoteConfig cached = LuckgramSettings.remoteConfig();
        long ttl = cached.ttlSeconds > 0 ? cached.ttlSeconds : LuckgramRemoteConfigLogic.DEFAULT_TTL_SECONDS;
        if (!LuckgramRemoteConfigLogic.isStale(System.currentTimeMillis(), cached.fetchedAt, ttl, lastFailureMs)) {
            return;
        }
        refresh();
    }

    /** Refreshes in the background now (debug page / tests). */
    public static void refresh() {
        String base = LuckgramAI.apiBaseUrl();
        if (base == null || base.isEmpty() || !inFlight.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                fetch();
                lastFailureMs = 0;
            } catch (Throwable e) {
                lastFailureMs = System.currentTimeMillis();
                FileLog.e("luckgram remote config: " + e.getMessage());
            } finally {
                inFlight.set(false);
            }
        }, "LuckgramRemoteConfig");
        t.setDaemon(true);
        t.start();
    }

    private static void fetch() throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put(APP_VERSION_HEADER, BuildConfig.BUILD_VERSION_STRING);
        String version = LuckgramSettings.remoteConfigVersion();
        if (version != null && !version.isEmpty()) {
            headers.put("If-None-Match", "\"" + version + "\"");
        }
        try (LuckgramAI.HttpResponse res = LuckgramAI.getInstance().getBlocking("/v1/config", headers)) {
            if (res.status == 304) {
                LuckgramSettings.touchRemoteConfig(); // unchanged: restart the TTL only
                return;
            }
            if (res.status != 200) {
                throw new IllegalStateException("HTTP " + res.status);
            }
            LuckgramSettings.mergeRemoteConfig(new JSONObject(LuckgramAI.readString(res.body)));
        }
    }
}
