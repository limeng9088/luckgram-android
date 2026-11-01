package org.telegram.luckgram.square;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Pure rules of Square (spec 26); the same cases live in SquareLogicTests.swift on iOS. */
public class SquareLogicTest {

    private static SquareLogic.Comment c(String id, String parent, String author) {
        SquareLogic.Comment x = new SquareLogic.Comment();
        x.id = id;
        x.parentId = parent;
        x.author.id = author;
        return x;
    }

    @Test
    public void parsesPostsPageAndDropsBadImages() throws Exception {
        JSONObject j = new JSONObject("{\"items\":[{\"id\":\"p1\",\"author\":{\"id\":\"a\",\"display_name\":\"Alice\",\"username\":\"alice\",\"avatar_url\":\"http://x/a.png\"},"
            + "\"text\":\"hi\",\"images\":[{\"url\":\"https://cdn/1.jpg\",\"width\":10,\"height\":20},{\"url\":\"ftp://bad\"}],"
            + "\"like_count\":3,\"comment_count\":-1,\"liked\":true,\"created_at\":\"2026-11-01T10:00:00Z\",\"status\":\"weird\"},"
            + "{\"id\":\"\"}],\"next_cursor\":\"abc\"}");
        SquareLogic.Page<SquareLogic.Post> page = SquareLogic.parsePosts(j);
        assertEquals(1, page.items.size());
        assertEquals("abc", page.nextCursor);
        SquareLogic.Post p = page.items.get(0);
        assertEquals("Alice", p.author.displayName);
        assertNull(p.author.avatarUrl); // not https
        assertEquals(1, p.images.size());
        assertNull(p.video);
        assertEquals(3, p.likeCount);
        assertEquals(0, p.commentCount);
        assertTrue(p.liked);
        assertEquals(SquareLogic.STATUS_PUBLISHED, p.status); // unknown states are shown as published
        assertFalse(p.isHidden());
        assertTrue(p.createdAtMs > 0);
        assertNull(SquareLogic.parsePosts(new JSONObject("{\"items\":[]}")).nextCursor);
    }

    @Test
    public void parsesHiddenAndVideo() throws Exception {
        SquareLogic.Post p = SquareLogic.parsePost(new JSONObject("{\"id\":\"p2\",\"status\":\"hidden\",\"hidden_reason\":\"spam\","
            + "\"images\":[{\"url\":\"https://cdn/x.jpg\"}],"
            + "\"video\":{\"url\":\"https://cdn/v.mp4\",\"cover_url\":\"https://cdn/c.jpg\",\"width\":1280,\"height\":720,\"duration_seconds\":42}}"));
        assertTrue(p.isHidden());
        assertEquals("spam", p.hiddenReason);
        assertNotNull(p.video);
        assertEquals(42, p.video.durationSeconds);
        assertEquals("https://cdn/c.jpg", p.video.coverUrl);
        assertTrue(p.images.isEmpty()); // a video post never also carries images
        assertNull(SquareLogic.parseVideo(new JSONObject("{\"url\":\"http://plain/v.mp4\"}")));
        SquareLogic.Comment c = SquareLogic.parseComment(new JSONObject("{\"id\":\"c1\",\"status\":\"hidden\",\"reason\":\"x\"}"));
        assertEquals(SquareLogic.STATUS_HIDDEN, c.status);
        assertEquals("x", c.hiddenReason);
    }

    @Test
    public void parsesUploads() throws Exception {
        List<SquareLogic.Upload> u = SquareLogic.parseUploads(new JSONObject("{\"uploads\":[{\"key\":\"square/1.jpg\",\"url\":\"https://s3/1\"},{\"key\":\"square/2.jpg\",\"url\":\"http://s3/2\"},{\"url\":\"https://s3/3\"}]}"));
        assertEquals(1, u.size());
        assertEquals("square/1.jpg", u.get(0).key);
        assertTrue(SquareLogic.parseUploads(null).isEmpty());
    }

    @Test
    public void appendsWithoutDuplicates() {
        ArrayList<SquareLogic.Post> into = new ArrayList<>();
        SquareLogic.Post a = new SquareLogic.Post();
        a.id = "a";
        SquareLogic.Post a2 = new SquareLogic.Post();
        a2.id = "a";
        SquareLogic.Post b = new SquareLogic.Post();
        b.id = "b";
        SquareLogic.appendUnique(into, Arrays.asList(a, b));
        SquareLogic.appendUnique(into, Arrays.asList(a2, b));
        assertEquals(2, into.size());
    }

