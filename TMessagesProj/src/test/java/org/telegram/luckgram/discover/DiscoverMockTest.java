package org.telegram.luckgram.discover;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class DiscoverMockTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void nearbyRoundTrip() throws Exception {
        DiscoverMock m = new DiscoverMock();
        assertNull(m.handle("GET", "/v1/ai/chat", "", null));
        // more than 2 decimals is rejected (the client must coarsen)
        assertEquals(400, m.handle("PUT", "/v1/nearby/me", "", b("{\"lat\":31.2304,\"lon\":121.47,\"username\":\"me\"}")).status);
        DiscoverMock.Reply put = m.handle("PUT", "/v1/nearby/me", "", b("{\"lat\":31.23,\"lon\":121.47,\"username\":\"me\"}"));
        assertEquals(200, put.status);
        assertTrue(put.body.getBoolean("visible"));
        assertTrue(m.handle("GET", "/v1/nearby/me", "", null).body.getBoolean("visible"));

        JSONArray items = m.handle("GET", "/v1/nearby", "lat=31.23&lon=121.47", null).body.getJSONArray("items");
        assertEquals(7, items.length());
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.getJSONObject(i);
            assertFalse(o.has("lat"));
            assertFalse(o.has("distance"));
        }
        assertEquals(200, m.handle("POST", "/v1/blocks", "", b("{\"user\":\"m1\"}")).status);
        assertEquals(6, m.handle("GET", "/v1/nearby", "lat=31.23&lon=121.47", null).body.getJSONArray("items").length());
        assertEquals(200, m.handle("DELETE", "/v1/blocks/m1", "", null).status);

        assertEquals(200, m.handle("DELETE", "/v1/nearby/me", "", null).status);
        assertFalse(m.handle("GET", "/v1/nearby/me", "", null).body.getBoolean("visible"));
    }

    @Test
    public void notLinkedAndRateLimit() throws Exception {
        DiscoverMock m = new DiscoverMock();
        m.handle("DELETE", "/v1/link", "", null);
        DiscoverMock.Reply r = m.handle("GET", "/v1/nearby", "lat=1.00&lon=1.00", null);
        assertEquals(403, r.status);
        assertEquals("not_linked", r.body.getJSONObject("error").getString("code"));
        m.setLinked(true);
        for (int i = 0; i < 30; i++) m.handle("GET", "/v1/nearby", "lat=1.00&lon=1.00", null);
        r = m.handle("GET", "/v1/nearby", "lat=1.00&lon=1.00", null);
        assertEquals(429, r.status);
        assertTrue(r.body.getJSONObject("error").getLong("retry_after_seconds") > 0);
    }

    @Test
    public void squareRoundTrip() throws Exception {
        DiscoverMock m = new DiscoverMock();
        // browsing works without a link; writes need it
        m.setLinked(false);
        JSONObject feed = m.handle("GET", "/v1/square/feed", "tab=latest", null).body;
        assertEquals(20, feed.getJSONArray("items").length());
        assertTrue(feed.has("next_cursor"));
        JSONObject page2 = m.handle("GET", "/v1/square/feed", "tab=latest&cursor=" + feed.getString("next_cursor"), null).body;
        assertEquals(5, page2.getJSONArray("items").length());
        assertFalse(page2.has("next_cursor"));
        assertEquals(403, m.handle("POST", "/v1/square/posts", "", b("{\"text\":\"hi\"}")).status);
        assertEquals(403, m.handle("POST", "/v1/square/posts/p1/like", "", null).status);

        m.setLinked(true);
        // profile first, then uploads + post
        assertEquals(403, m.handle("POST", "/v1/square/posts", "", b("{\"text\":\"hi\"}")).status);
        assertEquals(200, m.handle("PUT", "/v1/square/profile", "", b("{\"display_name\":\"Me\",\"bio\":\"\",\"username\":\"\"}")).status);
        JSONObject ups = m.handle("POST", "/v1/square/uploads", "", b("{\"count\":2,\"content_type\":\"image/jpeg\",\"sizes\":[10,20]}")).body;
        assertEquals(2, ups.getJSONArray("uploads").length());
        String key = ups.getJSONArray("uploads").getJSONObject(0).getString("key");
        String url = ups.getJSONArray("uploads").getJSONObject(0).getString("url");
        assertTrue(url.startsWith("https://" + DiscoverMock.MOCK_HOST + DiscoverMock.UPLOAD_PREFIX));
        // a key that was never uploaded is rejected
        assertEquals(400, m.handle("POST", "/v1/square/posts", "", b("{\"text\":\"hi\",\"image_keys\":[\"" + key + "\"]}")).status);
        assertTrue(m.acceptUpload(DiscoverMock.UPLOAD_PREFIX + key, b("jpeg")));
        DiscoverMock.Reply posted = m.handle("POST", "/v1/square/posts", "", b("{\"text\":\"hi\",\"image_keys\":[\"" + key + "\"]}"));
        assertEquals(200, posted.status);
        assertEquals("published", posted.body.getString("status")); // public at once, no review
        String id = posted.body.getString("id");
        assertEquals(id, m.handle("GET", "/v1/square/feed", "tab=latest", null).body.getJSONArray("items").getJSONObject(0).getString("id"));

        // a video post: one MP4 plus its cover, never together with images
        JSONObject vup = m.handle("POST", "/v1/square/uploads", "", b("{\"count\":1,\"content_type\":\"video/mp4\",\"sizes\":[3000]}")).body.getJSONArray("uploads").getJSONObject(0);
        JSONObject cup = m.handle("POST", "/v1/square/uploads", "", b("{\"count\":1,\"content_type\":\"image/jpeg\",\"sizes\":[30]}")).body.getJSONArray("uploads").getJSONObject(0);
        assertEquals(400, m.handle("POST", "/v1/square/uploads", "", b("{\"count\":1,\"content_type\":\"video/quicktime\",\"sizes\":[1]}")).status);
        assertEquals(400, m.handle("POST", "/v1/square/uploads", "", b("{\"count\":2,\"content_type\":\"image/jpeg\",\"sizes\":[10]}")).status); // sizes must match count
        assertTrue(vup.getString("key").endsWith(".mp4"));
        assertTrue(m.acceptUpload(DiscoverMock.UPLOAD_PREFIX + vup.getString("key"), b("mp4")));
        assertTrue(m.acceptUpload(DiscoverMock.UPLOAD_PREFIX + cup.getString("key"), b("jpg")));
        String videoBody = "{\"text\":\"v\",\"video_key\":\"" + vup.getString("key") + "\",\"cover_key\":\"" + cup.getString("key") + "\"}";
        assertEquals(400, m.handle("POST", "/v1/square/posts", "", b(videoBody.replace("\"text\":\"v\"", "\"text\":\"v\",\"image_keys\":[\"" + key + "\"]"))).status);
        DiscoverMock.Reply vposted = m.handle("POST", "/v1/square/posts", "", b(videoBody));
        assertEquals(200, vposted.status);
        assertTrue(vposted.body.getJSONObject("video").getInt("duration_seconds") > 0);
        assertEquals(400, m.handle("POST", "/v1/square/posts", "", b("{\"text\":\"v\",\"video_key\":\"" + cup.getString("key") + "\",\"cover_key\":\"" + cup.getString("key") + "\"}")).status);
        assertTrue(vposted.body.getJSONObject("video").getString("cover_url").contains("/mock-image/"));

        // like / comment / reply / delete
        assertEquals(1, m.handle("POST", "/v1/square/posts/" + id + "/like", "", null).body.getInt("like_count"));
        assertEquals(0, m.handle("DELETE", "/v1/square/posts/" + id + "/like", "", null).body.getInt("like_count"));
        JSONObject c = m.handle("POST", "/v1/square/posts/" + id + "/comments", "", b("{\"text\":\"nice\"}")).body;
        JSONObject r = m.handle("POST", "/v1/square/posts/" + id + "/comments", "", b("{\"text\":\"re\",\"parent_id\":\"" + c.getString("id") + "\"}")).body;
        assertEquals(c.getString("id"), r.getString("parent_id"));
        assertEquals(2, m.handle("GET", "/v1/square/posts/" + id + "/comments", "", null).body.getJSONArray("items").length());
        assertEquals(2, m.handle("GET", "/v1/square/posts/" + id, "", null).body.getInt("comment_count"));
        assertEquals(200, m.handle("DELETE", "/v1/square/comments/" + r.getString("id"), "", null).status);
        assertEquals(403, m.handle("DELETE", "/v1/square/posts/p1", "", null).status); // not mine
        assertEquals(200, m.handle("POST", "/v1/square/report", "", b("{\"target\":\"post\",\"id\":\"p1\",\"reason\":\"spam\"}")).status);

        // comment rate limit: 5 per minute
        for (int i = 0; i < 3; i++) m.handle("POST", "/v1/square/posts/" + id + "/comments", "", b("{\"text\":\"x\"}"));
        DiscoverMock.Reply limited = m.handle("POST", "/v1/square/posts/" + id + "/comments", "", b("{\"text\":\"x\"}"));
        assertEquals(429, limited.status);
        assertTrue(limited.body.getJSONObject("error").getLong("retry_after_seconds") > 0);

        // three reports hide a post; its author still sees it, marked hidden, with the reason
        for (int i = 0; i < 3; i++) m.handle("POST", "/v1/square/report", "", b("{\"target\":\"post\",\"id\":\"" + id + "\",\"reason\":\"spam\"}"));
        JSONObject hidden = m.handle("GET", "/v1/square/posts/" + id, "", null).body;
        assertEquals("hidden", hidden.getString("status"));
        assertTrue(hidden.has("hidden_reason"));
        JSONArray latest = m.handle("GET", "/v1/square/feed", "tab=latest", null).body.getJSONArray("items");
        boolean stillListedForAuthor = false;
        for (int i = 0; i < latest.length(); i++) if (latest.getJSONObject(i).getString("id").equals(id)) stillListedForAuthor = true;
        assertTrue(stillListedForAuthor);

        // blocking hides the author's posts; deleting my data removes mine
        assertEquals(200, m.handle("POST", "/v1/blocks", "", b("{\"user\":\"m1\"}")).status);
        JSONArray items = m.handle("GET", "/v1/square/feed", "tab=hot", null).body.getJSONArray("items");
        for (int i = 0; i < items.length(); i++) {
            assertFalse("m1".equals(items.getJSONObject(i).getJSONObject("author").getString("id")));
        }
        assertEquals(404, m.handle("GET", "/v1/square/users/m1/posts", "", null).status);
        assertEquals(200, m.handle("DELETE", "/v1/square/me", "", null).status);
        assertEquals(404, m.handle("GET", "/v1/square/posts/" + id, "", null).status);
        assertEquals(404, m.handle("GET", "/v1/square/profile", "", null).status);
    }

    @Test
    public void countsDecimals() {
        assertEquals(2, DiscoverMock.decimals("31.23"));
        assertEquals(0, DiscoverMock.decimals("31"));
        assertEquals(99, DiscoverMock.decimals("1.0E-5"));
        assertEquals(99, DiscoverMock.decimals("1e-05"));
        assertEquals(99, DiscoverMock.decimals("1E5"));
    }
}
