package org.telegram.luckgram.ai;

import android.text.TextUtils;

import org.telegram.luckgram.LuckgramStrings;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;

/**
 * spec 18 需求 5: "问 AI" in the message menu. Builds the text that goes into the assistant's input
 * field: the message text, its links, the file name and, for text files of at most 1 MB that are
 * already on the device, their content.
 */
public final class LuckgramAskAi {

    private LuckgramAskAi() {
    }

    /** spec 18 需求 5: text files up to 1 MB are read. */
    static final long MAX_TEXT_FILE = 1024 * 1024;

    private static final String[] TEXT_EXTENSIONS = {"txt", "md", "markdown", "csv", "tsv", "json", "xml", "html", "htm", "log", "yaml", "yml",
        "ini", "conf", "srt", "vtt", "java", "kt", "swift", "js", "ts", "py", "go", "rs", "c", "h", "cpp", "hpp", "cs", "rb", "php", "sh", "sql", "css"};

    public static boolean canAsk(MessageObject m) {
        if (!LuckgramAiChatActivity.isEnabled() || m == null || m.messageOwner == null) {
            return false;
        }
        return !TextUtils.isEmpty(m.messageOwner.message) || m.getDocument() != null || webpageUrl(m) != null;
    }

    public static String buildPrompt(ArrayList<MessageObject> messages) {
        StringBuilder text = new StringBuilder();
        LinkedHashSet<String> links = new LinkedHashSet<>();
        StringBuilder files = new StringBuilder();
        for (MessageObject m : messages) {
            if (m == null || m.messageOwner == null) continue;
            String body = m.messageOwner.message;
            if (!TextUtils.isEmpty(body)) {
                if (text.length() > 0) text.append("\n\n");
                text.append(body.trim());
            }
            if (m.messageOwner.entities != null) {
                for (TLRPC.MessageEntity e : m.messageOwner.entities) {
                    if (e instanceof TLRPC.TL_messageEntityTextUrl && !TextUtils.isEmpty(e.url)) {
                        links.add(e.url);
                    }
                }
            }
            String web = webpageUrl(m);
            if (web != null && (body == null || !body.contains(web))) {
                links.add(web);
            }
            TLRPC.Document doc = m.getDocument();
            if (doc != null && !m.isVoice() && !m.isRoundVideo() && !m.isSticker() && !m.isAnimatedSticker()) {
                String name = FileLoader.getDocumentFileName(doc);
                if (!TextUtils.isEmpty(name)) {
                    files.append('\n').append(LuckgramStrings.get("文件：", "File: ")).append(name);
                }
                String content = readTextFile(m, doc, name);
                if (content != null) {
                    files.append('\n').append(LuckgramStrings.get("文件内容：", "File content:")).append("\n```\n").append(content).append("\n```");
                }
            }
        }
        StringBuilder out = new StringBuilder(text);
        for (String link : links) {
            out.append('\n').append(LuckgramStrings.get("链接：", "Link: ")).append(link);
        }
        out.append(files);
        String s = out.toString().trim();
        return s.isEmpty() ? "" : s + "\n\n";
    }

    private static String webpageUrl(MessageObject m) {
        TLRPC.MessageMedia media = MessageObject.getMedia(m.messageOwner);
        if (media instanceof TLRPC.TL_messageMediaWebPage && media.webpage != null && !TextUtils.isEmpty(media.webpage.url)) {
            return media.webpage.url;
        }
        return null;
    }

    static boolean isTextFile(String mime, String name) {
        if (mime != null && (mime.startsWith("text/") || mime.equals("application/json") || mime.equals("application/xml"))) {
            return true;
        }
        if (name == null) return false;
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (String e : TEXT_EXTENSIONS) {
            if (e.equals(ext)) return true;
        }
        return false;
    }

    /** Content of a downloaded text file of at most 1 MB, else null. */
    private static String readTextFile(MessageObject m, TLRPC.Document doc, String name) {
        if (doc.size > MAX_TEXT_FILE || !isTextFile(doc.mime_type, name)) {
            return null;
        }
        File f = null;
        if (!TextUtils.isEmpty(m.messageOwner.attachPath)) {
            f = new File(m.messageOwner.attachPath);
        }
        if (f == null || !f.exists()) {
            f = FileLoader.getInstance(m.currentAccount).getPathToMessage(m.messageOwner);
        }
        if (f == null || !f.exists() || f.length() == 0 || f.length() > MAX_TEXT_FILE) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            return new String(buf, 0, off, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }
}
