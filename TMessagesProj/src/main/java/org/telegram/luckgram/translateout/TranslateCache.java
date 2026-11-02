/*
 * Luckgram — spec 01: local cache of translation results. Licensed under GNU GPL v. 2 or later.
 */
package org.telegram.luckgram.translateout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * spec 01 「翻译服务」5: translation results keyed by (target language, text), at most
 * {@link #MAX_ENTRIES}, each kept {@link #MAX_AGE_MS}. Opening the same translation again shows the
 * cached result without a request (and without spending quota). Keys are hashes, so the cache file
 * holds only translations, not the original texts. Pure (java.* / org.json) so it is unit tested.
 */
public final class TranslateCache {

    public static final int MAX_ENTRIES = 2000;
    public static final long MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;

    public static final class Entry {
        public final String text;
        /** Source language, or null when unknown. */
        public final String fromLang;
        public final long timeMs;

        public Entry(String text, String fromLang, long timeMs) {
            this.text = text;
            this.fromLang = fromLang;
            this.timeMs = timeMs;
        }
    }

    /** Access order: the least recently used entry is evicted first. */
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(64, 0.75f, true);
    private final int maxEntries;
    private final long maxAgeMs;

    public TranslateCache() {
        this(MAX_ENTRIES, MAX_AGE_MS);
    }

    public TranslateCache(int maxEntries, long maxAgeMs) {
        this.maxEntries = maxEntries;
        this.maxAgeMs = maxAgeMs;
    }

    /** Cache key of {@code text} translated into {@code toLang}. */
    public static String key(String toLang, String text) {
        final String raw = (toLang == null ? "" : toLang) + '\u0000' + (text == null ? "" : text);
        try {
            final byte[] d = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            final StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode()) + ":" + raw.length();
        }
    }

    public synchronized Entry get(String key, long nowMs) {
        final Entry e = entries.get(key);
        if (e == null) {
            return null;
        }
        if (nowMs - e.timeMs > maxAgeMs || nowMs < e.timeMs - maxAgeMs) {
            entries.remove(key);
            return null;
        }
        return e;
    }

    public synchronized void put(String key, String text, String fromLang, long nowMs) {
        if (key == null || text == null || text.isEmpty()) {
            return;
        }
        entries.remove(key);
        entries.put(key, new Entry(text, fromLang, nowMs));
        while (entries.size() > maxEntries) {
            final Iterator<String> it = entries.keySet().iterator();
            it.next();
            it.remove();
        }
    }

    public synchronized int size() {
        return entries.size();
    }

    /** Drops expired entries; true if anything was removed. */
    public synchronized boolean prune(long nowMs) {
        boolean removed = false;
        final Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            if (nowMs - it.next().getValue().timeMs > maxAgeMs) {
                it.remove();
                removed = true;
            }
        }
        return removed;
    }

    /** JSON array, least recently used first, so {@link #parse} restores the same order. */
    public synchronized String serialize() {
        final JSONArray arr = new JSONArray();
        try {
            for (Map.Entry<String, Entry> me : entries.entrySet()) {
                final JSONObject o = new JSONObject();
                o.put("k", me.getKey());
                o.put("t", me.getValue().text);
                if (me.getValue().fromLang != null) {
                    o.put("f", me.getValue().fromLang);
                }
                o.put("d", me.getValue().timeMs);
                arr.put(o);
            }
        } catch (Exception ignore) {
        }
        return arr.toString();
    }

    /** Restores {@link #serialize} output; expired or malformed entries are skipped. */
    public static TranslateCache parse(String json, long nowMs) {
        final TranslateCache cache = new TranslateCache();
        if (json == null || json.isEmpty()) {
            return cache;
        }
        try {
            final JSONArray arr = new JSONArray(json);
            final List<JSONObject> items = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                final JSONObject o = arr.optJSONObject(i);
                if (o != null) {
                    items.add(o);
                }
            }
            for (JSONObject o : items) {
                final String k = o.optString("k", "");
                final String t = o.optString("t", "");
                final long d = o.optLong("d", 0);
                final String f = o.has("f") ? o.optString("f", null) : null;
                if (!k.isEmpty() && !t.isEmpty() && nowMs - d <= cache.maxAgeMs) {
                    cache.put(k, t, f, d);
                }
            }
        } catch (Exception ignore) {
        }
        return cache;
    }
}
