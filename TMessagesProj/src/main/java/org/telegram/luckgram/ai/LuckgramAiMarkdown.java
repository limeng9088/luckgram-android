package org.telegram.luckgram.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal Markdown for AI replies (spec 18 需求 4): code blocks (```), headings (#), bullet and
 * numbered lists, **bold**, *italic* / _italic_, `inline code`, ~~strike~~ and [links](url).
 *
 * <p>Pure Java: {@link #parse} returns the display text plus style ranges; the UI turns the ranges
 * into spans. Unclosed markers are left as they are, so half-streamed text renders sensibly.
 */
public final class LuckgramAiMarkdown {

    private LuckgramAiMarkdown() {
    }

    public static final int BOLD = 1, ITALIC = 2, CODE = 3, CODE_BLOCK = 4, LINK = 5, HEADING = 6, STRIKE = 7;

    public static final class Span {
        public final int type, start, end;
        /** Link target for {@link #LINK}, language for {@link #CODE_BLOCK} (may be empty). */
        public final String value;

        Span(int type, int start, int end, String value) {
            this.type = type;
            this.start = start;
            this.end = end;
            this.value = value;
        }

        @Override
        public String toString() {
            return type + "[" + start + "," + end + ")" + (value == null ? "" : value);
        }
    }

    public static final class Result {
        public final String text;
        public final List<Span> spans;

        Result(String text, List<Span> spans) {
            this.text = text;
            this.spans = spans;
        }
    }

    public static Result parse(String md) {
        StringBuilder out = new StringBuilder();
        ArrayList<Span> spans = new ArrayList<>();
        if (md == null) {
            return new Result("", spans);
        }
        String[] lines = md.replace("\r\n", "\n").split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                String lang = trimmed.substring(3).trim();
                StringBuilder code = new StringBuilder();
                int j = i + 1;
                boolean closed = false;
                for (; j < lines.length; j++) {
                    if (lines[j].trim().startsWith("```")) {
                        closed = true;
                        break;
                    }
                    if (code.length() > 0) code.append('\n');
                    code.append(lines[j]);
                }
                int start = out.length();
                out.append(code);
                spans.add(new Span(CODE_BLOCK, start, out.length(), lang));
                i = closed ? j + 1 : j;
                if (i < lines.length) out.append('\n');
                continue;
            }
            int headingLevel = 0;
            while (headingLevel < trimmed.length() && headingLevel < 6 && trimmed.charAt(headingLevel) == '#') {
                headingLevel++;
            }
            if (headingLevel > 0 && headingLevel < trimmed.length() && trimmed.charAt(headingLevel) == ' ') {
                int start = out.length();
                inline(trimmed.substring(headingLevel + 1).trim(), out, spans);
                spans.add(new Span(HEADING, start, out.length(), String.valueOf(headingLevel)));
            } else if (isBullet(trimmed)) {
                int indent = line.indexOf(trimmed.charAt(0));
                for (int k = 0; k < indent / 2; k++) out.append("  ");
                out.append("• ");
                inline(trimmed.substring(2).trim(), out, spans);
            } else {
                inline(line, out, spans);
            }
            i++;
            if (i < lines.length) out.append('\n');
        }
        return new Result(out.toString(), spans);
    }

    private static boolean isBullet(String t) {
        return t.length() > 2 && (t.charAt(0) == '-' || t.charAt(0) == '*' || t.charAt(0) == '+') && t.charAt(1) == ' ';
    }

    /** Inline markup of one line, appended to {@code out}. */
    static void inline(String s, StringBuilder out, List<Span> spans) {
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < n && "\\`*_~[]()#".indexOf(s.charAt(i + 1)) >= 0) {
                out.append(s.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '`') {
                int end = s.indexOf('`', i + 1);
                if (end > i + 1) {
                    int start = out.length();
                    out.append(s, i + 1, end);
                    spans.add(new Span(CODE, start, out.length(), null));
                    i = end + 1;
                    continue;
                }
            }
            if (s.startsWith("**", i) || s.startsWith("__", i)) {
                String marker = s.substring(i, i + 2);
                int end = s.indexOf(marker, i + 2);
                if (end > i + 2) {
                    int start = out.length();
                    inline(s.substring(i + 2, end), out, spans);
                    spans.add(new Span(BOLD, start, out.length(), null));
                    i = end + 2;
                    continue;
                }
            }
            if (s.startsWith("~~", i)) {
                int end = s.indexOf("~~", i + 2);
                if (end > i + 2) {
                    int start = out.length();
                    inline(s.substring(i + 2, end), out, spans);
                    spans.add(new Span(STRIKE, start, out.length(), null));
                    i = end + 2;
                    continue;
                }
            }
            if ((c == '*' || c == '_') && i + 1 < n && s.charAt(i + 1) != ' ' && s.charAt(i + 1) != c
                && (c == '*' || i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1)))) {
                int end = s.indexOf(c, i + 1);
                if (end > i + 1 && s.charAt(end - 1) != ' ' && (c == '*' || end + 1 >= n || !Character.isLetterOrDigit(s.charAt(end + 1)))) {
                    int start = out.length();
                    inline(s.substring(i + 1, end), out, spans);
                    spans.add(new Span(ITALIC, start, out.length(), null));
                    i = end + 1;
                    continue;
                }
            }
            if (c == '[') {
                int close = s.indexOf("](", i + 1);
                int paren = close < 0 ? -1 : s.indexOf(')', close + 2);
                if (close > i + 1 && paren > close + 2) {
                    String url = s.substring(close + 2, paren).trim();
                    if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("tg://")) {
                        int start = out.length();
                        inline(s.substring(i + 1, close), out, spans);
                        spans.add(new Span(LINK, start, out.length(), url));
                        i = paren + 1;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
    }
}
