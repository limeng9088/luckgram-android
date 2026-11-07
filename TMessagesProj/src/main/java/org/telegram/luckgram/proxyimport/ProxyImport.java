package org.telegram.luckgram.proxyimport;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.util.TypedValue;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.luckgram.proxyexit.Socks5Https;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.utils.proxy.ProxySettings;

import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * specs#118: add proxies from a scanned or typed link, and import / refresh / delete mihomo and
 * Clash subscriptions (SOCKS5 only). Subscriptions are kept on this device only
 * (SharedPreferences {@code luckgram_proxy_subs}); proxies go into Telegram's own proxy list.
 */
public final class ProxyImport {

    private static final String PREFS = "luckgram_proxy_subs";
    private static final int TIMEOUT_MS = 20_000;
    private static final String USER_AGENT = "clash.meta";

    private ProxyImport() {
    }

    /** A saved subscription. */
    public static final class Sub {
        public final String url;
        public long updated;
        public final Set<String> keys = new LinkedHashSet<>();

        Sub(String url) {
            this.url = url;
        }

        public String title() {
            String h = ProxyImportLogic.hostOf(url);
            return h != null ? h : url;
        }
    }

    // ------------------------------------------------------------------ store

    public static List<Sub> subs() {
        List<Sub> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs().getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Sub s = new Sub(o.getString("url"));
                s.updated = o.optLong("updated");
                JSONArray k = o.optJSONArray("keys");
                if (k != null) for (int j = 0; j < k.length(); j++) s.keys.add(k.getString(j));
                out.add(s);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return out;
    }

