package org.telegram.luckgram.summary;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * spec 19, pure Java (unit-tested): the transcript sent to the model, splitting long ranges into
 * parts that fit {@code ai.max_input_chars}, and parsing the {@code [#123]} message references the
 * model puts after each point. Same rules as iOS {@code LuckgramChatSummaryLogic}.
 */
public final class ChatSummaryLogic {

    private ChatSummaryLogic() {
    }

    /** One chat message as the model sees it. */
    public static final class Line {
        public final int id;
        public final String sender;
        /** e.g. "10-31 14:05" */
        public final String time;
        /** Text, or a media placeholder like "[图片] caption". */
        public final String text;

        public Line(int id, String sender, String time, String text) {
            this.id = id;
            this.sender = sender;
            this.time = time;
            this.text = text;
        }

        String format() {
            return "[#" + id + "] " + sender + " (" + time + "): " + text.replace('\n', ' ').trim();
        }
    }

    /** How references must look; added to every request (the server's prompt doesn't ask for them). */
    public static final String CITE_RULE = "After each point, cite the message IDs it is based on, like [#123] or [#123, #456]. Use only IDs that appear in the messages.";

    /** Lines longer than this are cut, so one long message can't take a whole part. */
    static final int MAX_LINE_CHARS = 2000;

    public static String header(boolean merge) {
        if (merge) {
            return "These are summaries of consecutive parts of one chat. Merge them into 3 to 8 bullet points, keeping the [#ID] citations.\n" + CITE_RULE + "\n\n";
        }
        return "Summarize these chat messages.\n" + CITE_RULE + "\n\nMessages (oldest first):\n";
    }

    /**
     * Splits the lines (oldest first) into transcripts of at most {@code maxChars} characters each,
     * including the header. Always returns at least one part when there are lines.
     */
    public static List<String> split(List<Line> lines, int maxChars) {
        ArrayList<String> parts = new ArrayList<>();
        String header = header(false);
        int budget = Math.max(1000, maxChars - header.length() - 200);
        StringBuilder current = new StringBuilder();
        for (Line l : lines) {
            String s = l.format();
            if (s.length() > MAX_LINE_CHARS) {
                s = s.substring(0, MAX_LINE_CHARS) + "…";
            }
            if (current.length() > 0 && current.length() + s.length() + 1 > budget) {
                parts.add(header + current);
                current.setLength(0);
            }
            if (current.length() > 0) current.append('\n');
            current.append(s);
        }
        if (current.length() > 0) {
            parts.add(header + current);
        }
        return parts;
    }

    /** A piece of an answer: plain text, or a reference to message {@link #messageId}. */
    public static final class Segment {
        public final String text;
        /** 0 for plain text. */
        public final int messageId;

        Segment(String text, int messageId) {
            this.text = text;
            this.messageId = messageId;
        }

        @Override
        public String toString() {
            return messageId == 0 ? text : "{" + messageId + "}";
        }
    }

    private static final Pattern CITATION = Pattern.compile("[\\[（(]\\s*#?\\d+(?:\\s*[,，、]\\s*#?\\d+)*\\s*[\\]）)]");
    private static final Pattern NUMBER = Pattern.compile("\\d+");

    /**
     * Splits {@code text} into plain text and references. References to IDs not in {@code validIds}
     * are dropped (spec 19 需求 3: the client checks the ID is really in the range).
     */
    public static List<Segment> parse(String text, Collection<Integer> validIds) {
        ArrayList<Segment> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = CITATION.matcher(text);
        int last = 0;
        while (m.find()) {
            String token = m.group();
            if (!token.contains("#") && !token.startsWith("[")) {
                continue; // "(2024)" in normal text is not a citation
            }
            LinkedHashSet<Integer> ids = new LinkedHashSet<>();
            Matcher n = NUMBER.matcher(token);
            while (n.find()) {
                try {
                    int id = Integer.parseInt(n.group());
                    if (validIds.contains(id)) ids.add(id);
                } catch (NumberFormatException ignore) {
                }
            }
            if (ids.isEmpty() && !token.contains("#")) {
                continue; // e.g. "[1]" that is not one of our IDs: keep the text
            }
            String before = text.substring(last, m.start());
            String trimmed = trimEndBeforeCitation(before);
            if (!trimmed.isEmpty()) out.add(new Segment(trimmed, 0));
            for (int id : ids) out.add(new Segment(null, id));
            last = m.end();
        }
        if (last < text.length()) out.add(new Segment(text.substring(last), 0));
        return out;
    }

    private static String trimEndBeforeCitation(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ') end--;
        return s.substring(0, end);
    }

    /** The bullet points of a summary, without their "- " / "• " / "1. " markers. */
    public static List<String> bullets(String text) {
        ArrayList<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ")) {
                line = line.substring(2).trim();
            } else if (line.matches("^\\d{1,2}[.)、]\\s*.*")) {
                line = line.replaceFirst("^\\d{1,2}[.)、]\\s*", "");
            } else if (!out.isEmpty()) {
                // continuation of the previous point
                out.set(out.size() - 1, out.get(out.size() - 1) + " " + line);
                continue;
            }
            if (!line.isEmpty()) out.add(line);
        }
        return out;
    }
}
