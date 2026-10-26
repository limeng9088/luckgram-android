package org.telegram.luckgram;

import org.json.JSONObject;

import java.util.Iterator;

/**
 * A parsed {@code GET /v1/config} response (task S4, issue #56):
 * <pre>
 * {"version": "…", "ttl": 3600,
 *  "values":   {"ai.transcribe_max_mb": 4, "quota.free.chat_per_day": 50, "features.x.enabled": false, …},
 *  "features": {"round_video_from_gallery": false, …}}
 * </pre>
 * {@code features} is the per-device result computed by the server (enabled, rollout_percent and
 * min_app_version already applied) and wins over {@code values}. Uses only org.json, so it can be
 * unit-tested on a plain JVM.
 *
 * <p>This is the data model only (task 00). Fetching, {@code If-None-Match} with {@link #version},
 * 304 handling ({@link LuckgramSettings#touchRemoteConfig()}) and TTL refresh belong to the S4 client.
 */
public final class LuckgramRemoteConfig {

    public final String version;
    public final long ttlSeconds;
    /** Wall-clock millis when this response was received (for TTL refresh, S4). */
    public final long fetchedAt;
    private final JSONObject values;
    private final JSONObject features;

    private LuckgramRemoteConfig(String version, long ttlSeconds, long fetchedAt, JSONObject values, JSONObject features) {
        this.version = version;
        this.ttlSeconds = ttlSeconds;
        this.fetchedAt = fetchedAt;
        this.values = values;
        this.features = features;
    }

    public static final LuckgramRemoteConfig EMPTY = new LuckgramRemoteConfig(null, 0, 0, new JSONObject(), new JSONObject());

    /** Parses a server response. The server always sends the full set, so this replaces earlier values. */
    public static LuckgramRemoteConfig fromResponse(JSONObject response, long now) {
        if (response == null) return EMPTY;
        JSONObject values = response.optJSONObject("values");
        JSONObject features = response.optJSONObject("features");
        if (values == null && features == null) {
            // Not the documented shape: treat it as a flat "key": value object.
            values = response;
        }
        String version = response.has("version") && !response.isNull("version") ? String.valueOf(response.opt("version")) : null;
        return new LuckgramRemoteConfig(version, Math.max(0, response.optLong("ttl", 0)), now,
            values == null ? new JSONObject() : values, features == null ? new JSONObject() : features);
    }

    /** Whether the server allows the feature; true when the server says nothing about it. */
    public boolean isAllowed(String featureKey) {
        Object v = features.opt(featureKey);
        if (v instanceof Boolean) return (Boolean) v;
        v = values.opt("features." + featureKey + ".enabled");
        if (v instanceof Boolean) return (Boolean) v;
        return true;
    }

    /** A number from {@code values}, or {@code def} (the built-in default with the same name). */
    public long getLong(String key, long def) {
        Object v = values.opt(key);
        return v instanceof Number ? ((Number) v).longValue() : def;
    }

    public String getString(String key, String def) {
        Object v = values.opt(key);
        return v instanceof String ? (String) v : def;
    }

    public boolean getBool(String key, boolean def) {
        Object v = values.opt(key);
        return v instanceof Boolean ? (Boolean) v : def;
    }

    /** Same data, received again at {@code now} (HTTP 304 Not Modified). */
    public LuckgramRemoteConfig withFetchedAt(long now) {
        return new LuckgramRemoteConfig(version, ttlSeconds, now, values, features);
    }

    public boolean isExpired(long now) {
        return fetchedAt == 0 || now - fetchedAt >= ttlSeconds * 1000L;
    }

    // ---------------------------------------------------------------- persistence

    public String serialize() {
        try {
            JSONObject o = new JSONObject();
            if (version != null) o.put("version", version);
            o.put("ttl", ttlSeconds);
            o.put("fetched_at", fetchedAt);
            o.put("values", values);
            o.put("features", features);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    public static LuckgramRemoteConfig deserialize(String s) {
        if (s == null || s.isEmpty()) return EMPTY;
        try {
            JSONObject o = new JSONObject(s);
            LuckgramRemoteConfig c = fromResponse(o, o.optLong("fetched_at", 0));
            return c;
        } catch (Exception e) {
            return EMPTY;
        }
    }

    /** Number of entries, for debugging. */
    public int size() {
        int n = 0;
        for (Iterator<String> it = values.keys(); it.hasNext(); it.next()) n++;
        for (Iterator<String> it = features.keys(); it.hasNext(); it.next()) n++;
        return n;
    }
}
