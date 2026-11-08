package org.telegram.luckgram.xray;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.luckgram.LuckgramSettings;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.utils.proxy.ProxySettings;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * specs#119: the Xray nodes of this device and the in-process core that serves them.
 *
 * <p>Each node is a SOCKS5 proxy at {@code 127.0.0.1:<localPort>} in Telegram's own proxy list,
 * so Telegram connects, pings and switches as usual. One Xray instance has a loopback SOCKS5
 * inbound per node (username / password random per install, so other apps on the phone cannot
 * use it) routed to that node's outbound. No VpnService: only Luckgram's own traffic.
 *
 * <p>Node data (links contain UUIDs / passwords) is stored encrypted with an Android Keystore key
 * of its own, survives logout like Telegram's proxies, and is gone on uninstall.
 */
public final class XrayManager {

    public static final String FEATURE_KEY = "xray_core";

    private static final String PREFS = "luckgram_xray";
    private static final String ALIAS = "luckgram_xray_key";
    private static final String LOCAL_HOST = "127.0.0.1";
    private static final String USER = "lg";

    private static final ExecutorService QUEUE = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "luckgram-xray");
        t.setDaemon(true);
        return t;
    });

    /** A stored node. */
    public static final class Rec {
        public String link;
        public String protocol;
        public String host;
        public int port;
        public String name;
        public int localPort;
        public String outbound;

        public String key() {
            int hash = link.indexOf('#');
            return "xray|" + (hash >= 0 ? link.substring(0, hash) : link);
        }

        /** "VLESS · host:port" or the name when there is one. */
        public String title() {
            String hp = (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
            return name == null || name.isEmpty() ? hp : name;
        }

        public String detail() {
            return XrayLogic.label(protocol) + " · " + (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
        }
    }

    private static boolean loaded;
    private static String password;
    private static int portBase;
    private static final List<Rec> nodes = new ArrayList<>();
    private static volatile boolean running;
    private static volatile String lastError;

    private XrayManager() {
    }

    // ------------------------------------------------------------------ state

    /** The switch in Luckgram features and the core being part of this build. */
    public static boolean enabled() {
        return LuckgramSettings.isEnabled(FEATURE_KEY) && XrayCore.available();
    }

    public static boolean isRunning() {
        return running;
    }

    /** Why a node cannot be used right now, or null when the core is running. */
    public static String problem() {
        if (!XrayCore.available()) return T.notIncluded();
        if (!LuckgramSettings.isEnabled(FEATURE_KEY)) return T.switchedOff();
        if (running) return null;
        return lastError == null ? T.starting() : T.notRunning(lastError);
    }

    public static synchronized List<Rec> list() {
        load();
        return new ArrayList<>(nodes);
    }

    /** The node behind one of Telegram's proxies, or null when it is an ordinary proxy. */
    public static synchronized Rec forSettings(ProxySettings s) {
        if (s == null || s.getType() != ProxySettings.Type.SOCKS5 || !LOCAL_HOST.equals(s.getAddress()) || !USER.equals(s.getUser())) return null;
        load();
        for (Rec r : nodes) if (r.localPort == s.getPort()) return r;
        return null;
    }

    public static boolean isXray(SharedConfig.ProxyInfo info) {
        return info != null && forSettings(info.settings) != null;
    }

    // ------------------------------------------------------------------ changes

    /**
     * Adds (or finds) the node for a share link and returns the proxy to put in Telegram's list,
     * or null when libXray cannot build it. Call {@link #apply()} after a batch.
     */
    public static synchronized ProxySettings register(XrayLogic.Node node) {
        load();
        Rec rec = null;
        String key = node.key();
        for (Rec r : nodes) if (r.key().equals(key)) rec = r;
        if (rec == null) {
            String outbound = XrayCore.outboundFor(node.link);
            if (outbound == null) return null;
            Set<Integer> used = new HashSet<>();
            for (Rec r : nodes) used.add(r.localPort);
            List<Integer> ports = XrayLogic.pickPorts(portBase, used, 1);
            if (ports.isEmpty()) return null;
            rec = new Rec();
            rec.link = node.link;
            rec.protocol = node.protocol;
            rec.host = node.host;
            rec.port = node.port;
            rec.name = node.name;
            rec.localPort = ports.get(0);
            rec.outbound = outbound;
            nodes.add(rec);
            save();
        } else if (!node.name.equals(rec.name)) {
            rec.name = node.name;
            rec.link = node.link;
            save();
        }
        return settingsFor(rec);
    }

    public static ProxySettings settingsFor(Rec rec) {
        return ProxySettings.builder()
                .setType(ProxySettings.Type.SOCKS5)
                .setAddress(LOCAL_HOST)
                .setPort(rec.localPort)
                .setUser(USER)
                .setPassword(password)
                .build();
    }

    /**
     * Drops nodes whose proxy is no longer in Telegram's list (deleted there) and restarts the core
     * when something was dropped or {@code forceApply} is set.
     */
    public static void reconcile(boolean forceApply) {
        boolean changed = false;
        synchronized (XrayManager.class) {
            load();
            SharedConfig.loadProxyList();
            Set<Integer> live = new HashSet<>();
            for (SharedConfig.ProxyInfo i : SharedConfig.proxyList) {
                ProxySettings s = i.settings;
                if (s.getType() == ProxySettings.Type.SOCKS5 && LOCAL_HOST.equals(s.getAddress()) && USER.equals(s.getUser())) live.add(s.getPort());
            }
            for (int i = nodes.size() - 1; i >= 0; i--) {
                if (!live.contains(nodes.get(i).localPort)) {
                    nodes.remove(i);
                    changed = true;
                }
            }
            if (changed) save();
        }
        if (changed || forceApply) apply();
    }

    /** Restarts the core with the current nodes (stops it when there are none or it is switched off). */
    public static void apply() {
        QUEUE.execute(XrayManager::applyNow);
    }

    private static void applyNow() {
        List<XrayLogic.Run> runs = new ArrayList<>();
        String pass;
        synchronized (XrayManager.class) {
            load();
            for (Rec r : nodes) runs.add(new XrayLogic.Run(r.localPort, r.outbound));
            pass = password;
        }
        XrayCore.stop();
        running = false;
        String error = null;
        if (enabled() && !runs.isEmpty()) {
            try {
                error = XrayCore.run(XrayLogic.buildConfig(runs, USER, pass));
            } catch (Exception e) {
                error = "invalid config";
            }
            running = error == null;
        }
        lastError = error;
        if (error != null) FileLog.e("luckgram xray: core did not start"); // no details: may name local ports only, but keep logs clean
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged));
    }

    /**
     * At launch, before Telegram connects: when the selected proxy is an Xray node the core is
     * started synchronously so the first connection does not fail; otherwise in the background.
     */
    public static void onAppStart() {
        try {
            boolean any;
            synchronized (XrayManager.class) {
                load();
                any = !nodes.isEmpty();
            }
            if (!any) return;
            if (SharedConfig.isProxyEnabled() && isXray(SharedConfig.currentProxy)) {
                applyNow();
            } else {
                apply();
            }
        } catch (Throwable t) {
            FileLog.e("luckgram xray: start failed");
        }
    }

    /** Feature switch toggled. */
    public static void onToggled(boolean on) {
        apply();
    }

    // ------------------------------------------------------------------ storage

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        nodes.clear();
        String json = decrypt(prefs().getString("data", null));
        try {
            if (json != null) {
                JSONObject o = new JSONObject(json);
                password = o.optString("pass", null);
                portBase = o.optInt("base", 0);
                JSONArray a = o.optJSONArray("nodes");
                if (a != null) {
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject n = a.getJSONObject(i);
                        Rec r = new Rec();
                        r.link = n.getString("link");
                        r.protocol = n.optString("protocol");
                        r.host = n.optString("host");
                        r.port = n.optInt("port");
                        r.name = n.optString("name");
                        r.localPort = n.getInt("local");
                        r.outbound = n.getString("outbound");
                        nodes.add(r);
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e("luckgram xray: stored nodes unreadable");
            nodes.clear();
        }
        if (password == null || password.isEmpty() || portBase < 20000) {
            SecureRandom rnd = new SecureRandom();
            byte[] b = new byte[18];
            rnd.nextBytes(b);
            password = Base64.encodeToString(b, Base64.NO_WRAP | Base64.URL_SAFE | Base64.NO_PADDING);
            portBase = 20000 + rnd.nextInt(35000);
            save();
        }
    }

    private static void save() {
        try {
            JSONArray a = new JSONArray();
            for (Rec r : nodes) {
                a.put(new JSONObject()
                        .put("link", r.link).put("protocol", r.protocol).put("host", r.host).put("port", r.port)
                        .put("name", r.name).put("local", r.localPort).put("outbound", r.outbound));
            }
            String json = new JSONObject().put("pass", password).put("base", portBase).put("nodes", a).toString();
            String enc = encrypt(json);
            if (enc != null) prefs().edit().putString("data", enc).apply();
        } catch (Exception e) {
            FileLog.e("luckgram xray: save failed");
        }
    }

    private static SecretKey key(boolean create) throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        KeyStore.Entry entry = ks.getEntry(ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        if (!create) return null;
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return gen.generateKey();
    }

    private static String encrypt(String plain) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key(true));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(c.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    private static String decrypt(String stored) {
        if (stored == null) return null;
        try {
            int sep = stored.indexOf(':');
            SecretKey k = key(false);
            if (sep < 0 || k == null) return null;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(128, Base64.decode(stored.substring(0, sep), Base64.NO_WRAP)));
            return new String(c.doFinal(Base64.decode(stored.substring(sep + 1), Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ texts

    public static final class T {
        private static String t(String zh, String en) {
            return LuckgramStrings.get(zh, en);
        }

        public static String notIncluded() { return t("这个版本没有内置代理内核", "This build has no built-in proxy core"); }
        public static String switchedOff() { return t("内置代理内核已关闭（设置 → Luckgram 功能）", "Built-in proxy core is off (Settings → Luckgram features)"); }
        public static String starting() { return t("内核启动中…", "Starting core…"); }
        public static String notRunning(String why) { return t("内核未运行：", "Core not running: ") + why; }
        public static String copyLink() { return t("复制分享链接", "Copy Share Link"); }
        public static String linkCopied() { return t("链接已复制", "Link copied"); }
        public static String delete() { return t("删除代理", "Delete Proxy"); }
        public static String unavailable() { return t("这个节点现在用不了", "This node can't be used now"); }
    }
}
