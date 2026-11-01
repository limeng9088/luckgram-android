package org.telegram.luckgram.nearby;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Pure logic of "People nearby" (spec 25, server S10): coordinate coarsening, response parsing,
 * distance-bucket grouping, expiry / reminder times, preconditions and all texts in both
 * languages. Only java.* and org.json, so it runs in the JVM unit tests; the same rules are in
 * NearbyLogic.swift on iOS.
 */
public final class NearbyLogic {

    private NearbyLogic() {
    }

    public static final String FEATURE_KEY = "nearby";

    public static final int BIO_MAX = 70;
    public static final int MAX_RESULTS = 100;
    /** Reminder this long before the visibility expires (spec 25 item 4). */
    public static final long REMIND_BEFORE_MS = 60L * 60 * 1000;
    public static final long DEFAULT_VISIBLE_MS = 24L * 60 * 60 * 1000;
    /** Avatar upload limit agreed with S10: base64 JPEG ≤ 256 KB. */
    public static final int AVATAR_MAX_BYTES = 256 * 1024;

    // ---------------------------------------------------------------- coordinates

    /**
     * Rounds a coordinate to a ~1 km grid (2 decimals). The raw value never leaves the device; the
     * result is what goes into the request.
     */
    public static double coarsen(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return 0;
        return Math.round(value * 100.0) / 100.0;
    }

    /** Coarsened coordinate as sent: at most 2 decimals, '.' separator. */
    public static String coord(double value) {
        String s = String.format(Locale.US, "%.2f", coarsen(value));
        // "-0.00" -> "0.00"
        return s.equals("-0.00") ? "0.00" : s;
    }

    public static boolean isValidLocation(double lat, double lon) {
        return !Double.isNaN(lat) && !Double.isNaN(lon) && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }

    public static String queryPath(double lat, double lon) {
        return "/v1/nearby?lat=" + coord(lat) + "&lon=" + coord(lon);
    }

    // ---------------------------------------------------------------- card

    /** Trims the bio and cuts it to {@link #BIO_MAX} characters (code points). */
    public static String cleanBio(String bio) {
        if (bio == null) return "";
        String s = bio.replace('\n', ' ').trim();
        if (s.codePointCount(0, s.length()) > BIO_MAX) {
            s = s.substring(0, s.offsetByCodePoints(0, BIO_MAX));
        }
        return s;
    }

    /** S10 limit for {@code display_name} (runes). */
    public static final int NAME_MAX = 64;

    /**
     * Card name: first name, else last name, else the username — same as iOS
     * {@code compactDisplayTitle} — capped at {@link #NAME_MAX} code points.
     */
    public static String cardName(String firstName, String lastName, String username) {
        String s = firstName == null ? "" : firstName.replace('\n', ' ').trim();
        if (s.isEmpty()) s = lastName == null ? "" : lastName.replace('\n', ' ').trim();
        if (s.isEmpty()) s = username == null ? "" : username;
        if (s.codePointCount(0, s.length()) > NAME_MAX) {
            s = s.substring(0, s.offsetByCodePoints(0, NAME_MAX));
        }
        return s;
    }

    public static JSONObject cardBody(String displayName, String bio, String username, double lat, double lon, String avatarBase64) throws Exception {
        JSONObject j = new JSONObject()
            .put("display_name", cardName(displayName, null, username))
            .put("bio", cleanBio(bio))
            .put("username", username)
            // JSON numbers; coarsened again here so callers can't send the raw value by mistake.
            .put("lat", Double.parseDouble(coord(lat)))
            .put("lon", Double.parseDouble(coord(lon)));
        if (avatarBase64 != null) {
            j.put("avatar", avatarBase64);
        }
        return j;
    }

    // ---------------------------------------------------------------- preconditions

    public static final int READY = 0;
    public static final int NEED_USERNAME = 2;

    /** Only the public username is up to the user; the S6 link is made silently (spec 25 item 2). */
    public static int precondition(String username) {
        if (username == null || username.isEmpty()) return NEED_USERNAME;
        return READY;
    }

    // ---------------------------------------------------------------- responses

    public static final class Person {
        public String id;
        public String displayName;
        public String bio;
        public String username;
        public String avatarUrl;
        public String bucket;
    }

    public static final class MyState {
        public boolean visible;
        public long expiresAtMs;
    }

    public static MyState parseMe(JSONObject j) {
        MyState s = new MyState();
        s.visible = j.optBoolean("visible", false);
        s.expiresAtMs = parseTime(j.optString("expires_at", ""));
        if (s.visible && s.expiresAtMs <= 0) {
            s.expiresAtMs = System.currentTimeMillis() + DEFAULT_VISIBLE_MS;
        }
        return s;
    }

