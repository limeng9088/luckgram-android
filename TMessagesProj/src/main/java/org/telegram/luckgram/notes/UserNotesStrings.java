/*
 * Luckgram — private notes (spec 05).
 * Licensed under GNU GPL v. 2 or later.
 */

package org.telegram.luckgram.notes;

import org.telegram.messenger.LocaleController;

import java.util.Locale;

/**
 * Chinese / English UI strings for private notes.
 *
 * Telegram loads translations from cloud language packs, which have no keys for Luckgram features,
 * so the strings live here. Chinese is used when the selected app language is Chinese, or when the
 * app uses its default (English) pack and the system language is Chinese.
 */
public final class UserNotesStrings {

    private UserNotesStrings() {
    }

    public static boolean isChinese() {
        try {
            LocaleController lc = LocaleController.getInstance();
            LocaleController.LocaleInfo info = lc.getCurrentLocaleInfo();
            String code = info != null ? (info.pluralLangCode != null ? info.pluralLangCode : info.shortName) : null;
            if (code != null && code.toLowerCase(Locale.ROOT).startsWith("zh")) {
                return true;
            }
            if (info == null || "en".equals(info.shortName)) {
                Locale sys = lc.getSystemDefaultLocale();
                return sys != null && "zh".equals(sys.getLanguage());
            }
        } catch (Exception ignore) {
        }
        return false;
    }

    private static String s(String en, String zh) {
        return isChinese() ? zh : en;
    }

    public static String featureName() { return s("Private notes", "私人备注"); }
    public static String featureDescription() { return s("Notes about contacts, groups and channels that only you can see", "给联系人、群组和频道写只有自己能看到的备注"); }

    public static String rowTitle() { return s("Private note", "私人备注"); }
    public static String onlyYou() { return s("only visible to you", "只有你能看到"); }
    public static String addNote() { return s("Add a note…", "添加备注…"); }

    public static String editTitle() { return s("Private note", "私人备注"); }
    public static String editHint() { return s("Write something only you can see", "写点只有你能看到的内容"); }
    public static String editInfo() { return s("Only you can see this note. It is stored on this device for the current account and is never uploaded.", "只有你能看到这条备注。它只保存在这台设备的当前账号中，不会上传。"); }
    public static String deleteNote() { return s("Delete note", "删除备注"); }
    public static String deleteConfirm() { return s("Delete this note?", "确定删除这条备注吗？"); }
    public static String delete() { return s("Delete", "删除"); }
    public static String cancel() { return s("Cancel", "取消"); }
    public static String discardTitle() { return s("Discard changes?", "放弃修改？"); }
    public static String discard() { return s("Discard", "放弃"); }
    public static String tooLong() { return s("A note can be up to 2000 characters", "备注最多 2000 字"); }

    public static String barTitle() { return s("Private note · only visible to you", "私人备注 · 只有你能看到"); }
    public static String edit() { return s("Edit", "编辑"); }
    public static String expand() { return s("Show note", "展开备注"); }
    public static String collapse() { return s("Collapse", "收起"); }

    public static String allNotes() { return s("All notes", "全部备注"); }
    public static String searchHint() { return s("Search notes or names", "搜索备注或名字"); }
    public static String noNotes() { return s("No notes yet. Open a profile and tap “Add a note…”.", "还没有备注。打开资料页，点击「添加备注…」即可添加。"); }
    public static String noResults() { return s("No results", "没有找到结果"); }
    public static String unknownPeer() { return s("Unknown", "未知"); }
}
