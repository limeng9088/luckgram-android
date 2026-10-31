package org.telegram.luckgram.post;

import java.util.List;

/** Pure helpers of spec 24 (unit-tested): the requests sent with task=post. */
final class AiPostText {

    private AiPostText() {
    }

    /** spec 24 需求 2 */
    static final int EXAMPLES = 20;
    static final int MAX_EXAMPLE_CHARS = 1500;

    static final String SHORTER = "Make it shorter, keeping the key information.";
    static final String EMOJI = "Add more fitting emoji, keeping the text.";
    static final String REWRITE = "Rewrite it with the same information in a fresh way.";

    static String translate(String language) {
        return "Translate it into " + language + ". Keep the formatting and emoji.";
    }

    /**
     * Style examples (newest first is fine, they are only references), cut so the whole request
     * stays within {@code maxChars}.
     */
    static String examples(List<String> posts, int maxChars) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String p : posts) {
            if (count >= EXAMPLES) break;
            String t = p == null ? "" : p.trim();
            if (t.isEmpty()) continue;
            if (t.length() > MAX_EXAMPLE_CHARS) t = t.substring(0, MAX_EXAMPLE_CHARS) + "…";
            if (sb.length() + t.length() + 8 > maxChars) break;
            sb.append("---\n").append(t).append('\n');
            count++;
        }
        return sb.toString();
    }

    /** A new post about {@code topic}. */
    static String generate(String examples, String topic) {
        StringBuilder sb = new StringBuilder();
        if (!examples.isEmpty()) {
            sb.append("Example posts from this channel (style reference only, do not copy):\n").append(examples).append("---\n\n");
        }
        sb.append("Topic: ").append(topic.trim()).append("\nWrite a new post for the channel.");
        return sb.toString();
    }

    /** An edit of the current draft (spec 24 需求 3: always based on the current draft). */
    static String edit(String examples, String draft, String instruction) {
        StringBuilder sb = new StringBuilder();
        if (!examples.isEmpty()) {
            sb.append("Example posts from this channel (style reference only, do not copy):\n").append(examples).append("---\n\n");
        }
        sb.append("Current draft:\n").append(draft.trim()).append("\n\nInstruction: ").append(instruction);
        return sb.toString();
    }
}
