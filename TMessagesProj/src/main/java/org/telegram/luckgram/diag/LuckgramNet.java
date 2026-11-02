package org.telegram.luckgram.diag;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import androidx.core.content.FileProvider;

import org.telegram.luckgram.LuckgramAI;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SharedConfig;
import org.telegram.utils.proxy.ProxySettings;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * specs#72: network plumbing shared by every request to the Luckgram API.
 * <ul>
 * <li>Requests follow the in-app Telegram proxy when it is SOCKS5 (read on every request, so turning
 * the proxy off takes effect at once); MTProto and web proxies can't carry HTTP, so those go direct.</li>
 * <li>Every request is written to a local diagnostic log (metadata only, see {@link LuckgramDiagLog}),
 * kept for 7 days / 2 MB and exported as .txt from the Luckgram settings page. Always on, independent
 * of Telegram's FileLog switch.</li>
 * </ul>
 */
public final class LuckgramNet {

    /** How one request leaves the device. */
    public static final class Route {
        public final String label;
        public final Proxy proxy;

        Route(String label, Proxy proxy) {
            this.label = label;
            this.proxy = proxy;
        }
    }

    private LuckgramNet() {
    }

    // ---------------------------------------------------------------- proxy

    private static volatile String socksHost;
    private static volatile int socksPort;
    private static volatile String socksUser;
    private static volatile String socksPassword;
    private static boolean authenticatorInstalled;

