/*
 * Luckgram — translation fallback (specs#82): when Telegram's messages.translateText fails, the
 * text is translated by the Luckgram API (/v1/ai/chat task=translate) instead of Google's web
 * translator, which is unreachable for many users. Licensed under GNU GPL v. 2 or later.
 */
package org.telegram.luckgram.translateout;

import java.util.Locale;

/** Pure rules of the fallback, free of android.* / org.telegram.* so they run as plain JVM tests. */
public final class TranslateFallbackLogic {

    /** Diagnostic-log feature names, one per entry point (specs#82 §1). */
    public static final String SOURCE_OUT = "translate_out";
    public static final String SOURCE_MESSAGE = "translate_message";
    public static final String SOURCE_CHAT = "translate_chat";

    /** Telegram gets this long before we stop waiting and fall back (spec 01 「翻译服务」2). */
    public static final long TELEGRAM_TIMEOUT_MS = 8_000;

    /** Logged instead of a Telegram error text when no error came. */
    public static final String TIMEOUT = "TIMEOUT";
    public static final String EMPTY_RESULT = "EMPTY_RESULT";
    /** Telegram had no connection, so it was not asked at all. */
    public static final String NO_CONNECTION = "NO_CONNECTION";

    /** Why the fallback failed too, for the message shown to the user (specs#82 §3). */
    public static final int FAIL_OTHER = 0;
    public static final int FAIL_NETWORK = 1;
    public static final int FAIL_QUOTA = 2;
    public static final int FAIL_TOO_LONG = 3;
    public static final int FAIL_CONSENT = 4;

    private TranslateFallbackLogic() {
    }

    /**
     * Whether Luckgram AI should translate instead. Every Telegram failure counts — an error of any
     * kind (TRANSLATIONS_DISABLED_ALT, FLOOD_WAIT_x, TO_LANG_INVALID…), a timeout or an empty
     * result; a usable Telegram result never does, so no AI quota is spent while Telegram works.
     */
    public static boolean shouldFallback(boolean telegramGaveText) {
        return !telegramGaveText;
    }

    /** What the diagnostic log records for Telegram's failure. */
    public static String telegramFailure(String errorText, boolean timedOut) {
        if (timedOut) {
            return TIMEOUT;
        }
        if (errorText == null || errorText.trim().isEmpty()) {
            return EMPTY_RESULT;
        }
        return errorText.trim();
    }

    /**
     * The {@code to_lang} sent to the server: an ISO 639-1 (or 639-2) code, with the script / region
     * only where it changes the written language (zh-Hant, pt-BR). Accepts Telegram / Android forms
     * such as {@code zh_TW}, {@code zh-hans}, {@code nb}, {@code pt_br}. Null when unusable.
     */
    public static String aiLanguage(String lng) {
        if (lng == null) {
            return null;
        }
        String s = lng.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (s.isEmpty() || s.equals("und")) {
            return null;
        }
        final int dash = s.indexOf('-');
        final String base = dash > 0 ? s.substring(0, dash) : s;
        final String rest = dash > 0 ? s.substring(dash + 1) : "";
        if (base.length() < 2 || base.length() > 3) {
            return null;
        }
        for (int i = 0; i < base.length(); i++) {
            final char c = base.charAt(i);
            if (c < 'a' || c > 'z') {
                return null;
            }
        }
        switch (base) {
            case "zh":
                return rest.equals("hant") || rest.startsWith("hant-") || rest.equals("tw") || rest.equals("hk") || rest.equals("mo") ? "zh-Hant" : "zh";
            case "pt":
                return rest.equals("br") ? "pt-BR" : "pt";
            case "nb":
            case "nn":
                return "no";
            case "iw":
                return "he";
            case "in":
                return "id";
            default:
                return base;
        }
    }

    /** Maps the Luckgram API error code of the failed fallback to a FAIL_* kind. */
    public static int failureKind(String aiErrorCode) {
        if (aiErrorCode == null) {
            return FAIL_OTHER;
        }
        switch (aiErrorCode) {
            case "network_error":
                return FAIL_NETWORK;
            case "quota_exceeded":
                return FAIL_QUOTA;
            case "input_too_large":
                return FAIL_TOO_LONG;
            case "consent_denied":
                return FAIL_CONSENT;
            default:
                return FAIL_OTHER;
        }
    }

    /**
     * The message shown when both Telegram and Luckgram AI failed (spec 01 「翻译服务」4). Declining the
     * AI consent means "Telegram only", so it gets the generic message.
     */
    public static String failureMessage(int kind, boolean chinese) {
        switch (kind) {
            case FAIL_NETWORK:
                return chinese ? "网络不通，请检查网络或代理" : "No connection. Please check your network or proxy.";
            case FAIL_QUOTA:
                return chinese ? "今日翻译次数已用完，明天再来" : "You've used all of today's translations. Please come back tomorrow.";
            case FAIL_TOO_LONG:
                return chinese ? "这段文字太长，无法翻译" : "This text is too long to translate.";
            default:
                return chinese ? "翻译失败，请稍后再试" : "Translation failed. Please try again later.";
        }
    }
}
