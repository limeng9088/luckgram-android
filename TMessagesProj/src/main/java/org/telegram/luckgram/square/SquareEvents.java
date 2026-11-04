package org.telegram.luckgram.square;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * luckgram-specs#115: Square behavior events for the recommended feed, without Android
 * dependencies (plain-JVM tests; same rules as iOS {@code SquareEvents.swift}).
 *
 * <p>{@link Tracker} turns "which posts are on screen now" into impression / view events;
 * {@link Queue} batches them for {@code POST /v1/square/events} (contract: specs#115, w-1138).
 * Events carry only a type, the post id, the tab, the list position, a dwell time and a
 * timestamp — never post text. Likes, comments, reports and blocks are recorded by the server.
 *
 * <pre>
 * POST /v1/square/events → 202 {"accepted":n}
 * {"events":[{"type":"impression","post_id":"…","tab":"recommend","rank":3,"at":"2026-11-04T10:00:00Z"},
 *            {"type":"view","post_id":"…","tab":"recommend","dwell_ms":5400,"at":"…"}, …]}
 * </pre>
 */
public final class SquareEvents {

    private SquareEvents() {
    }

    public static final String PATH = "/v1/square/events";

    public static final String IMPRESSION = "impression";
    /** Effective view: on screen for at least {@link #MIN_VIEW_MS}. */
    public static final String VIEW = "view";
    public static final String OPEN = "open";
    public static final String SHARE = "share";
    public static final String NOT_INTERESTED = "not_interested";

    /** {@code tab} values besides the feed tabs. */
    public static final String TAB_USER = "user";
    public static final String TAB_DETAIL = "detail";

    /** The server counts a view from 2 s on; shorter ones are reported too (contract). */
    public static final long MIN_VIEW_MS = 2000;
    /** A post left on screen (app in background, phone down) doesn't count longer than this. */
    public static final long MAX_DWELL_MS = 5 * 60 * 1000;
    public static final int BATCH_MAX = 100;
    /** The server drops events older than this; so do we. */
    public static final long MAX_AGE_MS = 24L * 60 * 60 * 1000;
    public static final int QUEUE_MAX = 500;
    public static final int FLUSH_AT = 20;
    public static final long FLUSH_INTERVAL_MS = 30_000;

    public static final class Event {
        public final String type, postId, tab;
        /** Position in the list from 0, or -1 when unknown. */
        public final int rank;
        public final long dwellMs;
        public final long timeMs;

        public Event(String type, String postId, String tab, int rank, long dwellMs, long timeMs) {
            this.type = type;
            this.postId = postId;
            this.tab = tab == null ? "" : tab;
            this.rank = rank;
            this.dwellMs = dwellMs;
            this.timeMs = timeMs;
        }

        public static Event of(String type, String postId, String tab, long now) {
            return new Event(type, postId, tab, -1, 0, now);
        }

        public JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject().put("type", type).put("post_id", postId);
            if (!tab.isEmpty()) o.put("tab", tab);
            if (rank >= 0) o.put("rank", rank);
            if (VIEW.equals(type)) o.put("dwell_ms", dwellMs);
            o.put("at", iso(timeMs));
            return o;
        }

        static Event parse(JSONObject o) {
            if (o == null || o.optString("type").isEmpty() || o.optString("post_id").isEmpty()) return null;
            return new Event(o.optString("type"), o.optString("post_id"), o.optString("tab", ""), o.optInt("rank", -1), o.optLong("dwell_ms", 0), parseIso(o.optString("at", "")));
        }
    }

    static String iso(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    static long parseIso(String s) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(s).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- tracker

    /** One feed list (one tab). Call {@link #update} whenever the visible posts may have changed. */
    public static final class Tracker {
        private final String tab;
        private final Map<String, Long> visibleSince = new HashMap<>();
        private final Map<String, Integer> ranks = new HashMap<>();
        private final Set<String> impressed = new HashSet<>();

        public Tracker(String tab) {
            this.tab = tab;
        }

        public String tab() {
            return tab;
        }

        /**
         * @param visible the posts now on screen (at least half their height) → their list position
         * @return new events: an impression the first time a post shows in this load, a view with
         * its dwell time when a post leaves the screen
         */
        public List<Event> update(Map<String, Integer> visible, long now) {
            ArrayList<Event> out = new ArrayList<>();
            for (Iterator<Map.Entry<String, Long>> it = visibleSince.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, Long> e = it.next();
                if (!visible.containsKey(e.getKey())) {
                    long dwell = Math.min(MAX_DWELL_MS, Math.max(0, now - e.getValue()));
                    Integer rank = ranks.get(e.getKey());
                    if (dwell > 0) out.add(new Event(VIEW, e.getKey(), tab, rank == null ? -1 : rank, dwell, now));
                    it.remove();
                }
            }
            for (Map.Entry<String, Integer> v : visible.entrySet()) {
                String id = v.getKey();
                ranks.put(id, v.getValue());
                if (!visibleSince.containsKey(id)) {
                    visibleSince.put(id, now);
                    if (impressed.add(id)) out.add(new Event(IMPRESSION, id, tab, v.getValue(), 0, now));
                }
            }
            return out;
        }

        /** The list left the screen (page closed, app in background): ends every view. */
        public List<Event> hideAll(long now) {
            return update(new HashMap<>(), now);
        }

        public Integer rankOf(String postId) {
            return ranks.get(postId);
        }

        /** A new list (refresh): posts may be shown — and counted — again. */
        public void reset() {
            visibleSince.clear();
            impressed.clear();
            ranks.clear();
        }
    }

    // ---------------------------------------------------------------- queue

    /** Events waiting to be sent, oldest first; persisted between launches. */
    public static final class Queue {
        private final ArrayList<Event> events = new ArrayList<>();
        private long lastFlushMs;
        /** Failed attempts of the batch at the head of the queue. */
        private int failures;

        public Queue(String stored, long now) {
            lastFlushMs = now;
            try {
                JSONArray arr = new JSONArray(stored == null || stored.isEmpty() ? "[]" : stored);
                for (int i = 0; i < arr.length(); i++) {
                    Event e = Event.parse(arr.optJSONObject(i));
                    if (e != null && now - e.timeMs <= MAX_AGE_MS) events.add(e);
                }
            } catch (Exception ignore) {
            }
            trim();
        }

        public void addAll(List<Event> list) {
            events.addAll(list);
            trim();
        }

        public void add(Event e) {
            events.add(e);
            trim();
        }

        private void trim() {
            while (events.size() > QUEUE_MAX) events.remove(0);
        }

        public int size() {
            return events.size();
        }

        public boolean shouldFlush(long now) {
            return !events.isEmpty() && (events.size() >= FLUSH_AT || now - lastFlushMs >= FLUSH_INTERVAL_MS);
        }

        /** The next request body, or null when empty; at most {@link #BATCH_MAX} events. */
        public JSONObject nextBatch() {
            if (events.isEmpty()) return null;
            JSONArray arr = new JSONArray();
            for (int i = 0; i < Math.min(BATCH_MAX, events.size()); i++) {
                try {
                    arr.put(events.get(i).toJson());
                } catch (Exception ignore) {
                }
            }
            try {
                return new JSONObject().put("events", arr);
            } catch (Exception e) {
                return null;
            }
        }

        /** The batch of {@code count} events was accepted. */
        public void onSent(int count, long now) {
            for (int i = 0; i < count && !events.isEmpty(); i++) events.remove(0);
            failures = 0;
            lastFlushMs = now;
        }

        /**
         * The batch failed. Contract: retry once, then drop it silently. A batch the server can't
         * accept at all (400, or 404 before the endpoint is deployed) is dropped right away.
         */
        public void onFailed(int count, int httpStatus, long now) {
            failures++;
            if (failures >= 2 || httpStatus == 400 || httpStatus == 404) {
                for (int i = 0; i < count && !events.isEmpty(); i++) events.remove(0);
                failures = 0;
            }
            lastFlushMs = now;
        }

        @Override
        public String toString() {
            JSONArray arr = new JSONArray();
            for (Event e : events) {
                try {
                    arr.put(e.toJson());
                } catch (Exception ignore) {
                }
            }
            return arr.toString();
        }
    }
}
