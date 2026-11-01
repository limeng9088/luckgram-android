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

    // square
    public static final String MY_ID = "me";
    public static final String UPLOAD_PREFIX = "/mock-upload/";
    private JSONObject squareProfile;
    private final List<JSONObject> posts = new ArrayList<>();
    private final List<JSONObject> comments = new ArrayList<>();
    private final Set<String> myLikes = new HashSet<>();
    private final Set<String> uploaded = new HashSet<>();
    private final List<Long> myPostTimes = new ArrayList<>();
    private final List<Long> myCommentTimes = new ArrayList<>();
    private int nextId = 100;

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
        long now = System.currentTimeMillis();
        for (int i = 0; i < 25; i++) {
            String[] who = seed[i % seed.length];
            try {
                JSONObject author = new JSONObject().put("id", who[0]).put("display_name", who[1]).put("username", "mock_" + who[0]).put("bio", who[2]);
                JSONArray images = new JSONArray();
                for (int k = 0; k < i % 4; k++) {
                    images.put(new JSONObject().put("url", "https://" + MOCK_HOST + "/mock-image/" + i + "_" + k + ".png").put("width", 1).put("height", 1));
                }
                posts.add(new JSONObject().put("id", "p" + i).put("author", author)
                    .put("text", (i % 2 == 0 ? "Mock 帖子 #" : "Mock post #") + i)
                    .put("images", images).put("like_count", (i * 7) % 13).put("comment_count", 0)
                    .put("created_at_ms", now - i * 3_600_000L).put("status", "published"));
            } catch (Exception ignore) {
            }
        }
    }

    /** Host of the in-app mock (LuckgramAIMock.HOST); kept here so this class stays Android-free. */
    public static final String MOCK_HOST = "luckgram-mock.invalid";

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
        if (path.startsWith("/v1/square/")) {
            return square(method, path, query, body);
        }
        return null;
    }

    /** Presigned upload (no auth header, like S3). True when the key was issued by this mock. */
    public synchronized boolean acceptUpload(String path, byte[] body) {
        if (!path.startsWith(UPLOAD_PREFIX) || body == null || body.length == 0) return false;
        String key = path.substring(UPLOAD_PREFIX.length());
        if (!key.startsWith("square/")) return false;
        uploaded.add(key);
        return true;
    }

    public synchronized boolean isUploaded(String key) {
        return uploaded.contains(key);
    }

    // ---------------------------------------------------------------- square

    private static String param(String query, String name) {
        if (query == null) return null;
        for (String kv : query.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && kv.substring(0, eq).equals(name)) {
                try {
                    return java.net.URLDecoder.decode(kv.substring(eq + 1), "UTF-8");
                } catch (Exception e) {
                    return kv.substring(eq + 1);
                }
            }
        }
        return null;
    }

    private JSONObject myAuthor() throws Exception {
        JSONObject p = squareProfile != null ? squareProfile : new JSONObject();
        JSONObject a = new JSONObject().put("id", MY_ID).put("display_name", p.optString("display_name", "Me")).put("bio", p.optString("bio", ""));
        if (p.has("username") && !p.optString("username", "").isEmpty()) a.put("username", p.optString("username"));
        return a;
    }

    private JSONObject findPost(String id) {
        for (JSONObject p : posts) if (p.optString("id").equals(id)) return p;
        return null;
    }

    private static boolean isMine(JSONObject o) {
        JSONObject a = o.optJSONObject("author");
        return a != null && MY_ID.equals(a.optString("id"));
    }

    private boolean visible(JSONObject o) {
        JSONObject a = o.optJSONObject("author");
        if (a != null && blocked.contains(a.optString("id"))) return false;
        return isMine(o) || "published".equals(o.optString("status"));
    }

    /** Reports per target id; at {@link #REPORT_HIDE_THRESHOLD} the content is hidden (S11: 3 different users; the mock has one). */
    private final java.util.Map<String, Integer> reports = new java.util.HashMap<>();
    public static final int REPORT_HIDE_THRESHOLD = 3;

    private void countReport(String target, String id) throws Exception {
        int n = (reports.containsKey(id) ? reports.get(id) : 0) + 1;
        reports.put(id, n);
        if (n < REPORT_HIDE_THRESHOLD) return;
        List<JSONObject> list = "comment".equals(target) ? comments : posts;
        for (JSONObject o : list) {
            if (o.optString("id").equals(id)) {
                o.put("status", "hidden").put("hidden_reason", "Mock: 被多名用户举报 / reported by several users");
            }
        }
    }

    private JSONObject out(JSONObject p) throws Exception {
        JSONObject o = new JSONObject(p.toString());
        o.remove("created_at_ms");
        o.put("created_at", iso(p.optLong("created_at_ms")));
        o.put("liked", linked && myLikes.contains(p.optString("id")));
        int n = 0;
        for (JSONObject c : comments) {
            if (c.optString("post_id").equals(p.optString("id")) && visible(c)) n++;
        }
        o.put("comment_count", n);
        if (!isMine(p)) o.remove("hidden_reason");
        return o;
    }

    private Reply page(List<JSONObject> list, String cursor) throws Exception {
        int from = 0;
        try {
            if (cursor != null) from = Integer.parseInt(cursor);
        } catch (NumberFormatException ignore) {
        }
        JSONArray arr = new JSONArray();
        int to = Math.min(list.size(), from + 20);
        for (int i = from; i < to; i++) arr.put(out(list.get(i)));
        JSONObject r = new JSONObject().put("items", arr);
        if (to < list.size()) r.put("next_cursor", Integer.toString(to));
        return ok(r);
    }

    private Reply limited(List<Long> times, long windowMs, int max) throws Exception {
        long now = System.currentTimeMillis();
        while (!times.isEmpty() && now - times.get(0) >= windowMs) times.remove(0);
        if (times.size() >= max) {
            return rateLimited(Math.max(1, (windowMs - (now - times.get(0)) + 999) / 1000));
        }
        times.add(now);
        return null;
    }

    private Reply square(String method, String path, String query, byte[] body) throws Exception {
        boolean write = !"GET".equals(method);
        if (write && !linked) return error(403, "not_linked");
        if (path.equals("/v1/square/feed")) {
            String tab = param(query, "tab");
            ArrayList<JSONObject> list = new ArrayList<>();
            for (JSONObject p : posts) if (visible(p)) list.add(p);
            if ("hot".equals(tab)) {
                Collections.sort(list, (a, b) -> Double.compare(hot(b), hot(a)));
            } else {
                Collections.sort(list, (a, b) -> Long.compare(b.optLong("created_at_ms"), a.optLong("created_at_ms")));
            }
            return page(list, param(query, "cursor"));
        }
        if (path.equals("/v1/square/profile")) {
            if ("PUT".equals(method)) {
                JSONObject p = json(body);
                if (p.optString("display_name", "").trim().isEmpty()) return error(400, "invalid_request");
                squareProfile = p;
                return ok(new JSONObject(p.toString()).put("id", MY_ID));
            }
            if (!linked) return error(403, "not_linked");
            return squareProfile == null ? error(404, "not_found") : ok(new JSONObject(squareProfile.toString()).put("id", MY_ID));
        }
        if (path.equals("/v1/square/me") && "DELETE".equals(method)) {
            ArrayList<JSONObject> keep = new ArrayList<>();
            for (JSONObject p : posts) if (!isMine(p)) keep.add(p);
            posts.clear();
            posts.addAll(keep);
            ArrayList<JSONObject> keepC = new ArrayList<>();
            for (JSONObject c : comments) if (!isMine(c) && findPost(c.optString("post_id")) != null) keepC.add(c);
            comments.clear();
            comments.addAll(keepC);
            for (String id : myLikes) {
                JSONObject p = findPost(id);
                if (p != null) p.put("like_count", Math.max(0, p.optInt("like_count") - 1));
            }
            myLikes.clear();
            squareProfile = null;
            return ok(new JSONObject());
        }
        if (path.equals("/v1/square/uploads") && "POST".equals(method)) {
            JSONObject r = json(body);
            int count = r.optInt("count", 0);
            String ct = r.optString("content_type");
            boolean video = "video/mp4".equals(ct);
            JSONArray sizes = r.optJSONArray("sizes");
            if (count < 1 || count > 9 || !("image/jpeg".equals(ct) || video) || sizes == null || sizes.length() != count) return error(400, "invalid_request");
            for (int i = 0; i < sizes.length(); i++) {
                if (sizes.optLong(i, 0) <= 0) return error(400, "invalid_request");
            }
            JSONArray ups = new JSONArray();
            for (int i = 0; i < count; i++) {
                String key = "square/mock_" + (nextId++) + (video ? ".mp4" : ".jpg");
                ups.put(new JSONObject().put("key", key).put("url", "https://" + MOCK_HOST + UPLOAD_PREFIX + key).put("expires_at", iso(System.currentTimeMillis() + 900_000)));
            }
            return ok(new JSONObject().put("uploads", ups));
        }
        if (path.equals("/v1/square/posts") && "POST".equals(method)) {
            if (squareProfile == null) return error(403, "profile_required");
            JSONObject r = json(body);
            String text = r.optString("text", "");
            JSONArray keys = r.optJSONArray("image_keys");
            int n = keys == null ? 0 : keys.length();
            if (text.codePointCount(0, text.length()) > 1000 || n > 9 || (text.trim().isEmpty() && n == 0)) return error(400, "invalid_request");
            JSONArray images = new JSONArray();
            for (int i = 0; i < n; i++) {
                if (!uploaded.contains(keys.getString(i))) return error(400, "invalid_request");
                images.put(new JSONObject().put("url", "https://" + MOCK_HOST + "/mock-image/" + keys.getString(i)).put("width", 1).put("height", 1));
            }
            String vk = r.optString("video_key", "");
            String ck = r.optString("cover_key", "");
            JSONObject video = null;
            if (!vk.isEmpty() || !ck.isEmpty()) {
                if (n > 0) return error(400, "invalid_request"); // attachments are exclusive
                if (!uploaded.contains(vk) || !uploaded.contains(ck) || !vk.endsWith(".mp4")) return error(400, "invalid_video");
                // The real server reads these from the file; the mock has no decoder.
                video = new JSONObject().put("url", "https://" + MOCK_HOST + "/mock-video/" + vk)
                    .put("cover_url", "https://" + MOCK_HOST + "/mock-image/" + ck)
                    .put("width", 1280).put("height", 720).put("duration_seconds", 15).put("size", 1);
            }
            Reply lim = limited(myPostTimes, 24L * 3600 * 1000, 10);
            if (lim != null) return lim;
            JSONObject p = new JSONObject().put("id", "p" + (nextId++)).put("author", myAuthor()).put("text", text)
                .put("images", images).put("like_count", 0).put("comment_count", 0)
                .put("created_at_ms", System.currentTimeMillis()).put("status", "published");
            if (video != null) p.put("video", video);
            posts.add(0, p);
            return ok(out(p));
        }
        if (path.startsWith("/v1/square/posts/")) {
            String rest = path.substring("/v1/square/posts/".length());
            String id = rest;
            String sub = "";
            int slash = rest.indexOf('/');
            if (slash >= 0) {
                id = rest.substring(0, slash);
                sub = rest.substring(slash + 1);
            }
            JSONObject p = findPost(id);
            if (p == null || !visible(p)) return error(404, "not_found");
            switch (sub) {
                case "":
                    if ("DELETE".equals(method)) {
                        if (!isMine(p)) return error(403, "forbidden");
                        posts.remove(p);
                        return ok(new JSONObject());
                    }
                    return ok(out(p));
                case "like":
                    if ("POST".equals(method)) {
                        if (myLikes.add(id)) p.put("like_count", p.optInt("like_count") + 1);
                    } else {
                        if (myLikes.remove(id)) p.put("like_count", Math.max(0, p.optInt("like_count") - 1));
                    }
                    return ok(new JSONObject().put("like_count", p.optInt("like_count")).put("liked", myLikes.contains(id)));
                case "comments":
                    if ("POST".equals(method)) {
                        if (squareProfile == null) return error(403, "profile_required");
                        JSONObject r = json(body);
                        String text = r.optString("text", "").trim();
                        if (text.isEmpty() || text.codePointCount(0, text.length()) > 300) return error(400, "invalid_request");
                        Reply lim = limited(myCommentTimes, 60_000, 5);
                        if (lim != null) return lim;
                        JSONObject c = new JSONObject().put("id", "c" + (nextId++)).put("post_id", id).put("author", myAuthor())
                            .put("text", text).put("created_at", iso(System.currentTimeMillis())).put("status", "published");
                        String parent = r.optString("parent_id", "");
                        if (!parent.isEmpty()) c.put("parent_id", parent);
                        comments.add(c);
                        return ok(c);
                    }
                    ArrayList<JSONObject> list = new ArrayList<>();
                    for (JSONObject c : comments) if (c.optString("post_id").equals(id) && visible(c)) list.add(c);
                    JSONArray arr = new JSONArray();
                    for (JSONObject c : list) arr.put(c);
                    return ok(new JSONObject().put("items", arr));
                default:
                    return error(404, "not_found");
            }
        }
        if (path.startsWith("/v1/square/comments/") && "DELETE".equals(method)) {
            String id = path.substring("/v1/square/comments/".length());
            for (JSONObject c : comments) {
                if (c.optString("id").equals(id)) {
                    JSONObject p = findPost(c.optString("post_id"));
                    if (!isMine(c) && (p == null || !isMine(p))) return error(403, "forbidden");
                    comments.remove(c);
                    return ok(new JSONObject());
                }
            }
            return error(404, "not_found");
        }
        if (path.startsWith("/v1/square/users/")) {
            String rest = path.substring("/v1/square/users/".length());
            boolean postsOnly = rest.endsWith("/posts");
            String user = postsOnly ? rest.substring(0, rest.length() - "/posts".length()) : rest;
            if (blocked.contains(user)) return error(404, "not_found");
            ArrayList<JSONObject> list = new ArrayList<>();
            JSONObject author = null;
            for (JSONObject p : posts) {
                JSONObject a = p.optJSONObject("author");
                if (a != null && user.equals(a.optString("id"))) {
                    if (author == null) author = a;
                    if (visible(p)) list.add(p);
                }
            }
            if (MY_ID.equals(user)) author = myAuthor();
            if (author == null) return error(404, "not_found");
            if (postsOnly) return page(list, param(query, "cursor"));
            return ok(new JSONObject().put("author", author));
        }
        if (path.equals("/v1/square/report") && "POST".equals(method)) {
            JSONObject r = json(body);
            if (r.optString("target", "").isEmpty() || r.optString("id", "").isEmpty() || r.optString("reason", "").isEmpty()) return error(400, "invalid_request");
            countReport(r.optString("target"), r.optString("id"));
            return ok(new JSONObject());
        }
        return error(404, "not_found");
    }

    private double hot(JSONObject p) {
        int c = 0;
        for (JSONObject x : comments) if (x.optString("post_id").equals(p.optString("id"))) c++;
        double ageH = (System.currentTimeMillis() - p.optLong("created_at_ms")) / 3_600_000.0;
        return (p.optInt("like_count") + 2.0 * c) * Math.pow(0.5, ageH / 24.0);
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

    /** Decimal places of a coordinate as sent; exponent notation ("1e-05") counts as too precise. */
    static int decimals(String number) {
        if (number.toLowerCase(Locale.US).indexOf('e') >= 0) return 99;
        int dot = number.indexOf('.');
        if (dot < 0) return 0;
        return number.length() - dot - 1;
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
