package org.telegram.luckgram.crash;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;

import org.json.JSONObject;
import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramSettings;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.FileLog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * specs#205: crash and ANR reports. A Java crash is written to a local file by the uncaught
 * exception handler (no network while the process dies); native crashes and ANRs are read from
 * the system's exit records ({@link ApplicationExitInfo}, Android 11+) on a later start. Pending
 * reports are uploaded to {@code POST /v1/crash} in the background some seconds after start.
 * Reports carry the stack, app version, device model, Android / ROM version and a few facts
 * (foreground, proxy kind, network kind), never account, phone number or message content.
 */
public final class LuckgramCrash {

    /** Local switch, Settings → Luckgram features (default on). */
    public static final String KEY_ENABLED = "crash_reports";
    private static final String PREFS = "luckgram_crash";
    private static final String KEY_EXIT_SEEN = "exit_seen";
    private static final String DIR = "luckgram_crash";
    private static final long UPLOAD_DELAY_MS = 20_000;
    /** Stack text limit before the server's crash.max_kb; also bounds memory in the handler. */
    private static final int MAX_STACK_CHARS = 128 * 1024;
    private static final int MAX_TRACE_BYTES = 4 << 20;
    private static final int MAX_UPLOADS_PER_START = 10;

    private static volatile boolean enabled = true;
    private static volatile File dir;
    private static volatile boolean installed;
    private static volatile boolean uploading;
    private static long processStart;

    private LuckgramCrash() {
    }

    // ---------------------------------------------------------------- strings

    public static String switchText() {
        return LuckgramStrings.get("发送崩溃报告", "Send crash reports");
    }

    public static String switchInfoText() {
        return LuckgramStrings.get("App 崩溃或卡死后，下次打开时把技术信息（出错位置、版本号、机型、系统版本、是否开着代理）发给 Luckgram，用于修复问题。不含手机号、账号、聊天内容和联系人。",
            "After the app crashes or freezes, technical details (where it failed, app version, phone model, Android version, whether a proxy was on) are sent to Luckgram the next time it opens, to fix the problem. No phone number, account, messages or contacts.");
    }

    // ---------------------------------------------------------------- setup

