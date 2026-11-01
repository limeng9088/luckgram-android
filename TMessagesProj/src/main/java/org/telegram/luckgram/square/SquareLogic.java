package org.telegram.luckgram.square;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Pure logic of "Square" (spec 26, server S11): response parsing, limits, image and video sizing,
 * comment threading, permissions, the silent S6 link and all texts in both languages. Only java.*
 * and org.json (JVM unit tests); the same rules are in SquareLogic.swift on iOS.
 */
public final class SquareLogic {

    private SquareLogic() {
    }

    public static final String FEATURE_KEY = "square";

    public static final int TEXT_MAX = 1000;
    public static final int COMMENT_MAX = 300;
    public static final int IMAGES_MAX = 9;
    public static final int PAGE_SIZE = 20;
    /** Long edge after client-side compression (spec 26 item 3). */
    public static final int IMAGE_MAX_EDGE = 2048;
    public static final int PROFILE_BIO_MAX = 70;
    /** Built-in default for {@code square.max_posts_per_day}; the remote config value wins (spec 26 item 7). */
    public static final long DEFAULT_MAX_POSTS_PER_DAY = 10;
    /** Built-in default for {@code square.max_video_seconds}; the remote config value wins (spec 26 item 3). */
    public static final long DEFAULT_MAX_VIDEO_SECONDS = 60;
    /** Short video after client-side transcoding: long edge ≤ 1280 px, H.264/AAC MP4, ≤ {@code square.max_video_mb} (default 50). */
    public static final int VIDEO_MAX_EDGE = 1280;
    public static final long DEFAULT_MAX_VIDEO_MB = 50;
    public static final long VIDEO_MAX_BYTES = DEFAULT_MAX_VIDEO_MB * 1024 * 1024;
    public static final String IMAGE_CONTENT_TYPE = "image/jpeg";
    public static final String VIDEO_CONTENT_TYPE = "video/mp4";

    public static final String TAB_LATEST = "latest";
    public static final String TAB_HOT = "hot";

    /** Posts are public as soon as they are created; reported ones are hidden (S11 governance). */
    public static final String STATUS_PUBLISHED = "published";
    public static final String STATUS_HIDDEN = "hidden";

    // ---------------------------------------------------------------- model

    public static final class Author {
        public String id = "";
        public String displayName = "";
        public String avatarUrl;
        public String username;
        public String bio = "";
    }

    public static final class Image {
        public String url;
        public int width;
        public int height;
    }

    /** One short video: the MP4, its cover frame and the duration in seconds. */
    public static final class Video {
        public String url;
        public String coverUrl;
        public int width;
        public int height;
        public int durationSeconds;
    }

    public static final class Post {
        public String id = "";
        public Author author = new Author();
        public String text = "";
        public final List<Image> images = new ArrayList<>();
        public Video video;
        public int likeCount;
        public int commentCount;
        public boolean liked;
        public long createdAtMs;
        public String status = STATUS_PUBLISHED;
        /** Why the post was hidden (only sent to its author). */
        public String hiddenReason;

        public boolean isHidden() {
            return STATUS_HIDDEN.equals(status);
        }
    }

    public static final class Comment {
        public String id = "";
        public String postId = "";
        public String parentId;
        public Author author = new Author();
        public String text = "";
        public long createdAtMs;
        public String status = STATUS_PUBLISHED;
        public String hiddenReason;
        /** Set by {@link #thread}: 0 = comment, 1 = reply. */
        public int depth;
    }

    public static final class Page<T> {
        public final List<T> items = new ArrayList<>();
        public String nextCursor;
    }

    /** One presigned upload slot from {@code POST /v1/square/uploads}. */
    public static final class Upload {
        public String key = "";
        public String url = "";
    }

    // ---------------------------------------------------------------- parsing

    private static String str(JSONObject o, String key) {
        if (o == null || !o.has(key) || o.isNull(key)) return null;
        String v = o.optString(key, null);
        return v == null || v.isEmpty() ? null : v;
    }

