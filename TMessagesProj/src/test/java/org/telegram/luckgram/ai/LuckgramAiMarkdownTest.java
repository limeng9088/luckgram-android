package org.telegram.luckgram.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class LuckgramAiMarkdownTest {

    private static LuckgramAiMarkdown.Span only(List<LuckgramAiMarkdown.Span> spans, int type) {
        LuckgramAiMarkdown.Span found = null;
        for (LuckgramAiMarkdown.Span s : spans) {
            if (s.type == type) {
                assertTrue("more than one span of type " + type, found == null);
                found = s;
            }
        }
        assertTrue("no span of type " + type, found != null);
        return found;
    }

    @Test
    public void plainTextIsUnchanged() {
        LuckgramAiMarkdown.Result r = LuckgramAiMarkdown.parse("太阳能板把光变成电。\n第二行 2*3=6");
        assertEquals("太阳能板把光变成电。\n第二行 2*3=6", r.text);
        assertTrue(r.spans.isEmpty());
    }

    @Test
    public void boldItalicCodeStrike() {
        LuckgramAiMarkdown.Result r = LuckgramAiMarkdown.parse("a **bold** b *it* c `x = 1` d ~~no~~");
        assertEquals("a bold b it c x = 1 d no", r.text);
        LuckgramAiMarkdown.Span b = only(r.spans, LuckgramAiMarkdown.BOLD);
        assertEquals("bold", r.text.substring(b.start, b.end));
        LuckgramAiMarkdown.Span i = only(r.spans, LuckgramAiMarkdown.ITALIC);
        assertEquals("it", r.text.substring(i.start, i.end));
        LuckgramAiMarkdown.Span c = only(r.spans, LuckgramAiMarkdown.CODE);
        assertEquals("x = 1", r.text.substring(c.start, c.end));
        LuckgramAiMarkdown.Span s = only(r.spans, LuckgramAiMarkdown.STRIKE);
        assertEquals("no", r.text.substring(s.start, s.end));
    }

    @Test
    public void snakeCaseIsNotItalic() {
        LuckgramAiMarkdown.Result r = LuckgramAiMarkdown.parse("use snake_case_name here");
        assertEquals("use snake_case_name here", r.text);
        assertTrue(r.spans.isEmpty());
    }

    @Test
    public void codeBlockKeepsContentVerbatim() {
        LuckgramAiMarkdown.Result r = LuckgramAiMarkdown.parse("Code:\n```python\nprint(\"**hi**\")\nx = 1\n```\nDone");
        assertEquals("Code:\nprint(\"**hi**\")\nx = 1\nDone", r.text);
        LuckgramAiMarkdown.Span cb = only(r.spans, LuckgramAiMarkdown.CODE_BLOCK);
        assertEquals("print(\"**hi**\")\nx = 1", r.text.substring(cb.start, cb.end));
        assertEquals("python", cb.value);
    }

    @Test
    public void unclosedCodeBlockWhileStreaming() {
        LuckgramAiMarkdown.Result r = LuckgramAiMarkdown.parse("```\nint a;");
        assertEquals("int a;", r.text);
        only(r.spans, LuckgramAiMarkdown.CODE_BLOCK);
    }

    @Test
    public void unclosedMarkersStayLiteral() {
        LuckgramAiMarkdown.Result r = LuckgramAiMarkdown.parse("**half and `tick");
        assertEquals("**half and `tick", r.text);
        assertTrue(r.spans.isEmpty());
    }

    @Test
    public void headingsListsAndLinks() {
        LuckgramAiMarkdown.Result r = LuckgramAiMarkdown.parse("## 标题\n- one\n- **two**\n[site](https://example.com) and [bad](javascript:x)");
        assertEquals("标题\n• one\n• two\nsite and [bad](javascript:x)", r.text);
        LuckgramAiMarkdown.Span h = only(r.spans, LuckgramAiMarkdown.HEADING);
        assertEquals("标题", r.text.substring(h.start, h.end));
        assertEquals("2", h.value);
        LuckgramAiMarkdown.Span l = only(r.spans, LuckgramAiMarkdown.LINK);
        assertEquals("site", r.text.substring(l.start, l.end));
        assertEquals("https://example.com", l.value);
        LuckgramAiMarkdown.Span b = only(r.spans, LuckgramAiMarkdown.BOLD);
        assertEquals("two", r.text.substring(b.start, b.end));
    }

    @Test
    public void escapes() {
        LuckgramAiMarkdown.Result r = LuckgramAiMarkdown.parse("\\*not italic\\*");
        assertEquals("*not italic*", r.text);
        assertTrue(r.spans.isEmpty());
    }
}