    @Test
    public void threadsOneLevelOfReplies() {
        SquareLogic.Comment c1 = c("c1", null, "a");
        SquareLogic.Comment r1 = c("r1", "c1", "b");
        SquareLogic.Comment rr = c("rr", "r1", "c"); // reply to a reply → shown under c1
        SquareLogic.Comment c2 = c("c2", null, "d");
        SquareLogic.Comment orphan = c("o", "missing", "e");
        List<SquareLogic.Comment> out = SquareLogic.thread(Arrays.asList(rr, c2, r1, c1, orphan));
        List<String> ids = new ArrayList<>();
        for (SquareLogic.Comment x : out) ids.add(x.id + ":" + x.depth);
        assertEquals(Arrays.asList("c2:0", "c1:0", "rr:1", "r1:1", "o:0"), ids);
        assertEquals("c1", SquareLogic.replyParentId(Arrays.asList(c1, r1, rr), rr));
        assertEquals("c1", SquareLogic.replyParentId(Arrays.asList(c1, r1, rr), c1));
    }

    @Test
    public void permissions() {
        SquareLogic.Post post = new SquareLogic.Post();
        post.author.id = "owner";
        SquareLogic.Comment mine = c("m", null, "me");
        SquareLogic.Comment other = c("o", null, "x");
        assertTrue(SquareLogic.canDeletePost(post, "owner"));
        assertFalse(SquareLogic.canDeletePost(post, "me"));
        assertFalse(SquareLogic.canDeletePost(post, null));
        assertTrue(SquareLogic.canDeleteComment(mine, post, "me"));
        assertTrue(SquareLogic.canDeleteComment(other, post, "owner"));
        assertFalse(SquareLogic.canDeleteComment(other, post, "me"));
        assertFalse(SquareLogic.canDeleteComment(other, null, ""));
    }

    @Test
    public void validation() {
        String long1000 = new String(new char[1000]).replace('\0', '字');
        assertTrue(SquareLogic.isValidPost(long1000, 0, false));
        assertFalse(SquareLogic.isValidPost(long1000 + "x", 0, false));
        assertFalse(SquareLogic.isValidPost("   ", 0, false));
        assertTrue(SquareLogic.isValidPost("", 1, false));
        assertTrue(SquareLogic.isValidPost("", 0, true));
        assertFalse(SquareLogic.isValidPost("", 10, false));
        assertFalse(SquareLogic.isValidPost("x", 1, true)); // photos or a video, not both
        assertTrue(SquareLogic.isValidComment(" ok "));
        assertFalse(SquareLogic.isValidComment(""));
        assertFalse(SquareLogic.isValidComment(new String(new char[301]).replace('\0', 'a')));
        assertEquals(2, SquareLogic.length("😀😀")); // code points, not UTF-16 units
    }

    @Test
    public void imageAndVideoSizing() {
        assertArrayEquals(new int[]{2048, 1536}, SquareLogic.scaledSize(4096, 3072));
        assertArrayEquals(new int[]{1536, 2048}, SquareLogic.scaledSize(3072, 4096));
        assertArrayEquals(new int[]{800, 600}, SquareLogic.scaledSize(800, 600));
        assertArrayEquals(new int[]{2048, 1}, SquareLogic.scaledSize(100000, 10));
        assertArrayEquals(new int[]{0, 0}, SquareLogic.scaledSize(0, 10));
        assertEquals(1, SquareLogic.sampleSize(2048, 100));
        assertEquals(2, SquareLogic.sampleSize(4096, 100));
        assertEquals(4, SquareLogic.sampleSize(9000, 9000));
        assertArrayEquals(new int[]{1280, 720}, SquareLogic.videoSize(1920, 1080));
        assertArrayEquals(new int[]{720, 1280}, SquareLogic.videoSize(1080, 1920));
        assertArrayEquals(new int[]{640, 480}, SquareLogic.videoSize(640, 480));
        assertArrayEquals(new int[]{1280, 718}, SquareLogic.videoSize(1921, 1079)); // even sides
        assertEquals(2_500_000, SquareLogic.videoBitrate(1280, 720, 0));
        assertEquals(1_000_000, SquareLogic.videoBitrate(1280, 720, 1_000_000)); // never above the source
        assertEquals(600_000, SquareLogic.videoBitrate(160, 120, 0));
        assertEquals("0:07", SquareLogic.formatDuration(7));
        assertEquals("1:00", SquareLogic.formatDuration(60));
        assertEquals("12:34", SquareLogic.formatDuration(754));
    }