    private static String httpsOrNull(String url) {
        return url != null && url.startsWith("https://") ? url : null;
    }

    public static Author parseAuthor(JSONObject o) {
        Author a = new Author();
        if (o == null) return a;
        a.id = o.optString("id", "");
        a.displayName = o.optString("display_name", "");
        a.avatarUrl = httpsOrNull(str(o, "avatar_url"));
        a.username = str(o, "username");
        a.bio = o.optString("bio", "");
        return a;
    }

    public static Video parseVideo(JSONObject o) {
        if (o == null) return null;
        String url = httpsOrNull(str(o, "url"));
        if (url == null) return null;
        Video v = new Video();
        v.url = url;
        v.coverUrl = httpsOrNull(str(o, "cover_url"));
        v.width = Math.max(0, o.optInt("width", 0));
        v.height = Math.max(0, o.optInt("height", 0));
        v.durationSeconds = Math.max(0, o.optInt("duration_seconds", 0));
        return v;
    }

    public static Post parsePost(JSONObject o) {
        Post p = new Post();
        p.id = o.optString("id", "");
        p.author = parseAuthor(o.optJSONObject("author"));
        p.text = o.optString("text", "");
        JSONArray imgs = o.optJSONArray("images");
        if (imgs != null) {
            for (int i = 0; i < imgs.length() && p.images.size() < IMAGES_MAX; i++) {
                JSONObject io = imgs.optJSONObject(i);
                String url = io == null ? null : httpsOrNull(str(io, "url"));
                if (url == null) continue;
                Image img = new Image();
                img.url = url;
                img.width = io.optInt("width", 0);
                img.height = io.optInt("height", 0);
                p.images.add(img);
            }
        }
        p.video = parseVideo(o.optJSONObject("video"));
        if (p.video != null) p.images.clear(); // attachments are exclusive
        p.likeCount = Math.max(0, o.optInt("like_count", 0));
        p.commentCount = Math.max(0, o.optInt("comment_count", 0));
        p.liked = o.optBoolean("liked", false);
        p.createdAtMs = parseTime(o.optString("created_at", ""));
        p.status = normalizeStatus(o.optString("status", STATUS_PUBLISHED));
        p.hiddenReason = hiddenReason(o);
        return p;
    }

    public static Comment parseComment(JSONObject o) {
        Comment c = new Comment();
        c.id = o.optString("id", "");
        c.postId = o.optString("post_id", "");
        c.parentId = str(o, "parent_id");
        c.author = parseAuthor(o.optJSONObject("author"));
        c.text = o.optString("text", "");
        c.createdAtMs = parseTime(o.optString("created_at", ""));
        c.status = normalizeStatus(o.optString("status", STATUS_PUBLISHED));
        c.hiddenReason = hiddenReason(o);
        return c;
    }

