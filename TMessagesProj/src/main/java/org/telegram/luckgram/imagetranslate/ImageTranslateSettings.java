package org.telegram.luckgram.imagetranslate;

import org.telegram.luckgram.LuckgramSettings;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.luckgram.translatein.TranslateInLogic;
import org.telegram.luckgram.translateout.TranslateOutController;
import org.telegram.messenger.LocaleController;

/**
 * specs#108 需求 2: which language images are translated to. This chat's choice, else the image-only
 * default, else the reading translation language (#105), else the app language. Kept on this device.
 */
public final class ImageTranslateSettings {

    private ImageTranslateSettings() {
    }

    public static boolean isEnabled() {
        return LuckgramSettings.isEnabled(ImageTranslateLogic.FEATURE_KEY);
    }

    private static String chatKey(long dialogId) {
        return ImageTranslateLogic.KEY_CHAT_PREFIX + dialogId;
    }

    /** {@code dialogId} 0 = not in a chat (gallery before sending a new chat, etc.). */
    public static String targetLanguage(long dialogId) {
        String perChat = dialogId != 0 ? LuckgramSettings.getString(chatKey(dialogId), "") : "";
        String app = LocaleController.getInstance().getCurrentLocale() != null ? LocaleController.getInstance().getCurrentLocale().getLanguage() : "en";
        return ImageTranslateLogic.targetLanguage(perChat, imageDefault(), ImageTranslator.readingTarget(), app);
    }

    /** The image-only default, or "" when images follow the reading translation language. */
    public static String imageDefault() {
        return LuckgramSettings.getString(ImageTranslateLogic.KEY_TO_LANG, "");
    }

    /** Whether the user picked a language for images yet (first use asks, needs 2). */
    public static boolean hasChosen(long dialogId) {
        return !imageDefault().isEmpty() || (dialogId != 0 && !LuckgramSettings.getString(chatKey(dialogId), "").isEmpty());
    }

    /** "Only this chat" (dialogId ≠ 0) or "as default". Choosing the default clears this chat's override. */
    public static void setLanguage(long dialogId, String lang, boolean onlyThisChat) {
        if (onlyThisChat && dialogId != 0) {
            LuckgramSettings.setString(chatKey(dialogId), lang);
        } else {
            LuckgramSettings.setString(ImageTranslateLogic.KEY_TO_LANG, lang);
            if (dialogId != 0) {
                LuckgramSettings.setString(chatKey(dialogId), null);
            }
        }
    }

    /** Settings: back to following the reading translation language. */
    public static void followReadingLanguage() {
        LuckgramSettings.setString(ImageTranslateLogic.KEY_TO_LANG, null);
    }

    public static String languageName(String lang) {
        return TranslateOutController.languageName(TranslateInLogic.base(lang));
    }

    // ---------------------------------------------------------------- strings (same as iOS)

    public static String translateImageText() {
        return LuckgramStrings.get("翻译图片", "Translate image");
    }

    public static String buttonText() {
        return LuckgramStrings.get("译", "Tr");
    }

    public static String originalText() {
        return LuckgramStrings.get("原图", "Original");
    }

    public static String translatedText() {
        return LuckgramStrings.get("译文", "Translation");
    }

    public static String copyText() {
        return LuckgramStrings.get("复制译文", "Copy translation");
    }

    public static String saveText() {
        return LuckgramStrings.get("保存图片", "Save image");
    }

    public static String shareText() {
        return LuckgramStrings.get("分享", "Share");
    }

    public static String sendTranslatedText() {
        return LuckgramStrings.get("发送翻译后的图片", "Send translated image");
    }

    public static String translatingText() {
        return LuckgramStrings.get("正在翻译…", "Translating…");
    }

    public static String noTextText() {
        return LuckgramStrings.get("图片里没有识别到要翻译的文字", "No text to translate was found in this image");
    }

    public static String copiedText() {
        return LuckgramStrings.get("译文已复制", "Translation copied");
    }

    public static String savedText() {
        return LuckgramStrings.get("已保存到相册", "Saved to gallery");
    }

    public static String onlyThisChatText() {
        return LuckgramStrings.get("仅本聊天", "Only this chat");
    }

    public static String asDefaultText() {
        return LuckgramStrings.get("设为默认", "Set as default");
    }

    public static String applyToText(String lang) {
        return LuckgramStrings.get("图片翻译成" + lang + "：", "Translate images to " + lang + ":");
    }

    public static String originalLabel() {
        return LuckgramStrings.get("原文", "Original");
    }

    public static String translationLabel() {
        return LuckgramStrings.get("译文", "Translation");
    }

    /** Luckgram Features → image translation subpage. */
    public static String settingsLanguageText() {
        return LuckgramStrings.get("图片翻译语言", "Translate images to");
    }

    public static String followReadingText() {
        return LuckgramStrings.get("跟随阅读翻译语言", "Same as message translation");
    }

    public static String settingsInfoText() {
        return LuckgramStrings.get("图片里的文字在本机识别，只把识别出的文字发去翻译，不上传图片。",
                "Text is recognized on this device; only the recognized text is sent for translation, never the image.");
    }
}
