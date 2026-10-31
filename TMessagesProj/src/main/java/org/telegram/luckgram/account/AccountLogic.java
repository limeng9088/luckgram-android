package org.telegram.luckgram.account;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Pure logic of the Account (S6) and Luckgram Pro (S7) pages: response parsing, polling decisions
 * and texts in both languages. Only java.* and org.json, so it runs in the JVM unit tests; the
 * same rules are in AccountLogic.swift on iOS.
 */
public final class AccountLogic {

    private AccountLogic() {
    }

    /** Poll {@code GET /v1/link?nonce=} this often after opening the bot (luckgram-specs#31 item 3). */
    public static final long POLL_INTERVAL_MS = 2000;
    /** After opening an invoice, refresh the membership this often, this many times. */
    public static final long PRO_REFRESH_INTERVAL_MS = 3000;
    public static final int PRO_REFRESH_TIMES = 20;

    // ---------------------------------------------------------------- link (S6)

    public static final class LinkStatus {
        public boolean linked;
        public int devices;
        public int maxDevices;
        /** pending / linked / expired / used / limit_reached, or null without a nonce. */
        public String request;
    }

    public static LinkStatus parseLink(JSONObject j) {
        LinkStatus s = new LinkStatus();
        s.linked = j.optBoolean("linked", false);
        s.devices = j.optInt("devices", 0);
        s.maxDevices = j.optInt("max_devices", 0);
        s.request = j.has("request") && !j.isNull("request") ? j.optString("request", null) : null;
        return s;
    }

    public static final class LinkStart {
        public String nonce;
        public String url;
        public long expiresAtMs;
    }

    public static LinkStart parseStart(JSONObject j) {
        LinkStart s = new LinkStart();
        s.nonce = j.optString("nonce", "");
        s.url = j.optString("url", "");
        s.expiresAtMs = parseTime(j.optString("expires_at", ""));
        return s;
    }

    /** Keep polling while the request is pending and the nonce has not expired. */
    public static boolean shouldPoll(String request, long nowMs, long expiresAtMs) {
        return (request == null || "pending".equals(request)) && nowMs < expiresAtMs;
    }

    /** Only links the bot itself opens: https://t.me/... */
    public static boolean isTelegramLink(String url) {
        return url != null && url.startsWith("https://t.me/") && url.length() > "https://t.me/".length();
    }

    // ---------------------------------------------------------------- Pro (S7)

    public static final class Plan {
        public String id;
        public int days;
        public int stars;
    }

    public static final class Billing {
        public boolean enabled;
        public boolean linked;
        public boolean pro;
        /** RFC 3339 or null. */
        public String proExpiresAt;
        public final List<Plan> plans = new ArrayList<>();
    }

