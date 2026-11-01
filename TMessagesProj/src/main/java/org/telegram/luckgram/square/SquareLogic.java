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
 * Pure logic of "Square" (spec 26, server S11): response parsing, limits, image sizing, comment
 * threading and all texts in both languages. Only java.* and org.json (JVM unit tests); the same
 * rules are in SquareLogic.swift on iOS.
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

    public static final String TAB_LATEST = "latest";
    public static final String TAB_HOT = "hot";

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_APPROVED = "approved";
    public static final String STATUS_REJECTED = "rejected";

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

    public static final class Post {
        public String id = "";
        public Author author = new Author();
        public String text = "";
        public final List<Image> images = new ArrayList<>();
        public int likeCount;
        public int commentCount;
        public boolean liked;
        public long createdAtMs;
        public String status = STATUS_APPROVED;
        public String rejectReason;
    }

    public static final class Comment {
        public String id = "";
        public String postId = "";
        public String parentId;
        public Author author = new Author();
        public String text = "";
        public long createdAtMs;
        public String status = STATUS_APPROVED;
        public String rejectReason;
        /** Set by {@link #thread}: 0 = comment, 1 = reply. */
        public int depth;
    }

    public static final class Page<T> {
        public final List<T> items = new ArrayList<>();
        public String nextCursor;
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
        p.likeCount = Math.max(0, o.optInt("like_count", 0));
        p.commentCount = Math.max(0, o.optInt("comment_count", 0));
        p.liked = o.optBoolean("liked", false);
        p.createdAtMs = parseTime(o.optString("created_at", ""));
        p.status = normalizeStatus(o.optString("status", STATUS_APPROVED));
        p.rejectReason = str(o, "reject_reason");
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
        c.status = normalizeStatus(o.optString("status", STATUS_APPROVED));
        c.rejectReason = str(o, "reject_reason");
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

    static String normalizeStatus(String s) {
        if (STATUS_PENDING.equals(s) || STATUS_REJECTED.equals(s)) return s;
        return STATUS_APPROVED;
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

    public static boolean isValidPost(String text, int images) {
        int n = length(text == null ? null : text.trim());
        return images >= 0 && images <= IMAGES_MAX && n <= TEXT_MAX && (n > 0 || images > 0);
    }

    public static boolean isValidComment(String text) {
        int n = length(text == null ? null : text.trim());
        return n > 0 && n <= COMMENT_MAX;
    }

    /** Output size with the long edge ≤ {@link #IMAGE_MAX_EDGE}, keeping the aspect ratio. */
    public static int[] scaledSize(int width, int height) {
        if (width <= 0 || height <= 0) return new int[]{0, 0};
        int edge = Math.max(width, height);
        if (edge <= IMAGE_MAX_EDGE) return new int[]{width, height};
        double k = (double) IMAGE_MAX_EDGE / edge;
        return new int[]{Math.max(1, (int) Math.round(width * k)), Math.max(1, (int) Math.round(height * k))};
    }

    /** Largest power-of-two sample size that still keeps the long edge ≥ the target (decode step). */
    public static int sampleSize(int width, int height) {
        int edge = Math.max(width, height);
        int s = 1;
        while (edge / (s * 2) >= IMAGE_MAX_EDGE) s *= 2;
        return s;
    }

    public static JSONObject postBody(String text, List<String> imageKeys) throws Exception {
        JSONArray keys = new JSONArray();
        if (imageKeys != null) for (String k : imageKeys) keys.put(k);
        return new JSONObject().put("text", text == null ? "" : text.trim()).put("image_keys", keys);
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
        public String hot() { return t("热门", "Popular"); }
        public String compose() { return t("发帖", "New post"); }
        public String loadMore() { return t("加载更多", "Load more"); }
        public String loading() { return t("正在加载…", "Loading…"); }
        public String empty() { return t("还没有帖子", "No posts yet"); }
        public String noMore() { return t("没有更多了", "That's all"); }
        public String refresh() { return t("刷新", "Refresh"); }

        public String pending() { return t("审核中", "Under review"); }
        public String rejected() { return t("未通过", "Not approved"); }
        public String rejectedReason(String reason) {
            return reason == null || reason.isEmpty() ? rejected() : t("未通过：" + reason, "Not approved: " + reason);
        }
        public String likes(int n) { return t("赞 " + n, n == 1 ? "1 like" : n + " likes"); }
        public String comments(int n) { return t("评论 " + n, n == 1 ? "1 comment" : n + " comments"); }
        public String like() { return t("赞", "Like"); }
        public String unlike() { return t("取消赞", "Unlike"); }
        public String comment() { return t("评论", "Comment"); }
        public String reply() { return t("回复", "Reply"); }
        public String replyTo(String name) { return t("回复 " + name, "Reply to " + name); }
        public String commentHint() { return t("写评论（最多 300 字）", "Write a comment (up to 300 characters)"); }
        public String send() { return t("发送", "Send"); }
        public String delete() { return t("删除", "Delete"); }
        public String deletePostConfirm() { return t("删除这条帖子？", "Delete this post?"); }
        public String deleteCommentConfirm() { return t("删除这条评论？", "Delete this comment?"); }
        public String deleted() { return t("已删除", "Deleted"); }
        public String noComments() { return t("还没有评论", "No comments yet"); }
        public String commentsHeader() { return t("评论", "Comments"); }

        public String composeTitle() { return t("发帖", "New post"); }
        public String textHint() { return t("说点什么（最多 1000 字）", "Say something (up to 1000 characters)"); }
        public String addImages(int n) { return t("添加图片（" + n + "/9）", "Add images (" + n + "/9)"); }
        public String removeImages() { return t("清空图片", "Remove images"); }
        public String publish() { return t("发布", "Post"); }
        public String publishing() { return t("正在发布…", "Posting…"); }
        public String published() { return t("已发布，审核通过后出现在广场", "Posted. It will appear in Square once it's approved."); }
        public String imagesInfo() { return t("图片会先压缩到长边 2048 像素，并去掉位置等 EXIF 信息再上传。", "Images are resized to 2048 px on the long edge and their EXIF data (including location) is removed before upload."); }
        public String tooLong() { return t("内容太长", "Too long"); }
        public String needContent() { return t("写点文字或添加图片", "Add some text or images"); }
        public String imageFailed() { return t("有图片处理失败，请换一张", "An image couldn't be processed. Try another one."); }

        public String profileTitle() { return t("广场名片", "Square profile"); }
        public String profileNeeded() { return t("发帖前先填写广场名片，别人会在你的帖子上看到它。", "Fill in your Square profile first; others see it on your posts."); }
        public String displayName() { return t("显示名", "Name"); }
        public String bio() { return t("简介", "Bio"); }
        public String publicUsername() { return t("公开用户名（可选，别人可以给你发消息）", "Public username (optional, lets others message you)"); }
        public String useMyUsername(String u) { return t("显示 @" + u, "Show @" + u); }
        public String save() { return t("保存", "Save"); }
        public String saved() { return t("已保存", "Saved"); }
        public String editProfile() { return t("编辑广场名片", "Edit Square profile"); }

        public String authorPosts() { return t("帖子", "Posts"); }
        public String sendMessage() { return t("发消息", "Send message"); }

        public String needLinkTitle() { return t("需要先绑定 Telegram 账号", "Link your Telegram account first"); }
        public String needLinkText() { return t("浏览不需要绑定；发帖、点赞、评论和举报需要先在「Luckgram 功能 → 账号」里绑定。", "Browsing works without it; posting, liking, commenting and reporting need a linked account (Luckgram Features → Account)."); }
        public String goLink() { return t("去绑定", "Link account"); }
        public String cancel() { return t("取消", "Cancel"); }

        public String report() { return t("举报", "Report"); }
        public String reportPost() { return t("举报帖子", "Report post"); }
        public String reportComment() { return t("举报评论", "Report comment"); }
        public String reportAuthor() { return t("举报作者", "Report author"); }
        public String reportTitle() { return t("举报原因", "Reason"); }
        public String reportReason(String r) {
            switch (r) {
                case "spam": return t("垃圾信息", "Spam");
                case "scam": return t("诈骗", "Scam");
                case "porn": return t("色情", "Pornography");
                case "violence": return t("暴力", "Violence");
                case "hate": return t("仇恨", "Hate");
                case "impersonation": return t("冒充他人", "Impersonation");
                default: return t("其他", "Other");
            }
        }
        public String reported() { return t("已举报，感谢反馈", "Reported. Thank you."); }
        public String block() { return t("拉黑作者", "Block author"); }
        public String blockConfirm(String name) { return t("拉黑后你和 " + name + " 互相看不到对方的帖子和评论，「附近的人」里也互相不可见。", "You and " + name + " won't see each other's posts and comments, or each other in People Nearby."); }
        public String blocked() { return t("已拉黑", "Blocked"); }
        public String more() { return t("更多", "More"); }

        public String deleteMyData() { return t("删除我的广场数据", "Delete my Square data"); }
        public String deleteMyDataConfirm() { return t("会删除你在广场的全部帖子、评论、点赞和图片，不能恢复。", "This deletes all your Square posts, comments, likes and images. It can't be undone."); }
        public String deletedMyData() { return t("已删除你的广场数据", "Your Square data was deleted"); }

        public String waitSeconds(long seconds) {
            if (seconds <= 0) return t("操作太频繁，请稍后再试", "Too many requests. Try again later.");
            if (seconds < 60) return t("操作太频繁，请 " + seconds + " 秒后再试", "Too many requests. Try again in " + seconds + " s.");
            long min = (seconds + 59) / 60;
            if (min < 60) return t("操作太频繁，请 " + min + " 分钟后再试", "Too many requests. Try again in " + min + " min.");
            long h = (min + 59) / 60;
            return t("今天发帖已达上限，请 " + h + " 小时后再试", "Daily post limit reached. Try again in " + h + " h.");
        }
        public String banned() { return t("你的账号已被限制在广场发言", "Your account can't post in Square"); }

        public String timeAgo(long createdAtMs, long nowMs) {
            long s = Math.max(0, (nowMs - createdAtMs) / 1000);
            if (createdAtMs <= 0) return "";
            if (s < 60) return t("刚刚", "just now");
            if (s < 3600) return t((s / 60) + " 分钟前", (s / 60) + " min ago");
            if (s < 86400) return t((s / 3600) + " 小时前", (s / 3600) + " h ago");
            return t((s / 86400) + " 天前", (s / 86400) + " d ago");
        }
    }
}
