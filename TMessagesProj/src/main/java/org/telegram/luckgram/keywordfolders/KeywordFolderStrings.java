/*
 * Luckgram — keyword folders.
 * Licensed under GNU GPL v. 2 or later, like the rest of this Telegram-based project.
 */

package org.telegram.luckgram.keywordfolders;

import android.content.res.Resources;

import org.telegram.messenger.LocaleController;

import java.util.Locale;

/**
 * Chinese / English strings of the feature. Telegram ships no Chinese language pack, so Chinese is
 * used when the in-app language is Chinese, or when the app falls back to English while the system
 * language is Chinese.
 */
public final class KeywordFolderStrings {

    private KeywordFolderStrings() {
    }

    public static boolean isChinese() {
        String code = null;
        try {
            LocaleController.LocaleInfo info = LocaleController.getInstance().getCurrentLocaleInfo();
            if (info != null && info.shortName != null) {
                code = info.shortName.toLowerCase(Locale.ROOT);
            }
        } catch (Throwable ignore) {
        }
        if (code != null && code.startsWith("zh")) {
            return true;
        }
        if (code == null || code.startsWith("en")) {
            try {
                Locale system = Resources.getSystem().getConfiguration().locale;
                return system != null && "zh".equals(system.getLanguage());
            } catch (Throwable ignore) {
            }
        }
        return false;
    }

    private static String s(String zh, String en) {
        return isChinese() ? zh : en;
    }

    public static String title() { return s("关键词文件夹", "Keyword Folders"); }
    public static String newFolder() { return s("新建关键词文件夹", "New Keyword Folder"); }
    public static String editFolder() { return s("编辑关键词文件夹", "Edit Keyword Folder"); }
    public static String menuItem() { return s("关键词文件夹", "Keyword Folder"); }
    public static String newCloudFolder() { return s("新建文件夹", "New Folder"); }
    public static String settingsInfo() {
        return s("关键词文件夹只保存在本机、按账号保存，不会同步到其他设备。最近消息里提到关键词的聊天会自动归入。",
                "Keyword folders are stored on this device for this account only and are not synced. Chats whose recent messages mention a keyword are added automatically.");
    }
    public static String nameHeader() { return s("文件夹名称", "Folder name"); }
    public static String nameHint() { return s("例如：发票", "e.g. Invoices"); }
    public static String keywordsHeader() { return s("关键词（1–10 个）", "Keywords (1–10)"); }
    public static String keywordHint() { return s("关键词", "Keyword"); }
    public static String addKeyword() { return s("添加关键词", "Add keyword"); }
    public static String keywordsInfo() {
        return s("不区分大小写；中文按字词片段匹配，英文按整词匹配。只统计最近的消息：每个聊天的最后一条消息和最近 50 条已缓存在本机的消息。",
                "Case-insensitive. Chinese keywords match any part of the text, English keywords match whole words. Only recent messages are checked: the last message and the latest 50 messages cached on this device.");
    }
    public static String scopeHeader() { return s("范围", "Include"); }
    public static String scopeAll() { return s("全部", "All"); }
    public static String scopePrivate() { return s("私聊", "Private chats"); }
    public static String scopeGroups() { return s("群组", "Groups"); }
    public static String scopeChannels() { return s("频道", "Channels"); }
    public static String delete() { return s("删除关键词文件夹", "Delete Keyword Folder"); }
    public static String deleteAlertTitle() { return s("删除文件夹", "Delete Folder"); }
    public static String deleteAlertText() { return s("确定删除这个关键词文件夹吗？其中的聊天不会受影响。", "Delete this keyword folder? The chats in it will not be affected."); }
    public static String errorName() { return s("请输入文件夹名称", "Enter a folder name"); }
    public static String errorKeywords() { return s("请至少填写一个关键词", "Enter at least one keyword"); }
    public static String errorScope() { return s("请至少选择一个范围", "Choose at least one chat type"); }
    public static String limitReached() { return s("最多可以创建 10 个关键词文件夹", "You can create up to 10 keyword folders"); }
    public static String keywordsLimit() { return s("最多 10 个关键词", "Up to 10 keywords"); }
    public static String filterInfo(String keywords) { return s("关键词：", "Keywords: ") + keywords; }
    public static String listSeparator() { return s("、", ", "); }
    public static String jumpNext(String keyword) { return s("跳到下一个「" + keyword + "」", "Next \u201C" + keyword + "\u201D"); }
    public static String jumpNoMore() { return s("最近的消息里没有更多匹配，已回到最新一条", "No more matches in recent messages, back to the latest"); }
    public static String jumpNone() { return s("最近的消息里没有找到关键词", "No keyword found in recent messages"); }
    public static String close() { return s("关闭", "Close"); }
    public static String localBadge() { return s("本地", "Local"); }
}
