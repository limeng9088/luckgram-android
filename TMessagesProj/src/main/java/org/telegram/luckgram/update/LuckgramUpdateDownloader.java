package org.telegram.luckgram.update;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import org.telegram.luckgram.LuckgramSettings;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.luckgram.diag.LuckgramDiagLog;
import org.telegram.luckgram.diag.LuckgramNet;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.ui.ActionBar.AlertDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * specs#124: downloads the APK inside the app (over the in-app proxy like every Luckgram request,
 * specs#72), checks sha256 / size, keeps it in the app's private cache and hands it to the system
 * installer through the FileProvider. The user always confirms the installation on the system screen.
 */
public final class LuckgramUpdateDownloader {

    public interface Callback {
        void onProgress(int percent);

        void onDone(File apk);

        void onFailed(UpdateDownloadLogic.Failure failure, String detail);
    }

    public static final String KEY_AUTO = "update_auto_download";
    private static final String KEY_AUTO_FAILURE = "update_auto_failure";
    private static final String DIR = "luckgram-update";

    private static final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LuckgramUpdate");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    // main thread only
    private static boolean downloading;
    private static boolean downloadingAuto;
    private static String downloadingName;
    private static int percent = -1;
    private static Callback uiCallback;
    /** One flag per download, so a new download never revives a cancelled one. */
    private static AtomicBoolean cancelFlag = new AtomicBoolean(true);
    private static boolean cleanedThisRun;
    /** Set while the user is in the system "install unknown apps" page. */
    private static String pendingInstall;

    private LuckgramUpdateDownloader() {
    }

    public static UpdateDownloadLogic.Texts texts() {
        return new UpdateDownloadLogic.Texts(LuckgramStrings.isChinese());
    }

    public static boolean autoEnabled() {
        return LuckgramSettings.getBool(KEY_AUTO, true);
    }

    public static void setAutoEnabled(boolean on) {
        LuckgramSettings.setBool(KEY_AUTO, on);
        if (!on && downloading && downloadingAuto && uiCallback == null) {
            cancel();
        }
    }

