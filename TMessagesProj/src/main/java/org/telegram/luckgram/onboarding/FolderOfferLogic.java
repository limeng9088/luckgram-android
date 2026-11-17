package org.telegram.luckgram.onboarding;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * specs#98: after a new login, offer the "Luckgram picks" chat folder once per Telegram account.
 * Pure logic (no Android), unit-tested in FolderOfferLogicTest.
 */
public final class FolderOfferLogic {

    /** Remote config keys (luckgram-server). */
    public static final String ENABLED_KEY = "onboarding.folder_enabled";
    public static final String LINK_KEY = "onboarding.folder_link";

    private static final Pattern LINK = Pattern.compile("^https://t\\.me/addlist/([A-Za-z0-9_-]{4,64})$");

    private FolderOfferLogic() {
    }

    /** The slug of a https://t.me/addlist/&lt;slug&gt; link, null for anything else. */
    public static String slug(String link) {
        if (link == null) {
            return null;
        }
        Matcher m = LINK.matcher(link.trim());
        return m.matches() ? m.group(1) : null;
    }

    /** LuckgramSettings key remembering that this Telegram user saw the offer (any slot, any login). */
    public static String seenKey(long userId) {
        return "onboarding_folder_seen_" + userId;
    }

    /** Whether to ask Telegram about the folder at all: switched on, a valid link, not seen yet. */
    public static boolean shouldCheck(boolean enabled, String link, boolean seen, long userId) {
        return enabled && !seen && userId != 0 && slug(link) != null;
    }

    /**
     * After checkChatlistInvite: offer only when the account has not joined the folder yet
     * ("already" = joined) and the folder has something to join.
     */
    public static boolean shouldOffer(boolean alreadyJoined, int peers) {
        return !alreadyJoined && peers > 0;
    }

    /** Page texts. */
    public static final class Texts {
        private final boolean zh;

        public Texts(boolean zh) {
            this.zh = zh;
        }

        public String title() {
            return zh ? "Luckgram 为你精选" : "Hand-picked for you by Luckgram";
        }

        public String subtitle() {
            return zh ? "一个文件夹收好官方频道和精选内容，更新和活动不错过。"
                : "Official channels and picks in one folder, so you never miss an update or event.";
        }

        public String add() {
            return zh ? "添加 Luckgram 精选" : "Add Luckgram Picks";
        }

        public String skip() {
            return zh ? "不用了" : "No thanks";
        }
    }
}
