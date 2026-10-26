package org.telegram.luckgram.cache;

import org.telegram.messenger.CacheByChatsController;
import org.telegram.messenger.LocaleController;

import java.util.Locale;

/**
 * Luckgram 08 (cache): UI strings for the storage-usage extensions.
 *
 * Chinese is shown when either the app language or the system language is Chinese,
 * English otherwise. Kept local to this feature so it does not depend on server language packs.
 */
public final class LuckgramCacheStrings {

    private LuckgramCacheStrings() {
    }

    public static boolean isChinese() {
        try {
            Locale appLocale = LocaleController.getInstance().getCurrentLocale();
            if (appLocale != null && "zh".equals(appLocale.getLanguage())) {
                return true;
            }
        } catch (Throwable ignore) {
        }
        Locale system = Locale.getDefault();
        try {
            system = android.content.res.Resources.getSystem().getConfiguration().locale;
        } catch (Throwable ignore) {
        }
        return system != null && "zh".equals(system.getLanguage());
    }

    private static String t(String zh, String en) {
        return isChinese() ? zh : en;
    }

    /** Label of a keep-media option (one of CacheByChatsController.KEEP_MEDIA_*). */
    public static String keepMedia(int keepMedia) {
        if (keepMedia == CacheByChatsController.KEEP_MEDIA_ONE_HOUR) {
            return t("1 小时", "1 hour");
        } else if (keepMedia == CacheByChatsController.KEEP_MEDIA_ONE_DAY) {
            return t("1 天", "1 day");
        } else if (keepMedia == CacheByChatsController.KEEP_MEDIA_TWO_DAY) {
            return t("2 天", "2 days");
        } else if (keepMedia == CacheByChatsController.KEEP_MEDIA_THREE_DAY) {
            return t("3 天", "3 days");
        } else if (keepMedia == CacheByChatsController.KEEP_MEDIA_ONE_WEEK) {
            return t("1 周", "1 week");
        } else if (keepMedia == CacheByChatsController.KEEP_MEDIA_ONE_MONTH) {
            return t("1 个月", "1 month");
        } else if (keepMedia == CacheByChatsController.KEEP_MEDIA_ONE_MINUTE) {
            return t("1 分钟", "1 minute");
        }
        return t("永久", "Forever");
    }

    public static String noLimit() {
        return t("不限", "No limit");
    }

    public static String cacheLimitTitle() {
        return t("缓存上限", "Cache limit");
    }

    public static String cacheLimitInfo() {
        return t("超过上限时，最久未打开的缓存会先被自动清理。收藏夹里的媒体和正在下载的文件不会被清理。",
                "When the cache grows beyond the limit, the files you haven't opened for the longest time are removed first. Media in Saved Messages and files being downloaded are kept.");
    }

    public static String cacheLimitOption(int gigabytes) {
        return gigabytes + " GB";
    }

    /** Row title in the per-chat storage sheet. */
    public static String chatKeepMedia() {
        return t("本聊天媒体保留时间", "Keep media for this chat");
    }

    /** Value shown when the chat follows its category default. */
    public static String followCategory(String categoryValue) {
        return isChinese() ? "跟随分类（" + categoryValue + "）" : "Default (" + categoryValue + ")";
    }

    /** First item of the chooser: remove the per-chat override. */
    public static String useCategoryDefault(String categoryValue) {
        return isChinese() ? "使用分类默认值（" + categoryValue + "）" : "Use default (" + categoryValue + ")";
    }

    public static String chatUsesSpace(String size) {
        return isChinese() ? "该聊天在本机占用 " + size : "This chat uses " + size + " on this device";
    }

    public static String chatKeepMediaInfo() {
        return t("覆盖私聊 / 群组 / 频道的分类设置。收藏夹里的媒体和正在下载的文件不会被自动清理。",
                "Overrides the private chats / groups / channels setting. Media in Saved Messages and files being downloaded are never removed automatically.");
    }

    /** Profile "…" menu entry. */
    public static String profileMenuStorage() {
        return t("存储空间与保留时间", "Storage and keep media");
    }

    /** Line added to the "clear now" confirmation. */
    public static String willFree(String size) {
        return isChinese() ? "将释放 " + size + " 的本机空间。云端的文件不会被删除。" : "This will free up " + size + " on this device. Files stay in the cloud.";
    }
}
