package org.telegram.luckgram.discover;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.TimeZone;

/**
 * Mock of the "Discover" endpoints (S10 nearby, S11 square) for {@code LuckgramAIMock}, so the
 * pages run end to end before the server is live. Same paths, bodies and error format as agreed
 * with S10/S11. State lives in memory only. Only java.* and org.json (JVM unit tests).
 */
public final class DiscoverMock {

    public static final class Reply {
        public final int status;
        public final JSONObject body;

        Reply(int status, JSONObject body) {
            this.status = status;
            this.body = body;
        }
    }

    private final Random random = new Random();
    private boolean linked = true;

    // nearby
    private JSONObject myCard;
    private long myExpiresAt;
    private final Set<String> blocked = new HashSet<>();
    private final List<JSONObject> people = new ArrayList<>();
    private int nearbyUpdates;
    private int nearbyQueries;

    public DiscoverMock() {
        String[][] seed = {
            {"m1", "Alice", "喜欢咖啡和徒步", "500m"},
            {"m2", "Bob", "Android developer", "500m"},
            {"m3", "小林", "周末一起打球", "1km"},
            {"m4", "Carol", "", "1km"},
            {"m5", "Dave", "Photographer", "3km"},
            {"m6", "王五", "新搬来的邻居", "10km"},
            {"m7", "Eve", "Hi!", "far"},
        };
        for (String[] s : seed) {
            try {
                people.add(new JSONObject().put("id", s[0]).put("display_name", s[1]).put("bio", s[2])
                    .put("username", "mock_" + s[0]).put("distance_bucket", s[3]));
            } catch (Exception ignore) {
            }
        }
    }

    public synchronized void setLinked(boolean linked) {
        this.linked = linked;
    }

    public synchronized boolean isMyCardStored() {
        return myCard != null;
    }

    /** Last card body the client sent (to check coarsening in tests), or null. */
    public synchronized JSONObject lastCard() {
        return myCard;
    }

    /** Returns null when the path is not a discover endpoint. */
    public synchronized Reply handle(String method, String path, String query, byte[] body) throws Exception {
        if (path.equals("/v1/link")) {
            if ("DELETE".equals(method)) {
                linked = false;
                deleteMyCard();
                return ok(new JSONObject());
            }
            return ok(new JSONObject().put("linked", linked).put("devices", linked ? 1 : 0).put("max_devices", 3));
        }
        if (path.equals("/v1/blocks")) {
            if ("POST".equals(method)) {
                if (!linked) return error(403, "not_linked");
                String user = json(body).optString("user", "");
                if (user.isEmpty()) return error(400, "invalid_request");
                blocked.add(user);
                return ok(new JSONObject());
            }
            if (!linked) return error(403, "not_linked");
            JSONArray arr = new JSONArray();
            for (String b : blocked) arr.put(new JSONObject().put("user", b));
            return ok(new JSONObject().put("items", arr));
        }
        if (path.startsWith("/v1/blocks/") && "DELETE".equals(method)) {
            if (!linked) return error(403, "not_linked");
            blocked.remove(path.substring("/v1/blocks/".length()));
            return ok(new JSONObject());
        }
        if (path.equals("/v1/nearby") || path.startsWith("/v1/nearby/")) {
            return nearby(method, path, query, body);
        }
        return null;
    }

    private Reply nearby(String method, String path, String query, byte[] body) throws Exception {
        if (!linked) return error(403, "not_linked");
        switch (path) {
            case "/v1/nearby/me":
                if ("PUT".equals(method)) {
                    JSONObject card = json(body);
                    if (!card.has("lat") || !card.has("lon") || card.optString("username", "").isEmpty()) {
                        return error(400, "invalid_request");
                    }
                    if (decimals(card.get("lat").toString()) > 2 || decimals(card.get("lon").toString()) > 2) {
                        return error(400, "invalid_request");
                    }
                    if (++nearbyUpdates > 6) return rateLimited(600);
                    myCard = card;
                    myExpiresAt = System.currentTimeMillis() + 24L * 3600 * 1000;
                    return ok(new JSONObject().put("visible", true).put("expires_at", iso(myExpiresAt)));
                } else if ("DELETE".equals(method)) {
                    deleteMyCard();
                    return ok(new JSONObject());
                } else {
                    boolean visible = myCard != null && System.currentTimeMillis() < myExpiresAt;
                    JSONObject r = new JSONObject().put("visible", visible);
                    if (visible) r.put("expires_at", iso(myExpiresAt));
                    return ok(r);
                }
            case "/v1/nearby":
                if (!query.contains("lat=") || !query.contains("lon=")) return error(400, "invalid_request");
                if (++nearbyQueries > 30) return rateLimited(1200);
                ArrayList<JSONObject> list = new ArrayList<>();
                for (JSONObject p : people) {
                    if (!blocked.contains(p.optString("id"))) list.add(p);
                }
                Collections.shuffle(list, random);
                JSONArray arr = new JSONArray();
                for (JSONObject p : list) arr.put(p);
                return ok(new JSONObject().put("items", arr));
            case "/v1/nearby/report": {
                JSONObject r = json(body);
                if (r.optString("user", "").isEmpty() || r.optString("reason", "").isEmpty()) return error(400, "invalid_request");
                return ok(new JSONObject());
            }
            default:
                return error(404, "not_found");
        }
    }

    private void deleteMyCard() {
        myCard = null;
        myExpiresAt = 0;
    }

    /** Resets the per-hour counters (the real server uses a sliding hour). */
    public synchronized void resetLimits() {
        nearbyUpdates = 0;
        nearbyQueries = 0;
    }

    // ---------------------------------------------------------------- helpers

    static int decimals(String number) {
        int dot = number.indexOf('.');
        if (dot < 0) return 0;
        String frac = number.substring(dot + 1);
        int e = frac.toLowerCase(Locale.US).indexOf('e');
        if (e >= 0) return 99;
        return frac.length();
    }

    private static JSONObject json(byte[] body) throws Exception {
        if (body == null || body.length == 0) return new JSONObject();
        return new JSONObject(new String(body, StandardCharsets.UTF_8));
    }

    static String iso(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    static Reply ok(JSONObject o) {
        return new Reply(200, o);
    }

    static Reply error(int status, String code) throws Exception {
        return new Reply(status, new JSONObject().put("error", new JSONObject().put("code", code).put("message", code)));
    }

    static Reply rateLimited(long seconds) throws Exception {
        return new Reply(429, new JSONObject().put("error", new JSONObject().put("code", "rate_limited")
            .put("message", "rate_limited").put("retry_after_seconds", seconds)));
    }
}
