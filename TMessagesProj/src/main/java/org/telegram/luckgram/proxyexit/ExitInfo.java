package org.telegram.luckgram.proxyexit;

import org.json.JSONObject;

import java.util.Locale;

/**
 * specs#117: the exit (public IP + country) seen by the Luckgram server for a request sent
 * directly or through one proxy. Answer of {@code GET /v1/net/whoami} (specs#116).
 *
 * <p>Pure Java (java.* and org.json only), unit-tested in {@code ExitInfoTest}. Same format as
 * iOS {@code LuckgramProxyExit}.
 */
public final class ExitInfo {

    public static final String PATH = "/v1/net/whoami";
    /** Results are kept this long; the same proxy is not asked again within it. */
    public static final long TTL_MS = 10L * 60 * 1000;

    public final String ip;
    /** ISO 3166-1 alpha-2, upper case, or "" when unknown. */
    public final String country;
    public final String city;

    public ExitInfo(String ip, String country, String city) {
        this.ip = ip == null ? "" : ip.trim();
        String cc = country == null ? "" : country.trim().toUpperCase(Locale.ROOT);
        this.country = cc.matches("[A-Z]{2}") && !cc.equals("XX") && !cc.equals("T1") ? cc : "";
        this.city = city == null ? "" : city.trim();
    }

    /** Parses the whoami JSON; null when it has no usable IP. */
    public static ExitInfo parse(String json) {
        if (json == null) return null;
        try {
            JSONObject o = new JSONObject(json);
            ExitInfo e = new ExitInfo(o.optString("ip", ""), o.optString("country", ""), o.optString("city", ""));
            return e.ip.isEmpty() ? null : e;
        } catch (Exception ignore) {
            return null;
        }
    }

    /** Regional-indicator flag for a two-letter code, "" otherwise. */
    public static String flag(String cc) {
        if (cc == null || !cc.matches("[A-Z]{2}")) return "";
        int a = 0x1F1E6 + (cc.charAt(0) - 'A');
        int b = 0x1F1E6 + (cc.charAt(1) - 'A');
        return new String(Character.toChars(a)) + new String(Character.toChars(b));
    }

    /** Country name in {@code locale}, falling back to the code. */
    public static String countryName(String cc, Locale locale) {
        if (cc == null || cc.isEmpty()) return "";
        String n = new Locale("", cc).getDisplayCountry(locale);
        return n == null || n.isEmpty() ? cc : n;
    }

    /** "🇸🇬 新加坡 · 45.39.75.124", or just the IP when the country is unknown. */
    public String label(Locale locale) {
        if (country.isEmpty()) return ip;
        return flag(country) + " " + countryName(country, locale) + " · " + ip;
    }

    public static boolean isFresh(long checkedAtMs, long nowMs) {
        return checkedAtMs > 0 && nowMs - checkedAtMs >= 0 && nowMs - checkedAtMs < TTL_MS;
    }
}