    /** The route for a request starting now. */
    public static Route currentRoute() {
        ProxySettings s = null;
        boolean enabled = false;
        try {
            SharedConfig.ProxyInfo info = SharedConfig.currentProxy;
            enabled = SharedConfig.isProxyEnabled() && info != null;
            s = info != null ? info.settings : null;
        } catch (Throwable ignore) {
        }
        String label = LuckgramProxyRoute.decide(enabled && s != null, s != null ? s.getType().name() : null,
            s != null ? s.getAddress() : null, s != null ? s.getPort() : 0);
        if (!LuckgramProxyRoute.SOCKS5.equals(label)) {
            return new Route(label, null);
        }
        socksHost = s.getAddress();
        socksPort = s.getPort();
        socksUser = s.getUser();
        socksPassword = s.getPassword();
        installAuthenticator();
        // Unresolved: the proxy resolves the name, so local DNS (often filtered) is never asked.
        return new Route(label, new Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(s.getAddress(), s.getPort())));
    }

    /** Opens {@code url} over {@code route}. */
    public static HttpURLConnection open(URL url, Route route) throws IOException {
        return (HttpURLConnection) (route.proxy != null ? url.openConnection(route.proxy) : url.openConnection(Proxy.NO_PROXY));
    }

    /** SOCKS5 user/password: answered only for the proxy currently in use. */
    private static synchronized void installAuthenticator() {
        if (authenticatorInstalled) return;
        authenticatorInstalled = true;
        Authenticator.setDefault(new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                String protocol = getRequestingProtocol();
                String user = socksUser;
                if (protocol == null || !protocol.toUpperCase(Locale.US).startsWith("SOCKS") || user == null || user.isEmpty()) {
                    return null;
                }
                String host = getRequestingHost();
                if (getRequestingPort() != socksPort || (host != null && !host.equalsIgnoreCase(socksHost))) {
                    return null;
                }
                String pass = socksPassword;
                return new PasswordAuthentication(user, (pass != null ? pass : "").toCharArray());
            }
        });
    }

    /** Whether {@code url} is on the Luckgram API host (AI, config, square media...). */
    public static boolean isApiUrl(String url) {
        try {
            String host = new URL(url).getHost();
            return host != null && host.equalsIgnoreCase(apiHost());
        } catch (Exception e) {
            return false;
        }
    }

    static String apiHost() {
        try {
            return new URL(LuckgramAI.apiBaseUrl()).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- diagnostic log

    private static final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LuckgramDiag");
        t.setDaemon(true);
        return t;
    });
    private static boolean trimmedThisRun;

    /** Logs one finished Luckgram API request. */
    public static void logHttp(String feature, String method, String target, int status, Throwable failure, long startMs, String proxy, String requestId) {
        LuckgramDiagLog.Entry e = new LuckgramDiagLog.Entry();
        e.timeMs = startMs;
        e.feature = feature;
        e.method = method;
        e.target = target;
        e.status = status;
        e.exception = failure != null ? failure.getClass().getSimpleName() : null;
        e.durationMs = Math.max(0, System.currentTimeMillis() - startMs);
        e.proxy = proxy;
        e.requestId = requestId;
        log(e);
    }

    /** Logs a failed Telegram request made before an AI request (e.g. history for a summary). */
    public static void logTelegram(String feature, String method, int code, String text) {
        LuckgramDiagLog.Entry e = new LuckgramDiagLog.Entry();
        e.timeMs = System.currentTimeMillis();
        e.feature = feature;
        e.target = method;
        e.tgCode = code;
        e.tgError = text != null ? text : "";
        log(e);
    }

    public static void log(LuckgramDiagLog.Entry e) {
        final String line;
        try {
            line = LuckgramDiagLog.format(e, apiHost());
        } catch (Throwable t) {
            return;
        }
        writer.execute(() -> {
            try {
                File f = logFile();
                if (!trimmedThisRun || f.length() > LuckgramDiagLog.MAX_BYTES) {
                    trimmedThisRun = true;
                    rewriteTrimmed(f);
                }
                try (Writer w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8)) {
                    w.write(line);
                    w.write('\n');
                }
            } catch (Throwable t) {
                FileLog.e("luckgram diag: " + t.getClass().getSimpleName());
            }
        });
    }

    private static File logFile() {
        File dir = new File(ApplicationLoader.getFilesDirFixed(), "luckgram");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, "diag.log");
    }

    private static List<String> readLines(File f) throws IOException {
        ArrayList<String> lines = new ArrayList<>();
        if (!f.exists()) return lines;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) lines.add(l);
        }
        return lines;
    }

    private static void rewriteTrimmed(File f) throws IOException {
        if (!f.exists()) return;
        // Leave room for new lines so the file isn't rewritten on every request near the limit.
        List<String> kept = LuckgramDiagLog.trim(readLines(f), System.currentTimeMillis(), LuckgramDiagLog.MAX_AGE_MS, LuckgramDiagLog.MAX_BYTES * 3 / 4);
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f, false), StandardCharsets.UTF_8)) {
            for (String l : kept) {
                w.write(l);
                w.write('\n');
            }
        }
    }

    /** Writes the log to a .txt and opens the system share sheet. {@code done} runs on the UI thread. */
    public static void export(Activity activity, String chooserTitle, Runnable onEmpty) {
        writer.execute(() -> {
            File out = null;
            boolean empty = true;
            try {
                File src = logFile();
                rewriteTrimmed(src);
                List<String> lines = readLines(src);
                empty = lines.isEmpty();
                File dir = new File(ApplicationLoader.getFilesDirFixed(), "cache");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File[] old = dir.listFiles((d, name) -> name.startsWith("luckgram-diag-") && name.endsWith(".txt"));
                if (old != null) for (File o : old) //noinspection ResultOfMethodCallIgnored
                    o.delete();
                String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date());
                out = new File(dir, "luckgram-diag-" + stamp + ".txt");
                try (Writer w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8)) {
                    w.write("Luckgram diagnostic log\n");
                    w.write("app=" + BuildConfig.BUILD_VERSION_STRING + " android=" + Build.VERSION.SDK_INT + "\n");
                    w.write("server=" + LuckgramDiagLog.route(LuckgramAI.apiBaseUrl(), null) + " proxy_now=" + currentRoute().label + "\n\n");
                    for (String l : lines) {
                        w.write(l);
                        w.write('\n');
                    }
                }
            } catch (Throwable t) {
                FileLog.e("luckgram diag export: " + t.getClass().getSimpleName());
                out = null;
            }
            final File file = out;
            final boolean isEmpty = empty;
            org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
                if (file == null || isEmpty) {
                    if (onEmpty != null) onEmpty.run();
                    return;
                }
                try {
                    Uri uri = FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", file);
                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType("text/plain");
                    intent.putExtra(Intent.EXTRA_STREAM, uri);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    activity.startActivity(Intent.createChooser(intent, chooserTitle));
                } catch (Throwable t) {
                    FileLog.e(t);
                    if (onEmpty != null) onEmpty.run();
                }
            });
        });
    }
}