    public static Billing parseBilling(JSONObject j) {
        Billing b = new Billing();
        b.enabled = j.optBoolean("enabled", false);
        b.linked = j.optBoolean("linked", false);
        b.pro = "pro".equals(j.optString("tier", "free"));
        b.proExpiresAt = j.has("pro_expires_at") && !j.isNull("pro_expires_at") ? j.optString("pro_expires_at", null) : null;
        JSONArray arr = j.optJSONArray("plans");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.optJSONObject(i);
                if (p == null || p.optInt("stars", 0) <= 0) continue;
                Plan plan = new Plan();
                plan.id = p.optString("id", "");
                plan.days = p.optInt("days", 0);
                plan.stars = p.optInt("stars", 0);
                b.plans.add(plan);
            }
        }
        return b;
    }

    // ---------------------------------------------------------------- dates

    public static long parseTime(String rfc3339) {
        if (rfc3339 == null || rfc3339.isEmpty()) return 0;
        // "Z" / "+08:00" -> "+0000" / "+0800" ('X' needs API 24).
        String s = rfc3339.trim();
        if (s.endsWith("Z")) {
            s = s.substring(0, s.length() - 1) + "+0000";
        } else if (s.length() > 6 && s.charAt(s.length() - 3) == ':' && (s.charAt(s.length() - 6) == '+' || s.charAt(s.length() - 6) == '-')) {
            s = s.substring(0, s.length() - 3) + s.substring(s.length() - 2);
        }
        int dot = s.indexOf('.');
        if (dot > 0) { // drop fractional seconds
            int end = dot + 1;
            while (end < s.length() && Character.isDigit(s.charAt(end))) end++;
            s = s.substring(0, dot) + s.substring(end);
        }
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US);
            f.setLenient(false);
            return f.parse(s).getTime();
        } catch (ParseException e) {
            return 0;
        }
    }

    /** yyyy-MM-dd in the device's time zone. */
    public static String date(String rfc3339, TimeZone tz) {
        long t = parseTime(rfc3339);
        if (t == 0) return "";
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(tz);
        return f.format(new Date(t));
    }

    // ---------------------------------------------------------------- texts

    public static final class Texts {
        private final boolean zh;

        public Texts(boolean zh) {
            this.zh = zh;
        }

        private String t(String c, String e) {
            return zh ? c : e;
        }

        public String accountTitle() { return t("账号", "Account"); }
        public String telegramAccount() { return t("Telegram 账号", "Telegram account"); }
        public String linked() { return t("已绑定", "Linked"); }
        public String notLinked() { return t("未绑定", "Not linked"); }
        public String devices(int n, int max) { return t("已绑定设备：" + n + " / " + max, "Linked devices: " + n + " / " + max); }
        public String link() { return t("绑定 Telegram 账号", "Link Telegram account"); }
        public String unlink() { return t("解绑", "Unlink"); }
        public String waiting() { return t("等待 Bot 确认…（在 Bot 里点「开始」）", "Waiting for the bot… (tap Start in the bot)"); }
        public String linkInfo() {
            return t("绑定后，同一个 Telegram 账号下的设备共享 AI 额度和 Luckgram Pro。服务器只保存 Telegram 用户 ID，不保存手机号、用户名或聊天内容。",
                "Devices linked to the same Telegram account share AI limits and Luckgram Pro. The server keeps only your Telegram user ID — no phone number, username or chats.");
        }
        public String unlinkConfirmTitle() { return t("解绑这台设备？", "Unlink this device?"); }
        public String unlinkConfirmText() {
            return t("解绑后这台设备不再共享 Luckgram Pro。今天已用的 AI 额度不会因此恢复。",
                "This device will no longer share Luckgram Pro. AI usage already counted today is not reset.");
        }
        public String linkedNow() { return t("已绑定 Telegram 账号", "Telegram account linked"); }
        public String unlinkedNow() { return t("已解绑", "Unlinked"); }
        public String linkResult(String request, int max) {
            if ("expired".equals(request)) return t("绑定链接已过期，请重新点「绑定 Telegram 账号」。", "The link expired. Tap “Link Telegram account” again.");
            if ("used".equals(request)) return t("这个绑定链接已被使用，请重新点「绑定 Telegram 账号」。", "This link was already used. Tap “Link Telegram account” again.");
            if ("limit_reached".equals(request)) return t("这个 Telegram 账号已绑定 " + max + " 台设备，请先在 Bot 里用 /devices 解绑一台。", "This Telegram account already has " + max + " linked devices. Unlink one in the bot with /devices first.");
            return t("没有完成绑定，请重试。", "Linking was not completed. Please try again.");
        }

        public String proTitle() { return "Luckgram Pro"; }
        public String free() { return t("免费版", "Free"); }
        public String proUntil(String date) { return t("Pro，到期 " + date, "Pro until " + date); }
        public String plan(Plan p) { return t(p.days + " 天", p.days + " days"); }
        public String stars(int n) { return n + " ⭐"; }
        public String proInfo() {
            return t("用 Telegram Stars 付款，所有绑定这个 Telegram 账号的设备共享。已是 Pro 时再买会从当前到期时间往后延。",
                "Paid with Telegram Stars and shared by every device linked to this Telegram account. Buying again while Pro extends from the current end date.");
        }
        public String linkFirst() { return t("先绑定 Telegram 账号才能购买", "Link your Telegram account first"); }
        public String goLink() { return t("去绑定", "Link"); }
        public String notOnSale() { return t("Luckgram Pro 暂不开放购买", "Luckgram Pro is not on sale right now"); }
        public String checking() { return t("正在确认付款…", "Checking the payment…"); }
        public String proActive() { return t("Luckgram Pro 已生效", "Luckgram Pro is active"); }

        /** Server error codes of /v1/link* and /v1/billing/*. */
        public String error(String code) {
            if (code == null) code = "";
            switch (code) {
                case "link_unavailable":
                case "billing_unavailable":
                    return t("服务暂不可用，请稍后再试", "Not available right now. Please try again later");
                case "already_linked":
                    return t("这台设备已经绑定了", "This device is already linked");
                case "not_linked":
                    return linkFirst();
                case "billing_disabled":
                    return notOnSale();
                case "rate_limited":
                    return t("操作太频繁，请稍后再试", "Too many attempts. Please try again later");
                case "network_error":
                    return t("网络不可用，请检查网络后重试", "No connection. Check your network and try again");
                default:
                    return t("出错了，请稍后再试", "Something went wrong. Please try again later");
            }
        }
    }
}
