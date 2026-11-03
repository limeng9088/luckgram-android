package org.telegram.luckgram.procommunity;

import org.json.JSONObject;

/**
 * specs#89 (31): Pro community, the parts that need no Android. What {@code POST /v1/pro/community/invite}
 * (S14) answered, which links may be opened, the Bot API → Telegram channel id, and the texts.
 *
 * <p>Pure Java (java.* and org.json only), unit-tested in {@code ProCommunityLogicTest}.
 */
public final class ProCommunityLogic {

    /** Feature switch (default on). */
    public static final String FEATURE_KEY = "pro_community";
    public static final String INVITE_PATH = "/v1/pro/community/invite";

    public static final String CODE_NOT_PRO = "not_pro";
    public static final String CODE_ALREADY_MEMBER = "already_member";

    private ProCommunityLogic() {
    }

    /** What the invite request ended in. */
    public static final class Invite {
        public static final int JOIN = 1;
        public static final int ALREADY_MEMBER = 2;
        public static final int BAD_ANSWER = 3;

        public final int kind;
        /** JOIN: the one-time invite link. ALREADY_MEMBER: an optional link to the group. */
        public final String link;
        /** ALREADY_MEMBER: the Telegram channel id (positive), or 0 if not sent. */
        public final long channelId;

        Invite(int kind, String link, long channelId) {
            this.kind = kind;
            this.link = link;
            this.channelId = channelId;
        }
    }

    /** Shows the row: the feature is on and the server lists the perk (29: no perk, no row). */
    public static boolean showRow(boolean featureEnabled, boolean hasPerk) {
        return featureEnabled && hasPerk;
    }

    /** Parses a 200 body: {@code {invite_link}} or {@code {already_member:true, chat_id, chat_link?}}. */
    public static Invite parse(JSONObject body) {
        if (body == null) return new Invite(Invite.BAD_ANSWER, null, 0);
        if (body.optBoolean(CODE_ALREADY_MEMBER, false) || CODE_ALREADY_MEMBER.equals(body.optString("status", ""))) {
            return alreadyMember(body);
        }
        String link = firstLink(body, "invite_link", "url", "link");
        if (link == null) return new Invite(Invite.BAD_ANSWER, null, 0);
        return new Invite(Invite.JOIN, link, 0);
    }

    /** {@code already_member} sent as an error (e.g. 409): the error object, or null. */
    public static Invite alreadyMember(JSONObject body) {
        JSONObject src = body != null ? body : new JSONObject();
        long chat = channelId(src.optLong("chat_id", 0));
        return new Invite(Invite.ALREADY_MEMBER, firstLink(src, "chat_link", "invite_link", "url", "link"), chat);
    }

    /** Bot API supergroup id (-100…) or a plain channel id → the channel id; 0 if neither. */
    public static long channelId(long botApiId) {
        if (botApiId < -1000000000000L) return -botApiId - 1000000000000L;
        if (botApiId > 0) return botApiId;
        return 0;
    }

    /** Only Telegram links are opened (inside the app). */
    public static boolean isOpenableLink(String url) {
        if (url == null) return false;
        if (url.startsWith("tg://")) return url.length() > "tg://".length();
        return (url.startsWith("https://t.me/") && url.length() > "https://t.me/".length())
            || (url.startsWith("https://telegram.me/") && url.length() > "https://telegram.me/".length());
    }

    private static String firstLink(JSONObject o, String... keys) {
        for (String k : keys) {
            if (o.isNull(k)) continue;
            String v = o.optString(k, "");
            if (isOpenableLink(v)) return v;
        }
        return null;
    }

    public static final class Texts {
        private final boolean zh;

        public Texts(boolean zh) {
            this.zh = zh;
        }

        private String t(String zhText, String en) {
            return zh ? zhText : en;
        }

        public String join() { return t("加入 Pro 社群", "Join the Pro community"); }
        public String info() { return t("Luckgram Pro 用户的 Telegram 私密群。会员到期后会自动移出，续期后可重新加入。",
            "A private Telegram group for Luckgram Pro members. You leave it automatically when Pro ends and can join again after renewing."); }
        public String alreadyMember() { return t("你已经在 Pro 社群里", "You're already in the Pro community"); }
        public String proOnly() { return t("Pro 社群是 Luckgram Pro 专享，订阅后即可加入", "The Pro community is for Luckgram Pro members. Subscribe to join"); }
    }
}
