/*
 * Luckgram — translation fallback (specs#82). Licensed under GNU GPL v. 2 or later.
 */
package org.telegram.luckgram.translateout;

import android.content.Context;

import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramAIError;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.luckgram.diag.LuckgramNet;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LaunchActivity;

/**
 * When Telegram's messages.translateText fails (any error, TRANSLATIONS_DISABLED_ALT, timeout or an
 * empty result), the text is translated by Luckgram AI instead. Used by translate-before-sending,
 * the long-press "Translate" sheet and "Translate entire chat". Replaces Telegram's Google web
 * fallback, which is unreachable for many users.
 */
public final class LuckgramTranslate {

    public interface Done {
        /** On the UI thread: {@code text} on success, otherwise {@code error} (never both null). */
        void run(String text, LuckgramAIError error);
    }

    private LuckgramTranslate() {
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
        return LuckgramAI.getInstance().translate(consentContext(), text, lang, new LuckgramAI.Callback<String>() {
            @Override
            public void onResult(String result) {
                FileLog.d("luckgram translate: Luckgram AI ok, source=" + source);
                done.run(result, null);
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
