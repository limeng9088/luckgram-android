/*
 * Luckgram: UI strings for "translate before sending", in Chinese and English.
 *
 * Telegram's own language packs have no official Chinese, so these strings follow the system
 * language (or a Chinese in-app language) instead of relying on server-side packs.
 */
package org.telegram.luckgram.translateout;

import org.telegram.messenger.LocaleController;

import java.util.Locale;

public final class TranslateOutStrings {

    private TranslateOutStrings() {
    }

    public static boolean isChinese() {
        try {
            final LocaleController.LocaleInfo info = LocaleController.getInstance().getCurrentLocaleInfo();
            if (info != null && info.pluralLangCode != null && info.pluralLangCode.toLowerCase(Locale.ROOT).startsWith("zh")) {
                return true;
            }
            final Locale system = LocaleController.getInstance().getSystemDefaultLocale();
            return system != null && "zh".equalsIgnoreCase(system.getLanguage());
        } catch (Exception e) {
            return "zh".equalsIgnoreCase(Locale.getDefault().getLanguage());
        }
    }

    private static String s(String zh, String en) {
        return isChinese() ? zh : en;
    }

    public static String button() {
        return s("翻译", "Translate");
    }

    public static String translatedTo(String languageName) {
        return isChinese() ? "已翻译成 " + languageName : "Translated to " + languageName;
    }

    public static String undo() {
        return s("撤销", "Undo");
    }

    public static String failed() {
        return s("翻译失败，请稍后再试", "Translation failed. Please try again later.");
    }

    public static String pickerTitle() {
        return s("翻译成", "Translate to");
    }

    public static String search() {
        return s("搜索语言", "Search languages");
    }

    public static String common() {
        return s("常用语言", "Frequently used");
    }

    public static String all() {
        return s("所有语言", "All languages");
    }

    public static String noResults() {
        return s("没有找到该语言", "No languages found");
    }
}