    /** Installs the handler. Call first thing in {@code Application.onCreate}. */
    public static void install(Context context) {
        if (installed || context == null) {
            return;
        }
        installed = true;
        processStart = SystemClock.elapsedRealtime();
        try {
            dir = new File(context.getFilesDir(), DIR);
            enabled = LuckgramSettings.getBool(KEY_ENABLED, true);
        } catch (Throwable t) {
            dir = null;
        }
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            try {
                if (enabled) {
                    writeJava(thread, e);
                }
            } catch (Throwable ignore) {
                // never get in the way of the crash itself
            }
            if (previous != null) {
                previous.uncaughtException(thread, e);
            } else {
                Process.killProcess(Process.myPid());
                System.exit(10);
            }
        });
    }

    /** Scans the exit records and uploads pending reports, in the background after a delay. */
    public static void scheduleUpload() {
        if (ApplicationLoader.applicationHandler == null) {
            return;
        }
        ApplicationLoader.applicationHandler.postDelayed(() -> background(() -> runNow(null)), UPLOAD_DELAY_MS);
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        LuckgramSettings.setBool(KEY_ENABLED, value);
        if (!value) {
            background(LuckgramCrash::deleteAll);
        }
    }

    /**
     * Scans and uploads now (background thread). {@code done} gets the number of reports
     * uploaded, or -1 when reporting is off. Used at start and by the debug page.
     */
    public static void runNow(org.telegram.messenger.Utilities.Callback<Integer> done) {
        int sent = -1;
        try {
            if (enabled && remoteEnabled()) {
                scanExitInfo();
                sent = upload();
            }
        } catch (Throwable t) {
            FileLog.e("luckgram crash upload: " + t.getClass().getSimpleName());
        }
        if (done != null) {
            int n = sent;
            org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> done.run(n));
        }
    }

    /** Own short-lived thread: uploads block on the network and must not hold up the AI client's queue. */
    public static void background(Runnable r) {
        Thread t = new Thread(r, "luckgram-crash");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static boolean remoteEnabled() {
        return LuckgramSettings.remoteConfig().getBool("crash.upload_enabled", true);
    }

    // ---------------------------------------------------------------- writing

    private static void writeJava(Thread thread, Throwable e) throws Exception {
        String stack = CrashLogic.javaStack(e, MAX_STACK_CHARS);
        JSONObject j = CrashLogic.report(CrashLogic.KIND_JAVA, System.currentTimeMillis(), facts(true, null), thread != null ? thread.getName() : null, stack);
        save(CrashLogic.KIND_JAVA, System.currentTimeMillis(), j);
    }

    private static void save(String kind, long ms, JSONObject j) throws Exception {
        File d = dir;
        if (d == null || (!d.isDirectory() && !d.mkdirs())) {
            return;
        }
        File f = new File(d, CrashLogic.fileName(ms, kind));
        File tmp = new File(d, f.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(j.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(f)) {
            tmp.delete();
        }
        prune(d);
    }

    private static void prune(File d) {
        String[] names = d.list();
        if (names == null) {
            return;
        }
        for (String n : CrashLogic.toDelete(Arrays.asList(names), CrashLogic.KEEP_FILES)) {
            new File(d, n).delete();
        }
    }

    private static void deleteAll() {
        File d = dir;
        File[] files = d != null ? d.listFiles() : null;
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
    }

    // ---------------------------------------------------------------- system exit records

    /** Native crashes and ANRs of earlier runs (Android 11+), as pending report files. */
    private static void scanExitInfo() {
        if (Build.VERSION.SDK_INT < 30) {
            return;
        }
        Context ctx = ApplicationLoader.applicationContext;
        ActivityManager am = ctx != null ? (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE) : null;
        if (am == null) {
            return;
        }
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long lastSeen = prefs.getLong(KEY_EXIT_SEEN, 0);
        long now = System.currentTimeMillis();
        long newest = lastSeen;
        List<ApplicationExitInfo> infos;
        try {
            infos = am.getHistoricalProcessExitReasons(ctx.getPackageName(), 0, 16);
        } catch (Throwable t) {
            return;
        }
        for (ApplicationExitInfo info : infos) {
            long ts = info.getTimestamp();
            newest = Math.max(newest, ts);
            int reason = info.getReason();
            if ((reason != ApplicationExitInfo.REASON_ANR && reason != ApplicationExitInfo.REASON_CRASH_NATIVE)
                    || !CrashLogic.shouldReportExit(ts, lastSeen, now)) {
                continue;
            }
            try {
                byte[] trace = readTrace(info);
                String kind;
                String stack;
                if (reason == ApplicationExitInfo.REASON_ANR) {
                    kind = CrashLogic.KIND_ANR;
                    stack = CrashLogic.anrText(info.getDescription(), trace != null ? new String(trace, StandardCharsets.UTF_8) : null, MAX_STACK_CHARS);
                } else {
                    kind = CrashLogic.KIND_NATIVE;
                    // the trace is the tombstone protobuf only on Android 12+
                    stack = CrashLogic.tombstoneText(info.getDescription(), Build.VERSION.SDK_INT >= 31 ? trace : null, MAX_STACK_CHARS);
                }
                if (stack == null) {
                    continue;
                }
                Map<String, String> extra = new LinkedHashMap<>();
                extra.put("importance", String.valueOf(info.getImportance()));
                extra.put("process", info.getProcessName() != null && info.getProcessName().contains(":") ? info.getProcessName().substring(info.getProcessName().indexOf(':')) : "main");
                JSONObject j = CrashLogic.report(kind, ts, facts(false, extra), "main", stack);
                save(kind, ts, j);
            } catch (Throwable t) {
                FileLog.e("luckgram crash exit info: " + t.getClass().getSimpleName());
            }
        }
        prefs.edit().putLong(KEY_EXIT_SEEN, newest > 0 ? newest : now).apply();
    }

    private static byte[] readTrace(ApplicationExitInfo info) {
        try (InputStream in = info.getTraceInputStream()) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 << 10];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < MAX_TRACE_BYTES) {
                out.write(buf, 0, Math.min(n, MAX_TRACE_BYTES - out.size()));
            }
            return out.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- upload

    private static int upload() throws Exception {
        if (uploading) {
            return 0;
        }
        uploading = true;
        try {
            File d = dir;
            String[] names = d != null ? d.list() : null;
            if (names == null) {
                return 0;
            }
            prune(d);
            ArrayList<String> pending = new ArrayList<>();
            for (String n : names) {
                if (CrashLogic.isReportFile(n) && new File(d, n).exists()) {
                    pending.add(n);
                }
            }
            java.util.Collections.sort(pending);
            int maxBytes = (int) Math.min(MAX_STACK_CHARS, LuckgramSettings.remoteConfig().getLong("crash.max_kb", 128) * 1024);
            int sent = 0;
            for (String n : pending.subList(0, Math.min(pending.size(), MAX_UPLOADS_PER_START))) {
                File f = new File(d, n);
                JSONObject j;
                try {
                    j = new JSONObject(new String(read(f), StandardCharsets.UTF_8));
                    j.put("stack", CrashLogic.cutUtf8(j.optString("stack", ""), maxBytes));
                } catch (Exception bad) {
                    f.delete();
                    continue;
                }
                int status = LuckgramAI.getInstance().postJsonBlocking("/v1/crash", j.toString().getBytes(StandardCharsets.UTF_8));
                if (CrashLogic.deleteAfterUpload(status)) {
                    f.delete();
                    if (status < 300) {
                        sent++;
                    }
                } else {
                    break; // offline or server trouble: try again on a later start
                }
            }
            return sent;
        } finally {
            uploading = false;
        }
    }

    private static byte[] read(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 << 10];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    // ---------------------------------------------------------------- facts

    /** App and device facts. Every lookup is guarded: this also runs inside the crash handler. */
    private static CrashLogic.Facts facts(boolean live, Map<String, String> extra) {
        CrashLogic.Facts f = new CrashLogic.Facts();
        f.appVersion = BuildConfig.BUILD_VERSION_STRING;
        int build = BuildConfig.LUCKGRAM_BUILD;
        if (build <= 0) {
            try {
                Context ctx = ApplicationLoader.applicationContext;
                PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
                build = pi.versionCode / 10;
            } catch (Throwable ignore) {
            }
        }
        f.build = Math.max(0, build);
        f.abi = Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : null;

        Map<String, String> device = new LinkedHashMap<>();
        device.put("manufacturer", Build.MANUFACTURER);
        device.put("brand", Build.BRAND);
        device.put("model", Build.MODEL);
        f.device = device;

        Map<String, String> os = new LinkedHashMap<>();
        os.put("sdk", String.valueOf(Build.VERSION.SDK_INT));
        os.put("release", Build.VERSION.RELEASE);
        String rom = rom();
        if (rom != null) os.put("rom", rom);
        f.os = os;

        Map<String, String> c = new LinkedHashMap<>();
        if (live) {
            c.put("foreground", String.valueOf(!ApplicationLoader.mainInterfacePaused));
            c.put("uptime_s", String.valueOf((SystemClock.elapsedRealtime() - processStart) / 1000));
            c.put("proxy", proxyKind());
            c.put("network", networkKind());
        }
        if (extra != null) c.putAll(extra);
        f.context = c;
        return f;
    }

    private static String proxyKind() {
        try {
            org.telegram.messenger.SharedConfig.ProxyInfo info = org.telegram.messenger.SharedConfig.currentProxy;
            if (org.telegram.messenger.SharedConfig.isProxyEnabled() && info != null && org.telegram.luckgram.xray.XrayManager.isXray(info)) {
                return org.telegram.luckgram.xray.XrayManager.isRunning() ? "xray" : "xray_not_running";
            }
            return org.telegram.luckgram.diag.LuckgramNet.currentRoute().label;
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static String networkKind() {
        try {
            ConnectivityManager cm = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities nc = cm != null && Build.VERSION.SDK_INT >= 23 ? cm.getNetworkCapabilities(cm.getActiveNetwork()) : null;
            if (nc == null) return "none";
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return "vpn";
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "wifi";
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "cellular";
            return "other";
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /** ROM name and version (ColorOS, MIUI / HyperOS, EMUI / HarmonyOS, One UI, …), or the build display id. */
    private static String rom() {
        String[][] props = {
            {"ro.build.version.opporom", "ColorOS"},
            {"ro.miui.ui.version.name", "MIUI"},
            {"ro.mi.os.version.name", "HyperOS"},
            {"ro.build.version.emui", ""},
            {"ro.vivo.os.build.display.id", ""},
            {"ro.build.version.oneui", "OneUI"},
            {"ro.build.display.id", ""},
        };
        for (String[] p : props) {
            String v = prop(p[0]);
            if (v != null && !v.isEmpty()) {
                return p[1].isEmpty() || v.startsWith(p[1]) ? v : p[1] + " " + v;
            }
        }
        return null;
    }

    private static String prop(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Method get = c.getMethod("get", String.class);
            return (String) get.invoke(null, key);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- debug (specs#205 acceptance)

    /** Debug page: a Java crash on the main thread. */
    public static void testJavaCrash() {
        throw new IllegalStateException("Luckgram crash report test (Java)");
    }

    /** Debug page: a native crash (SIGSEGV), recorded by the system as REASON_CRASH_NATIVE. */
    public static void testNativeCrash() {
        Process.sendSignal(Process.myPid(), 11);
    }

    /** Debug page: blocks the main thread; touch the screen during it to get a system ANR. */
    public static void testFreeze() {
        SystemClock.sleep(15_000);
    }
}