    private static void save(List<Sub> list) {
        JSONArray a = new JSONArray();
        try {
            for (Sub s : list) {
                JSONObject o = new JSONObject();
                o.put("url", s.url);
                o.put("updated", s.updated);
                o.put("keys", new JSONArray(new ArrayList<>(s.keys)));
                a.put(o);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        prefs().edit().putString("list", a.toString()).apply();
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ proxies

    static ProxySettings toSettings(ProxyImportLogic.Entry e) {
        ProxySettings.Builder b = ProxySettings.builder().setAddress(e.host).setPort(e.port);
        if (ProxyImportLogic.TYPE_MTPROTO.equals(e.type)) {
            b.setType(ProxySettings.Type.MTPROTO).setSecret(e.secret);
        } else {
            b.setType(ProxySettings.Type.SOCKS5).setUser(e.user).setPassword(e.password);
        }
        return b.build();
    }

    static String keyOf(ProxySettings s) {
        if (s.getType() == ProxySettings.Type.SOCKS5) {
            return new ProxyImportLogic.Entry(ProxyImportLogic.TYPE_SOCKS5, s.getAddress(), s.getPort(), s.getUser(), s.getPassword(), "").key();
        }
        if (s.getType() == ProxySettings.Type.MTPROTO) {
            return new ProxyImportLogic.Entry(ProxyImportLogic.TYPE_MTPROTO, s.getAddress(), s.getPort(), "", "", s.getSecret()).key();
        }
        return null;
    }

    private static Set<String> existingKeys() {
        SharedConfig.loadProxyList();
        Set<String> out = new HashSet<>();
        for (SharedConfig.ProxyInfo i : SharedConfig.proxyList) {
            String k = keyOf(i.settings);
            if (k != null) out.add(k);
        }
        return out;
    }

    private static void removeKeys(List<String> keys) {
        if (keys.isEmpty()) return;
        Set<String> set = new HashSet<>(keys);
        for (SharedConfig.ProxyInfo i : new ArrayList<>(SharedConfig.proxyList)) {
            String k = keyOf(i.settings);
            if (k != null && set.contains(k)) SharedConfig.deleteProxy(i);
        }
    }

    private static void changed() {
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
    }

    // ------------------------------------------------------------------ UI entry points

    /** "输入链接": a box for a proxy link or a subscription URL. */
    public static void showInput(BaseFragment fragment) {
        Context ctx = fragment.getParentActivity();
        if (ctx == null) return;
        EditTextBoldCursor field = new EditTextBoldCursor(ctx);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        field.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        field.setHint(T.inputHint());
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        field.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField), Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated), Theme.getColor(Theme.key_text_RedRegular));
        FrameLayout box = new FrameLayout(ctx);
        box.addView(field, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 40, 0, 24, 6, 24, 0));
        AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle(T.inputTitle())
                .setMessage(T.inputMessage())
                .setView(box)
                .setPositiveButton(T.add(), (d, w) -> handle(fragment, field.getText().toString()))
                .setNegativeButton(LocaleController.getString(org.telegram.messenger.R.string.Cancel), null)
                .create();
        fragment.showDialog(dialog);
        field.requestFocus();
        AndroidUtilities.runOnUIThread(() -> AndroidUtilities.showKeyboard(field), 200);
    }

    /** A scanned or typed text: add the proxy, import the subscription, or say it is unknown. */
    public static void handle(BaseFragment fragment, String raw) {
        String text = raw == null ? "" : raw.trim();
        switch (ProxyImportLogic.kind(text)) {
            case ProxyImportLogic.KIND_PROXY: {
                ProxyImportLogic.Entry e = ProxyImportLogic.parseLink(text);
                boolean existed = existingKeys().contains(e.key());
                SharedConfig.addProxy(new SharedConfig.ProxyInfo(toSettings(e)));
                changed();
                BulletinFactory.of(fragment).createSimpleBulletin(org.telegram.messenger.R.raw.contact_check, existed ? T.alreadyAdded() : T.added()).show();
                break;
            }
            case ProxyImportLogic.KIND_SUBSCRIPTION:
                importSub(fragment, text, null);
                break;
            default:
                alert(fragment, T.unknownTitle(), T.unknown());
        }
    }

    /** Download and apply a subscription; {@code existing} is null for a new one. */
    public static void importSub(BaseFragment fragment, String url, Sub existing) {
        Context ctx = fragment.getParentActivity();
        if (ctx == null) return;
        AlertDialog progress = new AlertDialog(ctx, AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(false);
        progress.show();
        ProxySettings via = SharedConfig.isProxyEnabled() && SharedConfig.currentProxy != null
                && SharedConfig.currentProxy.settings.getType() == ProxySettings.Type.SOCKS5 ? SharedConfig.currentProxy.settings : null;
        Utilities.globalQueue.postRunnable(() -> {
            String body = null;
            try {
                body = download(url, via);
            } catch (Throwable t) {
                FileLog.e(t);
            }
            final String result = body;
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    progress.dismiss();
                } catch (Exception ignore) {
                }
                if (result == null) {
                    alert(fragment, T.importTitle(), T.downloadFailed());
                    return;
                }
                apply(fragment, url, existing, ProxyImportLogic.parseSubscription(result));
            });
        });
    }

    static String download(String url, ProxySettings via) throws Exception {
        URL u = new URL(url);
        try {
            return Socks5Https.getDirect(u, TIMEOUT_MS, ProxyImportLogic.MAX_BYTES, USER_AGENT);
        } catch (Exception direct) {
            if (via == null || !"https".equalsIgnoreCase(u.getProtocol())) throw direct;
            return Socks5Https.getViaSocks5(via.getAddress(), via.getPort(), via.getUser(), via.getPassword(), u, TIMEOUT_MS, ProxyImportLogic.MAX_BYTES, USER_AGENT);
        }
    }

    private static void apply(BaseFragment fragment, String url, Sub existing, ProxyImportLogic.Result r) {
        List<Sub> list = subs();
        Sub sub = null;
        for (Sub s : list) if (s.url.equals(url)) sub = s;
        if (sub == null) {
            sub = new Sub(url);
            list.add(sub);
        }
        ProxyImportLogic.Diff d = ProxyImportLogic.diff(sub.keys, r.proxies, existingKeys());
        removeKeys(d.remove);
        for (ProxyImportLogic.Entry e : d.add) SharedConfig.addProxy(new SharedConfig.ProxyInfo(toSettings(e)));
        // the subscription owns what it added now or before; a proxy that was already in the list
        // (added by hand) stays the user's and is never removed with the subscription
        Set<String> owned = ProxyImportLogic.owned(sub.keys, r.proxies, d);
        sub.keys.clear();
        sub.keys.addAll(owned);
        sub.updated = System.currentTimeMillis();
        save(list);
        changed();
        alert(fragment, existing == null ? T.importTitle() : T.refreshTitle(), T.result(r.proxies.size(), d.add.size(), d.remove.size(), r.skipped));
    }

    /** Tap on a subscription row: refresh / delete / cancel. */
    public static void showSubMenu(BaseFragment fragment, Sub sub) {
        Context ctx = fragment.getParentActivity();
        if (ctx == null) return;
        new AlertDialog.Builder(ctx)
                .setTitle(sub.title())
                .setItems(new CharSequence[]{T.refresh(), T.delete()}, (d, which) -> {
                    if (which == 0) {
                        importSub(fragment, sub.url, sub);
                    } else {
                        confirmDelete(fragment, sub);
                    }
                })
                .setNegativeButton(LocaleController.getString(org.telegram.messenger.R.string.Cancel), null)
                .show();
    }

    private static void confirmDelete(BaseFragment fragment, Sub sub) {
        AlertDialog dialog = new AlertDialog.Builder(fragment.getParentActivity())
                .setTitle(T.deleteTitle())
                .setMessage(T.deleteMessage(sub.keys.size()))
                .setPositiveButton(T.delete(), (d, w) -> {
                    removeKeys(new ArrayList<>(sub.keys));
                    List<Sub> list = subs();
                    for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).url.equals(sub.url)) list.remove(i);
                    save(list);
                    changed();
                })
                .setNegativeButton(LocaleController.getString(org.telegram.messenger.R.string.Cancel), null)
                .create();
        fragment.showDialog(dialog);
        android.widget.TextView b = (android.widget.TextView) dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
        if (b != null) b.setTextColor(Theme.getColor(Theme.key_text_RedBold));
    }

    private static void alert(BaseFragment fragment, String title, String message) {
        if (fragment.getParentActivity() == null) return;
        fragment.showDialog(new AlertDialog.Builder(fragment.getParentActivity())
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(LocaleController.getString(org.telegram.messenger.R.string.OK), null)
                .create());
    }

    /** "N 个代理 · 更新于 11-07 15:20". */
    public static String subLine(Sub s) {
        String when = s.updated > 0 ? new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(new java.util.Date(s.updated)) : "—";
        return T.subLine(s.keys.size(), when);
    }

    // ------------------------------------------------------------------ texts

    public static final class T {
        private static String t(String zh, String en) {
            return LuckgramStrings.get(zh, en);
        }

        public static String scanRow() { return t("扫码添加代理", "Scan QR Code"); }
        public static String linkRow() { return t("输入链接或订阅地址", "Add from Link or Subscription"); }
        public static String subsHeader() { return t("订阅", "Subscriptions"); }
        static String inputTitle() { return t("添加代理", "Add Proxy"); }
        static String inputMessage() { return t("粘贴代理链接（tg://socks、t.me/proxy、socks5://）或 mihomo / Clash 订阅地址。", "Paste a proxy link (tg://socks, t.me/proxy, socks5://) or a mihomo / Clash subscription URL."); }
        static String inputHint() { return t("链接或订阅地址", "Link or subscription URL"); }
        static String add() { return t("添加", "Add"); }
        static String added() { return t("已添加代理", "Proxy added"); }
        static String alreadyAdded() { return t("这个代理已在列表里", "This proxy is already in the list"); }
        static String unknownTitle() { return t("无法识别", "Not recognized"); }
        static String unknown() { return t("无法识别的代理链接。支持 tg://socks、t.me/proxy、socks5:// 链接和 mihomo / Clash 订阅地址。", "This is not a proxy link. Supported: tg://socks, t.me/proxy, socks5:// links and mihomo / Clash subscription URLs."); }
        static String importTitle() { return t("导入订阅", "Import Subscription"); }
        static String refreshTitle() { return t("刷新订阅", "Refresh Subscription"); }
        static String downloadFailed() { return t("下载订阅失败，请检查地址和网络后重试。", "Could not download the subscription. Check the URL and your connection, then try again."); }
        static String refresh() { return t("刷新", "Refresh"); }
        static String delete() { return t("删除订阅", "Delete Subscription"); }
        static String deleteTitle() { return t("删除订阅？", "Delete subscription?"); }
        static String deleteMessage(int n) { return t("将同时删除由这个订阅导入的 " + n + " 个代理。手动添加的代理不受影响。", "The " + n + " proxies imported from it will be removed too. Proxies you added yourself stay."); }
        static String subLine(int n, String when) { return t(n + " 个代理 · 更新于 " + when, n + (n == 1 ? " proxy" : " proxies") + " · updated " + when); }

        static String result(int total, int added, int removed, int skipped) {
            StringBuilder b = new StringBuilder();
            b.append(t("订阅里有 " + total + " 个可用代理：新增 " + added + " 个", total + " usable " + (total == 1 ? "proxy" : "proxies") + ": " + added + " added"));
            if (removed > 0) b.append(t("，移除 " + removed + " 个", ", " + removed + " removed"));
            b.append(t("。", "."));
            if (skipped > 0) {
                b.append("\n\n").append(t("跳过 " + skipped + " 个不支持的节点（Luckgram 只支持 SOCKS5 和 MTProto 代理，VLESS、Shadowsocks、VMess 等用不了）。",
                        "Skipped " + skipped + " unsupported " + (skipped == 1 ? "entry" : "entries") + " (Luckgram supports SOCKS5 and MTProto proxies only, not VLESS, Shadowsocks, VMess and others)."));
            }
            return b.toString();
        }
    }
}
