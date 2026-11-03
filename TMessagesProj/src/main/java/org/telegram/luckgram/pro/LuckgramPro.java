package org.telegram.luckgram.pro;

import org.json.JSONObject;
import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramAIError;
import org.telegram.luckgram.LuckgramSettings;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * specs#87 (29): the one place that says whether the user has Luckgram Pro. Every Pro check goes
 * through {@link #isPro()}; perks, AI credits and limits come from the same {@link ProState}.
 *
 * <p>Refreshed from {@code GET /v1/me} at launch / back to foreground ({@link #refreshIfStale()}),
 * after a payment and on pull-to-refresh ({@link #refresh()}). The last answer is cached, so offline
 * the user stays Pro until the cached {@code expires_at}. Listeners run on the UI thread when what
 * a user would see changes. No AI consent is asked: nothing of the user's is sent.
 */
public final class LuckgramPro {

    private static final String KEY_CACHE = "_pro_state";
    /** Back to foreground more often than this doesn't ask the server again. */
    static final long MIN_REFRESH_INTERVAL_MS = 60_000;

    public interface Listener {
        void onProStateChanged(ProState state);
    }

    private static volatile LuckgramPro instance;

    public static LuckgramPro getInstance() {
        if (instance == null) {
            synchronized (LuckgramPro.class) {
                if (instance == null) {
                    instance = new LuckgramPro();
                }
            }
        }
        return instance;
    }

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile ProState state;
    private volatile boolean inFlight;
    private volatile long lastRequestMs;

    private LuckgramPro() {
        ProState cached;
        try {
            cached = ProState.fromCache(LuckgramSettings.getString(KEY_CACHE, null));
        } catch (Throwable e) {
            cached = ProState.free();
        }
        state = cached;
    }

    /** Shortcut for {@code getInstance().isProNow()}. */
    public static boolean isPro() {
        return getInstance().isProNow();
    }

    public boolean isProNow() {
        return state.isPro(System.currentTimeMillis());
    }

    public ProState state() {
        return state;
    }

    public boolean hasPerk(String perk) {
        return state.hasPerk(perk);
    }

    public int credits() {
        return state.credits(System.currentTimeMillis());
    }

    public void addListener(Listener l) {
        if (l != null) listeners.addIfAbsent(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Launch / back to foreground: asks the server unless it did within the last minute. */
    public void refreshIfStale() {
        if (System.currentTimeMillis() - lastRequestMs < MIN_REFRESH_INTERVAL_MS) return;
        refresh();
    }

    /** Asks the server now (after a payment, pull-to-refresh). One request at a time. */
    public void refresh() {
        if (inFlight) return;
        inFlight = true;
        lastRequestMs = System.currentTimeMillis();
        try {
            LuckgramAI.getInstance().requestJson("GET", "/v1/me", null, new LuckgramAI.Callback<JSONObject>() {
                @Override
                public void onResult(JSONObject result) {
                    inFlight = false;
                    update(ProState.parse(result, System.currentTimeMillis()));
                }

                @Override
                public void onError(LuckgramAIError error) {
                    // keep the cached state (offline: Pro until the cached expiry)
                    inFlight = false;
                    FileLog.e("luckgram pro: " + (error != null ? error.code : "?"));
                }
            });
        } catch (Throwable e) {
            inFlight = false;
            FileLog.e(e);
        }
    }

    private void update(ProState next) {
        ProState prev = state;
        state = next;
        try {
            LuckgramSettings.setString(KEY_CACHE, next.toCache());
        } catch (Throwable ignore) {
        }
        if (next.sameAs(prev)) return;
        AndroidUtilities.runOnUIThread(() -> {
            for (Listener l : listeners) {
                try {
                    l.onProStateChanged(next);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
        });
    }
}