    static File dir() {
        File d = new File(ApplicationLoader.applicationContext.getCacheDir(), DIR);
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    private static String nameFor(UpdateLogic.State s) {
        return UpdateDownloadLogic.fileName(s.latest, s.build);
    }

    /** The verified package for {@code s}, or null. Only verified files get the final name. */
    public static File readyFile(UpdateLogic.State s) {
        if (s == null || !s.updateAvailable) {
            return null;
        }
        File f = new File(dir(), nameFor(s));
        if (!f.isFile() || f.length() <= 0 || (s.size > 0 && f.length() != s.size)) {
            return null;
        }
        return f;
    }

    public static boolean isDownloading(UpdateLogic.State s) {
        return downloading && s != null && nameFor(s).equals(downloadingName);
    }

    public static int percent() {
        return percent;
    }

    /** Main thread. Deletes packages of other versions (and all of them once the update is installed). Once per run. */
    public static void cleanupOnce(UpdateLogic.State s) {
        if (cleanedThisRun || downloading) {
            return;
        }
        cleanedThisRun = true;
        final String keep = s != null && s.updateAvailable ? nameFor(s) : null;
        executor.execute(() -> {
            File d = dir();
            for (String n : UpdateDownloadLogic.toDelete(d.list(), keep)) {
                //noinspection ResultOfMethodCallIgnored
                new File(d, n).delete();
            }
        });
    }

    /** Main thread: start a background download when the rules allow it (Wi-Fi / unmetered, switch on). */
    public static void maybeAutoDownload(UpdateLogic.State s) {
        if (s == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long lastFailure = LuckgramSettings.prefs().getLong(KEY_AUTO_FAILURE, 0);
        if (!UpdateDownloadLogic.shouldAutoDownload(s.updateAvailable, s.url, autoEnabled(), isUnmetered(),
                readyFile(s) != null, downloading, now, lastFailure)) {
            return;
        }
        start(s, true, null);
    }

    private static boolean isUnmetered() {
        try {
            ConnectivityManager cm = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo info = cm != null ? cm.getActiveNetworkInfo() : null;
            return info != null && info.isConnected() && !cm.isActiveNetworkMetered();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Main thread. Starts (or joins) the download for {@code s}; {@code callback} (may be null)
     * gets progress and the result on the main thread. A user tap turns an automatic download into
     * a manual one, so turning the switch off no longer stops it.
     */
    public static void start(UpdateLogic.State s, boolean auto, Callback callback) {
        if (downloading) {
            if (nameFor(s).equals(downloadingName)) {
                uiCallback = callback;
                downloadingAuto = downloadingAuto && auto;
                if (callback != null) {
                    callback.onProgress(percent);
                }
                return;
            }
            cancel();
        }
        uiCallback = callback;
        if (!UpdateDownloadLogic.isHttps(s.url)) {
            finishFailed(UpdateDownloadLogic.Failure.NOT_HTTPS, null, auto);
            return;
        }
        downloading = true;
        downloadingAuto = auto;
        downloadingName = nameFor(s);
        percent = -1;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        cancelFlag = cancelled;
        changed();
        final String name = downloadingName;
        final String url = s.url;
        final String sha = s.sha256;
        final long size = s.size;
        executor.execute(() -> run(name, url, sha, size, cancelled));
    }

    /** Main thread. */
    public static void cancel() {
        cancelFlag.set(true);
        downloading = false;
        downloadingName = null;
        percent = -1;
        uiCallback = null;
        changed();
    }

    /** Main thread: the dialog went away but the download goes on. */
    public static void detach(Callback callback) {
        if (uiCallback == callback) {
            uiCallback = null;
        }
    }

    /**
     * specs#160: the server's URL, then its backup host (lg-dl.cat158.com) when the first one could
     * not be reached at all (DNS, connect, TLS, reset before a response; same rule as the API
     * entries of specs#81). An HTTP status or a failure after the response started does not switch.
     */
    private static void run(String name, String url, String sha, long size, AtomicBoolean cancelled) {
        boolean apiOnBackup;
        try {
            apiOnBackup = org.telegram.luckgram.LuckgramAI.endpoints().usingBackup();
        } catch (Throwable t) {
            apiOnBackup = false;
        }
        java.util.List<String> urls = org.telegram.luckgram.LuckgramAI.mirrors().downloadUrls(url, apiOnBackup);
        for (int i = 0; i < urls.size(); i++) {
            boolean last = i == urls.size() - 1;
            if (!runOnce(name, urls.get(i), sha, size, cancelled, last) || cancelled.get()) {
                return;
            }
        }
    }

    /** One try; returns true when it failed at the network level and {@code last} is false (try the next host). */
    private static boolean runOnce(String name, String url, String sha, long size, AtomicBoolean cancelled, boolean last) {
        long start = System.currentTimeMillis();
        File d = dir();
        File part = new File(d, name + UpdateDownloadLogic.PART);
        File done = new File(d, name);
        LuckgramNet.Route route = LuckgramNet.currentRoute();
        HttpURLConnection conn = null;
        int status = 0;
        Throwable failure = null;
        try {
            if (!UpdateDownloadLogic.enoughSpace(d.getUsableSpace(), size)) {
                post(name, () -> finishFailed(UpdateDownloadLogic.Failure.SPACE, null, downloadingAuto));
                return false;
            }
            conn = LuckgramNet.open(new URL(url), route);
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(30_000);
            conn.setInstanceFollowRedirects(true);
            status = conn.getResponseCode();
            if (status != 200) {
                final String detail = "HTTP " + status;
                post(name, () -> finishFailed(UpdateDownloadLogic.Failure.NETWORK, detail, downloadingAuto));
                return false;
            }
            if (!conn.getURL().getProtocol().equalsIgnoreCase("https")) {
                post(name, () -> finishFailed(UpdateDownloadLogic.Failure.NOT_HTTPS, null, downloadingAuto));
                return false;
            }
            long total = size > 0 ? size : conn.getContentLength();
            if (total > 0 && !UpdateDownloadLogic.enoughSpace(d.getUsableSpace(), total)) {
                post(name, () -> finishFailed(UpdateDownloadLogic.Failure.SPACE, null, downloadingAuto));
                return false;
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long read = 0;
            int lastPercent = -1;
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (cancelled.get()) {
                        break;
                    }
                    out.write(buf, 0, n);
                    md.update(buf, 0, n);
                    read += n;
                    if (read > UpdateDownloadLogic.MAX_SIZE) {
                        throw new IOException("too large");
                    }
                    if (total > 0) {
                        int p = (int) Math.min(100, read * 100 / total);
                        if (p != lastPercent) {
                            lastPercent = p;
                            post(name, () -> progress(p));
                        }
                    }
                }
            }
            if (cancelled.get()) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                return false;
            }
            UpdateDownloadLogic.Verdict v = UpdateDownloadLogic.verify(sha, size, UpdateDownloadLogic.hex(md.digest()), read);
            if (!UpdateDownloadLogic.accepted(v)) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                logNote("verify:" + v.name().toLowerCase());
                post(name, () -> finishFailed(UpdateDownloadLogic.Failure.VERIFY, null, downloadingAuto));
                return false;
            }
            if (v == UpdateDownloadLogic.Verdict.NOT_CHECKED) {
                logNote("verify:skipped, server sent no sha256");
            }
            //noinspection ResultOfMethodCallIgnored
            done.delete();
            if (!part.renameTo(done)) {
                throw new IOException("rename failed");
            }
            post(name, () -> finishDone(done));
        } catch (Throwable t) {
            failure = t;
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            if (!last && status == 0 && !cancelled.get() && org.telegram.luckgram.diag.LuckgramEndpoints.isNetworkFailure(t)) {
                return true; // logged in finally; the caller tries the backup host
            }
            String msg = String.valueOf(t.getMessage());
            boolean space = msg.contains("ENOSPC") || msg.toLowerCase().contains("no space");
            final String detail = t.getClass().getSimpleName();
            if (!cancelled.get()) {
                post(name, () -> finishFailed(space ? UpdateDownloadLogic.Failure.SPACE : UpdateDownloadLogic.Failure.NETWORK, space ? null : detail, downloadingAuto));
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
            String target = url;
            try {
                URL u = new URL(url);
                target = u.getHost() + u.getPath();
            } catch (Exception ignore) {
            }
            LuckgramNet.logHttp("update", "GET", target, status, failure, start, route.label, null);
        }
        return false;
    }

    private static void logNote(String text) {
        LuckgramDiagLog.Entry e = new LuckgramDiagLog.Entry();
        e.timeMs = System.currentTimeMillis();
        e.feature = "update";
        e.target = text;
        LuckgramNet.log(e);
    }

    /** Runs {@code r} on the main thread if the download {@code name} is still the current one. */
    private static void post(String name, Runnable r) {
        AndroidUtilities.runOnUIThread(() -> {
            if (downloading && name.equals(downloadingName)) {
                r.run();
            }
        });
    }

    private static void progress(int p) {
        boolean step = p / 5 != percent / 5;
        percent = p;
        if (uiCallback != null) {
            uiCallback.onProgress(p);
        }
        if (step) {
            changed(); // Settings row shows the percentage in 5 % steps
        }
    }

    private static void finishDone(File f) {
        Callback cb = uiCallback;
        downloading = false;
        downloadingName = null;
        uiCallback = null;
        percent = -1;
        LuckgramSettings.prefs().edit().remove(KEY_AUTO_FAILURE).apply();
        changed();
        if (cb != null) {
            cb.onDone(f);
        }
    }

    private static void finishFailed(UpdateDownloadLogic.Failure f, String detail, boolean auto) {
        Callback cb = uiCallback;
        downloading = false;
        downloadingName = null;
        uiCallback = null;
        percent = -1;
        if (auto && cb == null) {
            LuckgramSettings.prefs().edit().putLong(KEY_AUTO_FAILURE, System.currentTimeMillis()).apply();
        }
        changed();
        if (cb != null) {
            cb.onFailed(f, detail);
        }
    }

    private static void changed() {
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.luckgramUpdateChanged);
    }

    // ---------------------------------------------------------------- install

    /** Main thread. Opens the system installer; asks for "install unknown apps" first when needed. */
    public static void install(Activity activity, File apk) {
        if (activity == null || apk == null || !apk.isFile()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.getPackageManager().canRequestPackageInstalls()) {
            UpdateDownloadLogic.Texts t = texts();
            AlertDialog.Builder b = new AlertDialog.Builder(activity);
            b.setTitle(t.permissionTitle());
            b.setMessage(t.permissionText());
            b.setPositiveButton(t.openSettings(), (d, w) -> {
                pendingInstall = apk.getName();
                try {
                    activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + activity.getPackageName())));
                } catch (ActivityNotFoundException e) {
                    pendingInstall = null;
                    FileLog.e(e);
                }
            });
            b.setNegativeButton(t.cancel(), null);
            b.show();
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** LaunchActivity.onResume: back from the permission page, continue the installation. */
    public static void resumePendingInstall(Activity activity) {
        String name = pendingInstall;
        if (name == null || activity == null) {
            return;
        }
        pendingInstall = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.getPackageManager().canRequestPackageInstalls()) {
            return; // not granted: the Settings row / force page still offer "Install"
        }
        File f = new File(dir(), name);
        if (f.isFile()) {
            install(activity, f);
        }
    }
}
