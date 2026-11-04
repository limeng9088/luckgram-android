/*
 * Luckgram: UI strings for "translate before sending", in Chinese and English.
 *
 * Telegram's own language packs have no official Chinese, so these strings follow the system
 * language (or a Chinese in-app language) instead of relying on server-side packs.
 */
package org.telegram.luckgram.translateout;

import org.telegram.luckgram.LuckgramStrings;


public final class TranslateOutStrings {

    private TranslateOutStrings() {
    }

    /** Same rule as every other Luckgram string (LuckgramStrings, feature 00). */
    public static boolean isChinese() {
        return LuckgramStrings.isChinese();
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

    /** spec 01 C.2: send button long-press item that picks the target language first. */
    public static String translateTo() {
        return s("翻译成…", "Translate to…");
    }

    public static String undo() {
        return s("撤销", "Undo");
    }

    public static String failed() {
        return s("翻译失败，请稍后再试", "Translation failed. Please try again later.");
    }

    // ---- specs#104: per-chat 「发送翻译」

    public static String chatMenu() { return s("发送前翻译…", "Translate before sending…"); }
    public static String turnOff() { return s("关闭发送翻译", "Turn off translate before sending"); }
    public static String turnedOn(String languageName) { return isChinese() ? "已开启发送翻译：发送前翻成" + languageName : "Translate before sending is on: messages go out in " + languageName; }
    public static String turnedOff() { return s("已关闭发送翻译", "Translate before sending is off"); }
    public static String translateAndSend() { return s("翻译后发送…", "Translate and send…"); }
    public static String sendTranslation() { return s("发送译文", "Send translation"); }
    public static String sendOriginal() { return s("发送原文", "Send original"); }
    public static String edit() { return s("编辑", "Edit"); }
    public static String previewTitle(String languageName) { return isChinese() ? "发送前预览 · " + languageName : "Preview · " + languageName; }
    public static String failedSendOriginal(String reason) { return (reason != null ? reason : failed()) + s("。要发送原文吗？", ". Send the original instead?"); }
    public static String cancel() { return s("取消", "Cancel"); }
    public static String capsuleHint() { return s("发送前翻译：点这里改语言或关闭", "Translate before sending: tap to change the language or turn it off"); }
    public static String settingsTitle() { return s("发送前翻译", "Translate before sending"); }
    public static String preview() { return s("发送前预览译文", "Preview before sending"); }
    public static String previewInfo() { return s("关掉后直接发送译文，不再弹出预览。", "When off, the translation is sent right away without a preview."); }
    public static String chatsOn() { return s("已开启发送翻译的聊天", "Chats with translate before sending"); }
    public static String noChatsOn() { return s("还没有聊天开启。在聊天「⋮」菜单或输入框「+」里选「发送前翻译…」。", "None yet. Open a chat's ⋮ menu or the input field's + and choose Translate before sending…"); }
    public static String turnOffAll() { return s("全部关闭", "Turn off for all chats"); }

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
