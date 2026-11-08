package org.telegram.luckgram.xray;

import org.json.JSONObject;

import java.lang.reflect.Method;

/**
 * specs#119: thin wrapper over libXray's single entry point {@code LibXray.invoke(requestJson)}
 * (gomobile AAR built in CI, {@code TMessagesProj/libs/libXray.aar}).
 *
 * <p>Called by reflection so builds without the AAR (local builds, other flavors) still compile;
 * {@link #available()} is then false and Xray nodes show "core not included".
 */
public final class XrayCore {

    private static final int API_VERSION = 3;
    private static Method invoke;
    private static boolean looked;

    private XrayCore() {
    }

    private static synchronized Method method() {
        if (!looked) {
            looked = true;
            try {
                invoke = Class.forName("libXray.LibXray").getMethod("invoke", String.class);
            } catch (Throwable t) {
                invoke = null;
            }
        }
        return invoke;
    }

    public static boolean available() {
        return method() != null;
    }

    /** {@code {"success":..,"data":..,"error":..}}; never throws. */
    private static JSONObject call(String method, JSONObject payload) {
        Method m = method();
        if (m == null) return failure("core not included");
        try {
            JSONObject req = new JSONObject().put("apiVersion", API_VERSION).put("method", method);
            if (payload != null) req.put("payload", payload);
            String res = (String) m.invoke(null, req.toString());
            return res == null ? failure("empty response") : new JSONObject(res);
        } catch (Throwable t) {
            return failure(t.getClass().getSimpleName());
        }
    }

    private static JSONObject failure(String error) {
        try {
            return new JSONObject().put("success", false).put("error", error);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** The Xray outbound for one canonical share link, or null when libXray cannot build it. */
    public static String outboundFor(String link) {
        try {
            JSONObject r = call("convertShareLinksToXrayJson", new JSONObject().put("text", link));
            if (!r.optBoolean("success")) return null;
            Object data = r.opt("data");
            return data == null ? null : XrayLogic.firstOutbound(data.toString());
        } catch (Exception e) {
            return null;
        }
    }

    /** Starts the core; null on success, otherwise the error (never contains credentials: libXray strips them). */
    public static String run(String xrayJson) {
        try {
            JSONObject r = call("runXray", new JSONObject().put("xrayJson", xrayJson));
            return r.optBoolean("success") ? null : shorten(r.optString("error", "unknown error"));
        } catch (Exception e) {
            return "invalid config";
        }
    }

    public static void stop() {
        call("stopXray", null);
    }

    public static boolean running() {
        JSONObject r = call("getXrayState", null);
        JSONObject d = r.optJSONObject("data");
        return r.optBoolean("success") && d != null && d.optBoolean("running");
    }

    public static String version() {
        JSONObject r = call("xrayVersion", null);
        JSONObject d = r.optJSONObject("data");
        return d == null ? "" : d.optString("version");
    }

    /** First line, at most 160 characters (Xray errors can be long chains). */
    private static String shorten(String s) {
        int nl = s.indexOf('\n');
        if (nl >= 0) s = s.substring(0, nl);
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }
}
