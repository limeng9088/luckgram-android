package org.telegram.luckgram.reply;

import java.util.ArrayList;
import java.util.List;

/** Pure helpers of spec 20 (unit-tested). */
final class AiReplyText {

    private AiReplyText() {
    }

    static String prompt(String transcript, String draft, String me) {
        StringBuilder sb = new StringBuilder("Recent messages (oldest first; \"").append(me).append("\" is me):\n");
        sb.append(transcript.isEmpty() ? "(none)" : transcript);
        if (draft != null && !draft.trim().isEmpty()) {
            sb.append("\n\nMy draft: ").append(draft.trim());
        } else {
            sb.append("\n\nI have no draft; suggest what I could reply.");
        }
        return sb.toString();
    }

    /** The candidates in streamed text: complete lines, plus the line being written. */
    static List<String> candidates(String text) {
        ArrayList<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String raw : text.split("\n")) {
            String line = raw.trim().replaceFirst("^(\\d[.)、]|[-•*])\\s*", "");
            if (line.length() >= 2 && (line.startsWith("\"") && line.endsWith("\"") || line.startsWith("“") && line.endsWith("”"))) {
                line = line.substring(1, line.length() - 1).trim();
            }
            if (!line.isEmpty()) out.add(line);
            if (out.size() == 3) break;
        }
        return out;
    }
}