    /** Parses {@code GET /v1/nearby}; drops entries without id/username and the user's own card. */
    public static List<Person> parseList(JSONObject j, String selfUsername) {
        ArrayList<Person> out = new ArrayList<>();
        JSONArray items = j.optJSONArray("items");
        if (items == null) return out;
        for (int i = 0; i < items.length() && out.size() < MAX_RESULTS; i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) continue;
            Person p = new Person();
            p.id = o.optString("id", "");
            p.username = o.optString("username", "");
            if (p.id.isEmpty() || p.username.isEmpty()) continue;
            if (selfUsername != null && p.username.equalsIgnoreCase(selfUsername)) continue;
            p.displayName = o.optString("display_name", p.username);
            if (p.displayName.isEmpty()) p.displayName = p.username;
            p.bio = o.optString("bio", "");
            p.avatarUrl = o.isNull("avatar_url") ? null : o.optString("avatar_url", null);
            if (p.avatarUrl != null && !p.avatarUrl.startsWith("https://")) p.avatarUrl = null;
            p.bucket = normalizeBucket(o.optString("distance_bucket", ""));
            out.add(p);
        }
        return out;
    }

    public static final String[] BUCKETS = {"500m", "1km", "3km", "10km", "far"};

    public static String normalizeBucket(String b) {
        for (String k : BUCKETS) {
            if (k.equals(b)) return k;
        }
        return "far";
    }

    /** Groups by bucket in {@link #BUCKETS} order (empty groups left out); order inside a group is the server's (random). */
    public static Map<String, List<Person>> group(List<Person> people) {
        LinkedHashMap<String, List<Person>> m = new LinkedHashMap<>();
        for (String b : BUCKETS) {
            ArrayList<Person> l = new ArrayList<>();
            for (Person p : people) {
                if (b.equals(p.bucket)) l.add(p);
            }
            if (!l.isEmpty()) m.put(b, Collections.unmodifiableList(l));
        }
        return m;
    }

    // ---------------------------------------------------------------- expiry

    /** When to show the "expires in 1 hour" reminder, or 0 if it is already too late. */
    /** Oldest last-known fix accepted when a single location request times out. */
    public static final long FALLBACK_MAX_AGE_MS = 30 * 60 * 1000L;

    public static boolean isFreshFix(long fixTimeMs, long nowMs) {
        return fixTimeMs > 0 && nowMs - fixTimeMs <= FALLBACK_MAX_AGE_MS && fixTimeMs <= nowMs + 60 * 1000L;
    }

    public static long reminderAt(long expiresAtMs, long nowMs) {
        long at = expiresAtMs - REMIND_BEFORE_MS;
        return at > nowMs ? at : 0;
    }

    public static boolean isExpired(long expiresAtMs, long nowMs) {
        return expiresAtMs > 0 && nowMs >= expiresAtMs;
    }

    static long parseTime(String s) {
        if (s == null || s.isEmpty()) return 0;
        String[] patterns = {"yyyy-MM-dd'T'HH:mm:ss.SSSX", "yyyy-MM-dd'T'HH:mm:ssX"};
        for (String p : patterns) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(p, Locale.US);
                f.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date d = f.parse(s);
                if (d != null) return d.getTime();
            } catch (ParseException ignore) {
            }
        }
        return 0;
    }

    // ---------------------------------------------------------------- report

    public static final String[] REPORT_REASONS = {"spam", "scam", "porn", "impersonation", "other"};

    public static JSONObject reportBody(String userId, String reason) throws Exception {
        return new JSONObject().put("user", userId).put("reason", reason);
    }

    // ---------------------------------------------------------------- texts

    public static final class Texts {
        private final boolean zh;

        public Texts(boolean zh) {
            this.zh = zh;
        }

        private String t(String zhText, String enText) {
            return zh ? zhText : enText;
        }

        public String title() { return t("附近的人", "People Nearby"); }
        public String featureDesc() { return t("用 Luckgram 服务端找到附近同样开启的人，直接发起私聊", "Find Luckgram users near you who have turned it on, and message them"); }

        public String needUsernameTitle() { return t("需要先设置用户名", "Set a username first"); }
        public String needUsernameText() { return t("别人通过你的公开用户名打开和你的私聊。设置用户名后再回来。", "Others open a chat with you through your public username. Set one, then come back."); }
        public String goUsername() { return t("去设置用户名", "Set username"); }

        public String introTitle() { return t("开始前请了解", "Before you start"); }
        public String introText() {
            return t("• 打开「在附近可见」后，会上传你的名片（显示名、头像、简介、用户名）和模糊位置（约 1 公里网格，原始坐标不离开手机）。\n"
                    + "• 只有同样打开的 Luckgram 用户能看到你，看到的是距离档位，不是位置。\n"
                    + "• 24 小时后自动关闭，到期前 1 小时提醒你续期。\n"
                    + "• 随时可以关闭；关闭或退出登录后，名片会立即从服务端删除。\n"
                    + "• 只在打开页面、刷新、续期时定位一次，不会后台定位。\n"
                    + "• 浏览列表时（包括关闭可见时）也会用同样的模糊位置向服务端查询；为防止定位追踪，服务端会短期保留你上次查询所在的约 1 公里网格，但不保存名片。",
                "• When \"Visible nearby\" is on, your card (name, photo, bio, username) and a blurred location (about a 1 km grid; the exact position never leaves your phone) are uploaded.\n"
                    + "• Only Luckgram users who also turned it on can see you, and they see a distance range, not a location.\n"
                    + "• It turns off after 24 hours; you get a reminder 1 hour before so you can renew.\n"
                    + "• Turn it off at any time. Turning it off or logging out deletes your card from the server right away.\n"
                    + "• Your location is read once when you open this page, refresh or renew, never in the background.\n"
                    + "• Browsing the list (also while not visible) sends the same blurred location to the server to find people; to prevent location tracking the server briefly keeps the ~1 km grid cell of your last search, but no card.");
        }
        public String introContinue() { return t("我知道了，继续", "Got it, continue"); }

        public String noPermission() { return t("没有定位权限，无法显示附近的人。可以在系统设置里允许 Luckgram 使用位置。", "No location permission, so nobody can be shown. Allow Luckgram to use your location in system settings."); }
        public String openSettings() { return t("打开系统设置", "Open settings"); }
        public String locationFailed() { return t("暂时无法获取位置，请点「刷新」重试", "Couldn't get your location. Tap Refresh to try again."); }
        public String locationOff() { return t("系统定位已关闭，请先打开定位服务", "Location services are off. Turn them on first."); }

        public String visibleSwitch() { return t("在附近可见", "Visible nearby"); }
        public String visibleOffInfo() { return t("关闭时只浏览：会用模糊位置查询附近的人，但不上传名片，别人看不到你。", "While off you only browse: a blurred location is used to search, but your card isn't uploaded and nobody can see you."); }
        public String visibleUntil(String time) { return t("可见到 " + time + "，到期前 1 小时提醒你续期。", "Visible until " + time + ". You'll be reminded 1 hour before."); }
        public String renew() { return t("续期 24 小时", "Renew for 24 hours"); }
        public String renewed() { return t("已续期 24 小时", "Renewed for 24 hours"); }
        public String myCard() { return t("我的名片", "My card"); }
        public String bio() { return t("一句话简介", "Bio"); }
        public String bioHint() { return t("介绍一下自己（最多 70 字）", "Say something about yourself (up to 70 characters)"); }
        public String bioEmpty() { return t("未填写", "Not set"); }
        public String showPhoto() { return t("显示头像", "Show photo"); }
        public String save() { return t("保存", "Save"); }
        public String cardSaved() { return t("名片已更新", "Card updated"); }

        public String listHeader() { return t("附近的人", "People nearby"); }
        public String empty() { return t("附近暂时没有打开可见的人，稍后刷新试试。", "Nobody nearby has turned it on yet. Try refreshing later."); }
        public String loading() { return t("正在查找附近的人…", "Looking for people nearby…"); }
        public String refresh() { return t("刷新", "Refresh"); }

        public String bucket(String b) {
            switch (b) {
                case "500m": return t("500 米内", "Within 500 m");
                case "1km": return t("1 公里内", "Within 1 km");
                case "3km": return t("3 公里内", "Within 3 km");
                case "10km": return t("10 公里内", "Within 10 km");
                default: return t("更远", "Farther");
            }
        }

        public String sendMessage() { return t("发消息", "Send message"); }
        public String report() { return t("举报", "Report"); }
        public String hide() { return t("不再显示此人", "Don't show this person"); }
        public String hideConfirm(String name) { return t("不再显示后，你和 " + name + " 在「附近的人」和「广场」里互相看不到对方。", "You and " + name + " will no longer see each other in People Nearby and Square."); }
        public String hidden() { return t("已不再显示此人", "You won't see this person again"); }
        public String reportTitle() { return t("举报原因", "Reason"); }
        public String reportReason(String r) {
            switch (r) {
                case "spam": return t("垃圾信息", "Spam");
                case "scam": return t("诈骗", "Scam");
                case "porn": return t("色情", "Pornography");
                case "impersonation": return t("冒充他人", "Impersonation");
                default: return t("其他", "Other");
            }
        }
        public String reported() { return t("已举报，感谢反馈", "Reported. Thank you."); }
        public String cancel() { return t("取消", "Cancel"); }

        public String reminderTitle() { return t("「在附近可见」1 小时后关闭", "\"Visible nearby\" turns off in 1 hour"); }
        public String reminderText() { return t("打开「附近的人」续期，继续让附近的人看到你。", "Open People Nearby to renew and stay visible."); }
        public String channelName() { return t("附近的人", "People Nearby"); }

        public String rateLimited(long seconds) {
            long min = Math.max(1, (seconds + 59) / 60);
            return t("操作太频繁，请 " + min + " 分钟后再试", "Too many requests. Try again in " + min + " min.");
        }
        public String disabled() { return t("附近的人暂时不可用，请稍后再试", "People Nearby is temporarily unavailable. Please try again later."); }
        public String underReview() { return t("你的名片被举报，正在审核，暂时不会出现在附近的人里", "Your card was reported and is under review. It won't appear nearby for now."); }
        public String connecting() { return t("正在连接附近的人服务…", "Connecting to People Nearby…"); }
        public String connectFailed() { return t("暂时无法连接附近的人服务，请稍后再试", "People Nearby isn't reachable right now. Please try again later."); }
        public String banned() { return t("你的账号已被限制使用附近的人", "Your account can't use People Nearby"); }
    }
}