    @Test
    public void pathsAndBodies() throws Exception {
        assertEquals("/v1/square/feed?tab=latest", SquareLogic.feedPath("nope", null));
        assertEquals("/v1/square/feed?tab=hot&cursor=a%2Fb", SquareLogic.feedPath(SquareLogic.TAB_HOT, "a/b"));
        assertEquals("/v1/square/users/x/posts?cursor=1", SquareLogic.withCursor("/v1/square/users/x/posts", "1"));
        assertEquals("a%20b", SquareLogic.pathId("a b"));
        JSONObject body = SquareLogic.postBody("  hi ", Arrays.asList("k1", "k2"));
        assertEquals("hi", body.getString("text"));
        assertEquals(2, body.getJSONArray("image_keys").length());
        JSONObject vb = SquareLogic.postBodyWithVideo("v", "square/v.mp4", "square/c.jpg");
        assertEquals("square/v.mp4", vb.getString("video_key"));
        assertEquals("square/c.jpg", vb.getString("cover_key"));
        assertFalse(vb.has("image_keys"));
        JSONObject ub = SquareLogic.uploadsBody(SquareLogic.VIDEO_CONTENT_TYPE, Arrays.asList(1234L));
        assertEquals("video/mp4", ub.getString("content_type"));
        assertEquals(1, ub.getInt("count"));
        assertEquals(1234L, ub.getJSONArray("sizes").getLong(0));
        assertFalse(SquareLogic.commentBody("x", null).has("parent_id"));
        assertEquals("c1", SquareLogic.commentBody("x", "c1").getString("parent_id"));
        JSONObject r = SquareLogic.reportBody("post", "p1", "spam");
        assertEquals("post", r.getString("target"));
        assertEquals(7, SquareLogic.REPORT_REASONS.length);
    }

    @Test
    public void silentLinkHelpers() {
        assertEquals("LuckgramBot", SquareLogic.botUsername("https://t.me/LuckgramBot?start=abc"));
        assertEquals("lg_bot", SquareLogic.botUsername("t.me/lg_bot/"));
        assertNull(SquareLogic.botUsername("https://example.com/LuckgramBot?start=abc"));
        assertNull(SquareLogic.botUsername("https://t.me/?start=abc"));
        assertNull(SquareLogic.botUsername(null));
        assertEquals("/start n0nce", SquareLogic.startCommand("n0nce"));
    }

    @Test
    public void textsCoverBothLanguagesAndWaitTimes() {
        SquareLogic.Texts zh = new SquareLogic.Texts(true);
        SquareLogic.Texts en = new SquareLogic.Texts(false);
        assertEquals("广场", zh.title());
        assertEquals("Square", en.title());
        assertTrue(zh.hiddenInfo("x").contains("原因：x"));
        assertTrue(en.hiddenInfo(null).startsWith("This post has been hidden"));
        assertTrue(zh.waitSeconds(30).contains("30 秒"));
        assertTrue(en.waitSeconds(90).contains("2 min"));
        assertTrue(zh.waitSeconds(7200).contains("小时"));
        assertTrue(en.waitSeconds(0).startsWith("You're doing that too often"));
        assertEquals("刚刚", zh.timeAgo(1000, 2000));
        assertEquals("3 h ago", en.timeAgo(1, 1 + 3 * 3600_000L));
        assertEquals("", en.timeAgo(0, 5));
        for (String r : SquareLogic.REPORT_REASONS) {
            assertFalse(zh.reportReason(r).isEmpty());
            assertFalse(en.reportReason(r).isEmpty());
        }
        assertTrue(en.dailyLimitInfo(10).contains("10"));
        assertTrue(en.videoTooLong(60, 95).contains("1:35"));
        assertTrue(zh.attachmentsInfo(60).contains("60 秒"));
        assertEquals("视频 · 0:42", zh.videoLabel(42));
        // nothing in the UI mentions linking an account (spec 26 item 2)
        for (SquareLogic.Texts tx : new SquareLogic.Texts[]{zh, en}) {
            for (String s : new String[]{tx.connecting(), tx.connectFailed(), tx.needContent(), tx.profileNeeded(), tx.empty()}) {
                assertFalse(s, s.contains("绑定") || s.toLowerCase().contains("link"));
            }
        }
    }
}
