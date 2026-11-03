package org.telegram.luckgram.scan;

import java.util.Locale;

/**
 * specs#92: chat list 「+」 menu → 扫一扫. What a scanned QR code is, so it goes to Telegram's own flow:
 * login token, proxy, a Telegram link (profile / group / channel), another web link, or plain text.
 * Also the proxy state shown next to 「代理配置」 and the texts.
 *
 * <p>Pure Java (java.* only), unit-tested in {@code ScanLogicTest}.
 */
public final class ScanLogic {

    /** Feature switch (default on); off = the chat list title bar is Telegram's again. */
    public static final String FEATURE_KEY = "chat_plus_menu";

    public static final int KIND_TEXT = 0;
    public static final int KIND_LOGIN = 1;
    public static final int KIND_PROXY = 2;
    public static final int KIND_TELEGRAM = 3;
    public static final int KIND_WEB = 4;

    public static final String LOGIN_PREFIX = "tg://login?token=";

    public static final int PROXY_OFF = 0;
    public static final int PROXY_CONNECTING = 1;
    public static final int PROXY_CONNECTED = 2;

    private ScanLogic() {
    }

    public static int kind(String raw) {
        if (raw == null) return KIND_TEXT;
        String text = raw.trim();
        if (text.isEmpty() || text.contains("\n") || text.contains(" ")) return KIND_TEXT;
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.startsWith(LOGIN_PREFIX)) return KIND_LOGIN;
        if (isProxy(lower)) return KIND_PROXY;
        if (lower.startsWith("tg:")) return KIND_TELEGRAM;
        String host = webHost(lower);
        if (host == null) return KIND_TEXT;
        if (isTelegramHost(host)) return pathOf(lower).isEmpty() ? KIND_WEB : KIND_TELEGRAM;
        return KIND_WEB;
    }

    /** {@code tg://proxy|socks|webproxy} and {@code t.me/proxy|socks} (also telegram.me / telegram.dog). */
    static boolean isProxy(String lower) {
        for (String p : new String[]{"tg:proxy", "tg://proxy", "tg:socks", "tg://socks", "tg:webproxy", "tg://webproxy"}) {
            if (lower.startsWith(p + "?")) return true;
        }
        String host = webHost(lower);
        if (host == null || !isTelegramHost(host)) return false;
        String path = pathOf(lower);
        return path.equals("proxy") || path.equals("socks");
    }

    /** The URL to open for a scanned text: adds https:// to a bare t.me/… link. */
    public static String url(String raw) {
        String text = raw == null ? "" : raw.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://") && !lower.startsWith("tg:")) {
            return "https://" + text;
        }
        return text;
    }

    public static int proxyState(boolean enabled, boolean connected) {
        if (!enabled) return PROXY_OFF;
        return connected ? PROXY_CONNECTED : PROXY_CONNECTING;
    }

    /** host of http(s)://host/… or a bare t.me/… ; null otherwise. */
    private static String webHost(String lower) {
        String rest;
        if (lower.startsWith("https://")) rest = lower.substring(8);
        else if (lower.startsWith("http://")) rest = lower.substring(7);
        else if (lower.startsWith("t.me/") || lower.startsWith("telegram.me/") || lower.startsWith("telegram.dog/")) rest = lower;
        else return null;
        int end = rest.length();
        for (char c : new char[]{'/', '?', '#'}) {
            int i = rest.indexOf(c);
            if (i >= 0 && i < end) end = i;
        }
        String host = rest.substring(0, end);
        if (host.startsWith("www.")) host = host.substring(4);
        return host.isEmpty() ? null : host;
    }

    private static boolean isTelegramHost(String host) {
        return host.equals("t.me") || host.equals("telegram.me") || host.equals("telegram.dog");
    }

    /** First path segment after the host. */
    private static String pathOf(String lower) {
        int start = lower.indexOf("://");
        String rest = start >= 0 ? lower.substring(start + 3) : lower;
        int slash = rest.indexOf('/');
        if (slash < 0) return "";
        String path = rest.substring(slash + 1);
        int end = path.length();
        for (char c : new char[]{'/', '?', '#'}) {
            int i = path.indexOf(c);
            if (i >= 0 && i < end) end = i;
        }
        return path.substring(0, end);
    }

    public static final class Texts {
        private final boolean zh;

        public Texts(boolean zh) {
            this.zh = zh;
        }

        private String t(String zhText, String en) {
            return zh ? zhText : en;
        }

        public String plus() { return t("更多", "More"); }
        public String scan() { return t("扫一扫", "Scan"); }
        public String proxy() { return t("代理配置", "Proxy settings"); }
        public String proxyState(int state) {
            switch (state) {
                case PROXY_CONNECTED: return t("已连接", "Connected");
                case PROXY_CONNECTING: return t("连接中", "Connecting");
                default: return t("未开启", "Off");
            }
        }
        public String scanHint() { return t("扫描 Telegram 二维码：加好友、加群、添加代理或在其他设备登录", "Scan a Telegram QR code: add a friend, join a group, add a proxy or log in on another device"); }
        public String myQr() { return t("我的二维码", "My QR code"); }
        public String cameraDenied() { return t("没有相机权限，请在系统设置里允许 Luckgram 使用相机", "No camera access. Allow Luckgram to use the camera in system settings"); }
        public String openSettings() { return t("去设置", "Settings"); }
        public String notNow() { return t("以后再说", "Not now"); }
        public String loginTitle() { return t("在其他设备登录", "Log in on another device"); }
        public String loginConfirm() { return t("确定要用当前账号登录扫码的设备吗？只确认你自己的设备。", "Log in to the scanned device with this account? Only confirm devices that are yours."); }
        public String login() { return t("登录", "Log in"); }
        public String loginDone() { return t("已在其他设备登录", "Logged in on the other device"); }
        public String text() { return t("扫描结果", "Scan result"); }
        public String copy() { return t("复制", "Copy"); }
        public String copied() { return t("已复制", "Copied"); }
        public String cancel() { return t("取消", "Cancel"); }
    }
}
