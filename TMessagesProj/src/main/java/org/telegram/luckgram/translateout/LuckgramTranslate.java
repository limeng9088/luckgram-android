/*
 * Luckgram — translation fallback (specs#82). Licensed under GNU GPL v. 2 or later.
 */
package org.telegram.luckgram.translateout;

import android.content.Context;

import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramAIError;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.luckgram.diag.LuckgramNet;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * When Telegram's messages.translateText fails (any error, TRANSLATIONS_DISABLED_ALT, timeout or an
 * empty result), the text is translated by Luckgram AI instead. Used by translate-before-sending,
 * and the long-press "Translate" sheet (spec 01). Replaces Telegram's Google web fallback, which is
 * unreachable for many users. Results are cached (spec 01 「翻译服务」5).
 */
public final class LuckgramTranslate {

    public interface Done {
        /** On the UI thread: {@code text} on success, otherwise {@code error} (never both null). */
        void run(String text, LuckgramAIError error);
    }

    private static final String CACHE_FILE = "luckgram_translate_cache.json";
    private static final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LuckgramTranslateCache");
        t.setDaemon(true);
        return t;
    });
    private static volatile TranslateCache cache;
    private static boolean saveScheduled;

    private LuckgramTranslate() {
    }

    /** spec 01 「翻译服务」5: the shared result cache (2000 entries, 7 days), loaded on first use. */
    public static TranslateCache cache() {
        TranslateCache c = cache;
        if (c == null) {
            synchronized (LuckgramTranslate.class) {
                c = cache;
                if (c == null) {
                    String json = null;
                    try {
                        final File f = new File(ApplicationLoader.getFilesDirFixed(), CACHE_FILE);
                        if (f.exists()) {
                            json = new String(readAll(f), StandardCharsets.UTF_8);
                        }
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                    c = TranslateCache.parse(json, System.currentTimeMillis());
                    cache = c;
                }
            }
        }
        return c;
    }

    /** Stores a translation and writes the cache file a little later (batched). */
    public static void remember(String toLang, String text, String translated, String fromLang) {
        cache().put(TranslateCache.key(toLang, text), translated, fromLang, System.currentTimeMillis());
        synchronized (LuckgramTranslate.class) {
            if (saveScheduled) {
                return;
            }
            saveScheduled = true;
        }
        io.execute(() -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignore) {
            }
            synchronized (LuckgramTranslate.class) {
                saveScheduled = false;
            }
            try {
                final TranslateCache c = cache();
                c.prune(System.currentTimeMillis());
                final File dir = ApplicationLoader.getFilesDirFixed();
                final File tmp = new File(dir, CACHE_FILE + ".tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(c.serialize().getBytes(StandardCharsets.UTF_8));
                }
                if (!tmp.renameTo(new File(dir, CACHE_FILE))) {
                    tmp.delete();
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    /** The cached translation of {@code text} into {@code toLang}, or null. */
    public static TranslateCache.Entry cached(String toLang, String text) {
        return cache().get(TranslateCache.key(toLang, text), System.currentTimeMillis());
    }

    private static byte[] readAll(File f) throws java.io.IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            final byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** Records why Telegram's translation failed in the specs#72 diagnostic log. */
    public static void logTelegramFailure(String source, TLRPC.TL_error err, boolean timedOut) {
        final String text = TranslateFallbackLogic.telegramFailure(err != null ? err.text : null, timedOut);
        FileLog.d("luckgram translate: telegram failed, source=" + source + " error=" + text + " -> Luckgram AI");
        LuckgramNet.logTelegram(source, "messages.translateText", err != null ? err.code : 0, text);
    }

    /** Records a Telegram failure that is not a TL_error, e.g. {@link TranslateFallbackLogic#NO_CONNECTION}. */
    public static void logTelegramFailure(String source, String reason) {
        FileLog.d("luckgram translate: telegram failed, source=" + source + " error=" + reason + " -> Luckgram AI");
        LuckgramNet.logTelegram(source, "messages.translateText", 0, reason);
    }

    /**
     * Translates {@code text} with Luckgram AI. The server request is logged by LuckgramAI itself
     * (feature {@code translate}), so the diagnostic log shows the fallback's outcome too.
     */
    public static LuckgramAI.Request viaAi(String source, String text, String toLang, Done done) {
        final String lang = TranslateFallbackLogic.aiLanguage(toLang);
        if (text == null || text.trim().isEmpty() || lang == null) {
            done.run(null, new LuckgramAIError(LuckgramAIError.INVALID_REQUEST, 0));
            return null;
        }
        final TranslateCache.Entry hit = cached(lang, text);
        if (hit != null) {
            done.run(hit.text, null);
            return null;
        }
        return LuckgramAI.getInstance().translate(consentContext(), text, lang, null, new LuckgramAI.Callback<LuckgramAI.Translation>() {
            @Override
            public void onResult(LuckgramAI.Translation result) {
                FileLog.d("luckgram translate: Luckgram AI ok, source=" + source);
                remember(lang, text, result.text, TranslateFallbackLogic.aiLanguage(result.fromLang));
                done.run(result.text, null);
            }

            @Override
            public void onError(LuckgramAIError error) {
                FileLog.d("luckgram translate: Luckgram AI failed, source=" + source + " code=" + error.code);
                done.run(null, error);
            }
        });
    }

    /** The message to show when Telegram and Luckgram AI both failed. */
    public static String failureMessage(LuckgramAIError error) {
        return TranslateFallbackLogic.failureMessage(
            TranslateFallbackLogic.failureKind(error != null ? error.code : null), LuckgramStrings.isChinese());
    }

    /** Activity for the one-time AI consent dialog; without one LuckgramAI reports consent_denied. */
    private static Context consentContext() {
        final BaseFragment f = LaunchActivity.getSafeLastFragment();
        if (f != null && f.getParentActivity() != null) {
            return f.getParentActivity();
        }
        return LaunchActivity.instance;
    }
}