    public static Page<Post> parsePosts(JSONObject j) {
        Page<Post> page = new Page<>();
        JSONArray items = j.optJSONArray("items");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.optJSONObject(i);
                if (o == null) continue;
                Post p = parsePost(o);
                if (!p.id.isEmpty()) page.items.add(p);
            }
        }
        page.nextCursor = str(j, "next_cursor");
        return page;
    }

    public static Page<Comment> parseComments(JSONObject j) {
        Page<Comment> page = new Page<>();
        JSONArray items = j.optJSONArray("items");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.optJSONObject(i);
                if (o == null) continue;
                Comment c = parseComment(o);
                if (!c.id.isEmpty()) page.items.add(c);
            }
        }
        page.nextCursor = str(j, "next_cursor");
        return page;
    }

    /** {@code {"uploads":[{"key","url"}]}}; entries without an https URL are dropped. */
    public static List<Upload> parseUploads(JSONObject j) {
        ArrayList<Upload> out = new ArrayList<>();
        JSONArray arr = j == null ? null : j.optJSONArray("uploads");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String key = o.optString("key", "");
            String url = httpsOrNull(str(o, "url"));
            if (key.isEmpty() || url == null) continue;
            Upload u = new Upload();
            u.key = key;
            u.url = url;
            out.add(u);
        }
        return out;
    }

    /** Anything but {@code hidden} is shown as published (a server-side state we don't know is still visible content). */
    static String normalizeStatus(String s) {
        return STATUS_HIDDEN.equals(s) ? STATUS_HIDDEN : STATUS_PUBLISHED;
    }

    static String hiddenReason(JSONObject o) {
        String r = str(o, "hidden_reason");
        if (r == null) r = str(o, "reason");
        return r;
    }

    /** Appends a page without duplicates (cursor pages may overlap after new posts). */
    public static void appendUnique(List<Post> into, List<Post> page) {
        for (Post p : page) {
            boolean dup = false;
            for (Post q : into) {
                if (q.id.equals(p.id)) {
                    dup = true;
                    break;
                }
            }
            if (!dup) into.add(p);
        }
    }

    /**
     * One level of comments plus one level of replies: each comment followed by its replies, in
     * server order. Replies to a reply are shown under the top comment; orphans become comments.
     */
    public static List<Comment> thread(List<Comment> comments) {
        ArrayList<Comment> top = new ArrayList<>();
        for (Comment c : comments) {
            if (c.parentId == null || findById(comments, c.parentId) == null) top.add(c);
        }
        ArrayList<Comment> out = new ArrayList<>();
        for (Comment t : top) {
            t.depth = 0;
            out.add(t);
            for (Comment c : comments) {
                if (c == t || c.parentId == null || top.contains(c)) continue;
                if (rootOf(comments, c) == t) {
                    c.depth = 1;
                    out.add(c);
                }
            }
        }
        return out;
    }

    private static Comment findById(List<Comment> list, String id) {
        for (Comment c : list) {
            if (c.id.equals(id)) return c;
        }
        return null;
    }

    private static Comment rootOf(List<Comment> list, Comment c) {
        Comment cur = c;
        for (int guard = 0; guard < 10 && cur.parentId != null; guard++) {
            Comment parent = findById(list, cur.parentId);
            if (parent == null) break;
            cur = parent;
        }
        return cur;
    }

    /** Top-level comment id to send as {@code parent_id} when replying to {@code c}. */
    public static String replyParentId(List<Comment> all, Comment c) {
        return rootOf(all, c).id;
    }

    // ---------------------------------------------------------------- permissions

    public static boolean canDeletePost(Post p, String myId) {
        return myId != null && !myId.isEmpty() && myId.equals(p.author.id);
    }

    /** The comment's author, or the author of the post it is under. */
    public static boolean canDeleteComment(Comment c, Post post, String myId) {
        if (myId == null || myId.isEmpty()) return false;
        return myId.equals(c.author.id) || (post != null && myId.equals(post.author.id));
    }

    // ---------------------------------------------------------------- validation

    public static int length(String s) {
        return s == null ? 0 : s.codePointCount(0, s.length());
    }

    /** Text ≤ 1000 characters; attachments are either up to 9 images or one video; something must be there. */
    public static boolean isValidPost(String text, int images, boolean hasVideo) {
        int n = length(text == null ? null : text.trim());
        if (images < 0 || images > IMAGES_MAX || n > TEXT_MAX) return false;
        if (hasVideo && images > 0) return false;
        return n > 0 || images > 0 || hasVideo;
    }

    public static boolean isValidComment(String text) {
        int n = length(text == null ? null : text.trim());
        return n > 0 && n <= COMMENT_MAX;
    }

    /** Output size with the long edge ≤ {@link #IMAGE_MAX_EDGE}, keeping the aspect ratio. */
    public static int[] scaledSize(int width, int height) {
        return scaledSize(width, height, IMAGE_MAX_EDGE);
    }

    /** Output size with the long edge ≤ {@code maxEdge}, keeping the aspect ratio. */
    public static int[] scaledSize(int width, int height, int maxEdge) {
        if (width <= 0 || height <= 0) return new int[]{0, 0};
        int edge = Math.max(width, height);
        if (edge <= maxEdge) return new int[]{width, height};
        double k = (double) maxEdge / edge;
        return new int[]{Math.max(1, (int) Math.round(width * k)), Math.max(1, (int) Math.round(height * k))};
    }

    /** Video output size: long edge ≤ {@link #VIDEO_MAX_EDGE}, both sides even (H.264 needs that). */
    public static int[] videoSize(int width, int height) {
        int[] s = scaledSize(width, height, VIDEO_MAX_EDGE);
        return new int[]{Math.max(2, s[0] / 2 * 2), Math.max(2, s[1] / 2 * 2)};
    }

    /** Largest power-of-two sample size that still keeps the long edge ≥ the target (decode step). */
    public static int sampleSize(int width, int height) {
        int edge = Math.max(width, height);
        int s = 1;
        while (edge / (s * 2) >= IMAGE_MAX_EDGE) s *= 2;
        return s;
    }

    /** Bitrate for the transcoded video: ~2.5 Mbps at 1280 px, scaled with the area, never above the source. */
    public static int videoBitrate(int width, int height, int sourceBitrate) {
        long area = (long) Math.max(1, width) * Math.max(1, height);
        int target = (int) Math.min(4_000_000L, Math.max(600_000L, area * 2_500_000L / (1280L * 720L)));
        return sourceBitrate > 0 ? Math.min(target, sourceBitrate) : target;
    }

    /** "0:07", "1:00", "12:34". */
    public static String formatDuration(int seconds) {
        int s = Math.max(0, seconds);
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    public static JSONObject postBody(String text, List<String> imageKeys) throws Exception {
        JSONArray keys = new JSONArray();
        if (imageKeys != null) for (String k : imageKeys) keys.put(k);
        return new JSONObject().put("text", text == null ? "" : text.trim()).put("image_keys", keys);
    }

    /** S11: {@code {text, video_key, cover_key}}; the server reads size, duration and format from the file. */
    public static JSONObject postBodyWithVideo(String text, String videoKey, String coverKey) throws Exception {
        return new JSONObject()
            .put("text", text == null ? "" : text.trim())
            .put("video_key", videoKey)
            .put("cover_key", coverKey);
    }

    public static JSONObject uploadsBody(int count, String contentType) throws Exception {
        return new JSONObject().put("count", count).put("content_type", contentType);
    }

    public static JSONObject commentBody(String text, String parentId) throws Exception {
        JSONObject j = new JSONObject().put("text", text == null ? "" : text.trim());
        if (parentId != null) j.put("parent_id", parentId);
        return j;
    }

    public static String feedPath(String tab, String cursor) {
        String t = TAB_HOT.equals(tab) ? TAB_HOT : TAB_LATEST;
        return "/v1/square/feed?tab=" + t + (cursor != null ? "&cursor=" + urlEncode(cursor) : "");
    }

    public static String withCursor(String path, String cursor) {
        if (cursor == null) return path;
        return path + (path.contains("?") ? "&" : "?") + "cursor=" + urlEncode(cursor);
    }

    static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    public static String pathId(String id) {
        return urlEncode(id).replace("+", "%20");
    }

    // ---------------------------------------------------------------- report

    public static final String[] REPORT_REASONS = {"spam", "scam", "porn", "violence", "hate", "impersonation", "other"};

    public static JSONObject reportBody(String target, String id, String reason) throws Exception {
        return new JSONObject().put("target", target).put("id", id).put("reason", reason);
    }

    // ---------------------------------------------------------------- silent link (S6)

    /** Bot username from the {@code t.me/<bot>?start=<nonce>} link, or null. */
    public static String botUsername(String link) {
        if (link == null) return null;
        String s = link.trim();
        int i = s.indexOf("t.me/");
        if (i < 0) return null;
        s = s.substring(i + 5);
        int end = s.length();
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '?' || c == '/' || c == '#') {
                end = k;
                break;
            }
        }
        s = s.substring(0, end);
        if (s.isEmpty() || !s.matches("[A-Za-z0-9_]{4,32}")) return null;
        return s;
    }

    /** The message the app sends to the bot on the user's behalf. */
    public static String startCommand(String nonce) {
        return "/start " + nonce;
    }

    // ---------------------------------------------------------------- time

    static long parseTime(String s) {
        if (s == null || s.isEmpty()) return 0;
        String[] patterns = {"yyyy-MM-dd'T'HH:mm:ss.SSSX", "yyyy-MM-dd'T'HH:mm:ssX"};
        for (String p : patterns) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(p, Locale.US);
                f.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date d = f.parse(s);
                if (d != null) return d.getTime();
            } catch (ParseException ignore) {
            }
        }
        return 0;
    }

    // ---------------------------------------------------------------- texts

    public static final class Texts {
        private final boolean zh;

        public Texts(boolean zh) {
            this.zh = zh;
        }

        private String t(String zhText, String enText) {
            return zh ? zhText : enText;
        }

        public String title() { return t("广场", "Square"); }
        public String latest() { return t("最新", "Latest"); }
        public String hot() { return t("热门", "Trending"); }
        public String compose() { return t("发帖", "Post"); }
        public String loadMore() { return t("加载更多", "Load more"); }
        public String loading() { return t("正在加载…", "Loading…"); }
        public String empty() { return t("还没有动态，来发第一条吧", "No posts yet. Be the first to share something."); }
        public String noMore() { return t("已经到底了", "You're all caught up"); }
        public String refresh() { return t("刷新", "Refresh"); }

        public String hidden() { return t("已隐藏", "Hidden"); }
        public String hiddenInfo(String reason) {
            String base = t("这条动态已被隐藏，只有你自己能看到。", "This post has been hidden and is visible only to you.");
            return reason == null || reason.isEmpty() ? base : base + " " + t("原因：", "Reason: ") + reason;
        }
        public String likes(int n) { return t("赞 " + n, n == 1 ? "1 like" : n + " likes"); }
        public String comments(int n) { return t("评论 " + n, n == 1 ? "1 comment" : n + " comments"); }
        public String like() { return t("赞", "Like"); }
        public String unlike() { return t("取消赞", "Unlike"); }
        public String comment() { return t("评论", "Comment"); }
        public String reply() { return t("回复", "Reply"); }
        public String replyTo(String name) { return t("回复 " + name, "Reply to " + name); }
        public String commentHint() { return t("写下你的评论（最多 300 字）", "Write a comment (up to 300 characters)"); }
        public String send() { return t("发送", "Send"); }
        public String delete() { return t("删除", "Delete"); }
        public String deletePostConfirm() { return t("删除这条动态？删除后无法恢复。", "Delete this post? This can't be undone."); }
        public String deleteCommentConfirm() { return t("删除这条评论？", "Delete this comment?"); }
        public String deleted() { return t("已删除", "Deleted"); }
        public String noComments() { return t("还没有评论，来说两句吧", "No comments yet. Start the conversation."); }
        public String commentsHeader() { return t("评论", "Comments"); }
        public String commentsLoading() { return t("正在加载评论…", "Loading comments…"); }
        public String replyingTo(String name) { return t("正在回复 " + name, "Replying to " + name); }
        public String cancelReply() { return t("取消回复", "Cancel"); }
        public String commentSent() { return t("评论已发布", "Comment posted"); }
        public String postGone() { return t("这条动态已不存在", "This post is no longer available"); }
        public String noPublicUsername() { return t("对方没有公开用户名，暂时无法发消息", "This person hasn't set a public username, so you can't message them yet"); }
        public String authorGone() { return t("找不到这位用户", "User not found"); }

        public String composeTitle() { return t("发动态", "New post"); }
        public String textHint() { return t("分享新鲜事…（最多 1000 字）", "Share what's new… (up to 1000 characters)"); }
        public String addAttachment() { return t("添加图片或视频", "Add photos or video"); }
        public String addImages(int n) { return t("添加图片（" + n + "/9）", "Add photos (" + n + "/9)"); }
        public String chooseImages() { return t("从相册选图片", "Choose photos"); }
        public String chooseVideo() { return t("从相册选视频", "Choose a video"); }
        public String takePhoto() { return t("拍照", "Take a photo"); }
        public String recordVideo() { return t("录像", "Record a video"); }
        public String removeImages() { return t("移除全部图片", "Remove all photos"); }
        public String removeVideo() { return t("移除视频", "Remove video"); }
        public String videoLabel(int seconds) { return t("视频 · " + SquareLogic.formatDuration(seconds), "Video · " + SquareLogic.formatDuration(seconds)); }
        public String publish() { return t("发布", "Post"); }
        public String publishing() { return t("正在发布…", "Posting…"); }
        public String uploadingVideo() { return t("正在上传视频…", "Uploading video…"); }
        public String published() { return t("已发布", "Posted"); }
        public String attachmentsInfo(long maxSeconds) {
            return t("最多 9 张图片，或 1 段不超过 " + maxSeconds + " 秒的视频。图片会压缩到长边 2048 像素，视频会转码为 1280p 以内的 MP4；位置等元数据都会去掉后再上传。",
                "Up to 9 photos, or one video of up to " + maxSeconds + " seconds. Photos are resized to 2048 px on the long edge and videos are transcoded to MP4 at up to 1280p; location and other metadata are removed before upload.");
        }
        public String tooLong() { return t("内容超过了 1000 字", "The text is over 1000 characters"); }
        public String needContent() { return t("写点什么，或添加图片 / 视频", "Add some text, photos or a video"); }
        public String imageFailed() { return t("有图片无法处理，请换一张试试", "One of the photos couldn't be processed. Please try another."); }
        public String preparingImages() { return t("正在处理图片…", "Preparing photos…"); }
        public String preparingVideo() { return t("正在处理视频…", "Preparing video…"); }
        public String videoFailed() { return t("视频无法处理，请换一段试试", "The video couldn't be processed. Please try another."); }
        public String videoTooBig(long maxMb) { return t("转码后仍超过 " + maxMb + " MB，请选一段更短的视频", "The video is still over " + maxMb + " MB after compression. Please pick a shorter one."); }
        public String videoRejected() { return t("视频不符合要求（时长、大小或格式），请换一段试试", "The video doesn't meet the requirements (length, size or format). Please try another."); }
        public String squareDisabled() { return t("广场暂时不可用，请稍后再试", "Square is temporarily unavailable. Please try again later."); }
        public String videoTooLongTitle() { return t("视频超过时长限制", "Video is too long"); }
        public String videoTooLong(long maxSeconds, int actualSeconds) {
            return t("广场视频最长 " + maxSeconds + " 秒，这段视频有 " + SquareLogic.formatDuration(actualSeconds) + "。可以只保留开头 " + maxSeconds + " 秒继续发布。",
                "Videos can be up to " + maxSeconds + " seconds; this one is " + SquareLogic.formatDuration(actualSeconds) + ". You can keep the first " + maxSeconds + " seconds and continue.");
        }
        public String trimAndContinue(long maxSeconds) { return t("保留前 " + maxSeconds + " 秒", "Keep first " + maxSeconds + " s"); }
        public String onlyOneAttachmentType() { return t("一条动态只能带图片或视频中的一种", "A post can have either photos or a video, not both"); }
        public String dailyLimitInfo(long perDay) { return t("每天最多发布 " + perDay + " 条动态。", "You can post up to " + perDay + " times a day."); }

        public String profileTitle() { return t("广场资料", "Square profile"); }
        public String profileNeeded() { return t("先设置一下在广场显示的名字和简介，其他人会在你的动态上看到。", "Set the name and bio shown with your posts in Square."); }
        public String displayName() { return t("显示名", "Name"); }
        public String bio() { return t("简介", "Bio"); }
        public String publicUsername() { return t("公开用户名", "Public username"); }
        public String useMyUsername(String u) { return t("在广场显示 @" + u + "，其他人可以通过它给你发消息", "Show @" + u + " in Square so others can message you"); }
        public String showUsername() { return t("显示", "Show"); }
        public String useTelegramAvatar() { return t("使用我的 Telegram 头像", "Use my Telegram photo"); }
        public String hideUsername() { return t("不显示", "Don't show"); }
        public String save() { return t("保存", "Save"); }
        public String saved() { return t("已保存", "Saved"); }
        public String editProfile() { return t("编辑广场资料", "Edit Square profile"); }

        public String authorPosts() { return t("动态", "Posts"); }
        public String sendMessage() { return t("发消息", "Message"); }

        public String connecting() { return t("正在连接广场…", "Connecting to Square…"); }
        public String connectFailed() { return t("暂时无法连接广场，请稍后再试", "Square isn't reachable right now. Please try again later."); }
        public String cancel() { return t("取消", "Cancel"); }

        public String report() { return t("举报", "Report"); }
        public String reportPost() { return t("举报这条动态", "Report post"); }
        public String reportComment() { return t("举报这条评论", "Report comment"); }
        public String reportAuthor() { return t("举报该用户", "Report user"); }
        public String reportTitle() { return t("举报原因", "Why are you reporting this?"); }
        public String reportReason(String r) {
            switch (r) {
                case "spam": return t("垃圾广告", "Spam");
                case "scam": return t("诈骗", "Scam or fraud");
                case "porn": return t("色情内容", "Sexual content");
                case "violence": return t("暴力内容", "Violence");
                case "hate": return t("仇恨言论", "Hate speech");
                case "impersonation": return t("冒充他人", "Impersonation");
                default: return t("其他", "Something else");
            }
        }
        public String reported() { return t("已收到你的举报，感谢反馈", "Thanks, your report has been received"); }
        public String block() { return t("拉黑该用户", "Block user"); }
        public String blockConfirm(String name) { return t("拉黑后，你和 " + name + " 在广场和「附近的人」里都不会再看到对方。", "You and " + name + " will no longer see each other in Square or People Nearby."); }
        public String blocked() { return t("已拉黑", "Blocked"); }
        public String more() { return t("更多", "More"); }

        public String deleteMyData() { return t("删除我的广场数据", "Delete my Square data"); }
        public String deleteMyDataConfirm() { return t("将删除你在广场发布的全部动态、评论、点赞和媒体文件，无法恢复。", "This permanently deletes all your Square posts, comments, likes and media."); }
        public String deletedMyData() { return t("你的广场数据已删除", "Your Square data has been deleted"); }

        public String waitSeconds(long seconds) {
            if (seconds <= 0) return t("操作太频繁，请稍后再试", "You're doing that too often. Please try again later.");
            if (seconds < 60) return t("操作太频繁，请 " + seconds + " 秒后再试", "You're doing that too often. Try again in " + seconds + " s.");
            long min = (seconds + 59) / 60;
            if (min < 60) return t("操作太频繁，请 " + min + " 分钟后再试", "You're doing that too often. Try again in " + min + " min.");
            long h = (min + 59) / 60;
            return t("今天的发布次数已用完，请 " + h + " 小时后再试", "You've reached today's posting limit. Try again in " + h + " h.");
        }
        public String banned() { return t("你的账号暂时无法在广场发布内容", "Your account can't post in Square at the moment"); }

        public String timeAgo(long createdAtMs, long nowMs) {
            long s = Math.max(0, (nowMs - createdAtMs) / 1000);
            if (createdAtMs <= 0) return "";
            if (s < 60) return t("刚刚", "Just now");
            if (s < 3600) return t((s / 60) + " 分钟前", (s / 60) + " min ago");
            if (s < 86400) return t((s / 3600) + " 小时前", (s / 3600) + " h ago");
            return t((s / 86400) + " 天前", (s / 86400) + " d ago");
        }
    }
}
