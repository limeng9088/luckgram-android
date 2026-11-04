/*
 * Luckgram — spec 01 A: translating received messages inside the bubble.
 * Licensed under GNU GPL v. 2 or later.
 */
package org.telegram.luckgram.translatein;

import java.util.Collection;
import java.util.Locale;

/** Pure rules of spec 01 A, free of android.* / org.telegram.* so they run as plain JVM tests. */
public final class TranslateInLogic {

    /** Feature switch (spec 01 「开关」): received-message translation incl. the one-tap button. */
    public static final String FEATURE_KEY = "translate_in";
    /** LuckgramSettings key of the target language; empty = the app language. */
    public static final String KEY_TO_LANG = "translate_in_to_lang";
    /** specs#105: per-account prefs key prefix of a chat's own target language (overrides the default). */
    public static final String KEY_CHAT_LANG_PREFIX = "to_lang_";

    private TranslateInLogic() {
    }

    /** Language code reduced to what decides "same language": lower case, no region; every zh-* is zh. */
    public static String base(String lang) {
        if (lang == null) {
            return null;
        }
        String s = lang.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (s.isEmpty() || s.equals("und")) {
            return null;
        }
        final int dash = s.indexOf('-');
        if (dash > 0) {
            s = s.substring(0, dash);
        }
        if (s.equals("nb") || s.equals("nn")) {
            return "no";
        }
        return s;
    }

    /** Whether {@code lang} is one of the "don't translate" languages (compared by {@link #base}). */
    public static boolean isExcluded(String lang, Collection<String> doNotTranslate) {
        final String b = base(lang);
        if (b == null || doNotTranslate == null) {
            return false;
        }
        for (String d : doNotTranslate) {
            if (b.equals(base(d))) {
                return true;
            }
        }
        return false;
    }

    /**
     * spec 01 A.2/A.3: the one-tap translate button shows next to someone else's message with text
     * whose detected language is known and not a "don't translate" language (nor the target).
     */
    public static boolean showButton(boolean featureOn, boolean outgoing, boolean hasText, String detectedLang,
                                     Collection<String> doNotTranslate, String toLang) {
        if (!featureOn || outgoing || !hasText) {
            return false;
        }
        final String b = base(detectedLang);
        if (b == null) {
            return false;
        }
        if (b.equals(base(toLang))) {
            return false;
        }
        return !isExcluded(b, doNotTranslate);
    }

    /**
     * The target language the user chose, or null when they have not chosen yet (specs#93: no
     * default from the app / system language; the first translation asks).
     */
    public static String targetLanguage(String chosen) {
        if (chosen != null && !chosen.trim().isEmpty()) {
            return chosen.trim();
        }
        return null;
    }

    /**
     * specs#105: the language a chat translates into — its own choice, else the default, else null
     * (nothing chosen yet: the first translation asks).
     */
    public static String effectiveLanguage(String chatChosen, String defaultChosen) {
        final String chat = targetLanguage(chatChosen);
        return chat != null ? chat : targetLanguage(defaultChosen);
    }

    /** specs#105: the language button next to a translation's header, e.g. 「🌐 中文 ▾」. */
    public static String languageButton(String toName) {
        return "\uD83C\uDF10 " + toName + " \u25BE";
    }

    /** spec 01 A.1: 「已翻译 · 英语 → 中文」 above the separator; without a known source just the target. */
    public static String header(String fromName, String toName, boolean chinese) {
        final boolean hasFrom = fromName != null && !fromName.isEmpty();
        if (chinese) {
            return hasFrom ? "已翻译 · " + fromName + " → " + toName : "已翻译 · " + toName;
        }
        return hasFrom ? "Translated · " + fromName + " → " + toName : "Translated · " + toName;
    }

    /** Long-press menu label: 「翻译」, or 「撤销翻译」 when the message shows a translation. */
    public static String menuLabel(boolean translated, boolean chinese) {
        if (translated) {
            return chinese ? "撤销翻译" : "Undo translation";
        }
        return chinese ? "翻译" : "Translate";
    }

    /**
     * Key of a message's translation state: per account, chat, message and text, so an edited
     * message loses its (stale) translation.
     */
    public static String stateKey(int account, long dialogId, int messageId, String textKey) {
        return account + ":" + dialogId + ":" + messageId + ":" + (textKey == null ? "" : textKey);
    }

    /** The "from>to" languages kept with a saved translation. */
    public static String encodeLanguages(String from, String to) {
        return (from == null ? "" : from) + ">" + (to == null ? "" : to);
    }

    /** Inverse of {@link #encodeLanguages}: {from, to}, each null when empty. */
    public static String[] decodeLanguages(String value) {
        if (value == null) {
            return new String[]{null, null};
        }
        final int gt = value.indexOf('>');
        final String from = gt < 0 ? value : value.substring(0, gt);
        final String to = gt < 0 ? "" : value.substring(gt + 1);
        return new String[]{from.isEmpty() ? null : from, to.isEmpty() ? null : to};
    }
}
