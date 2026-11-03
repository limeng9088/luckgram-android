package org.telegram.luckgram.agents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONObject;
import org.junit.Test;

/** specs#88 (30): S13 parsing, request paths, cache / offline search, "我的" list, Pro gate. */
public class AgentMarketLogicTest {

    private static final String PAGE = "{\"items\":["
        + "{\"id\":\"email-pro\",\"name\":{\"zh\":\"职场邮件\",\"en\":\"Work email\"},\"description\":{\"zh\":\"写邮件\",\"en\":\"Writes emails\"},"
        + "\"category\":\"work\",\"avatar\":{\"color\":\"#475569\",\"emoji\":\"📧\"},\"examples\":[{\"zh\":\"请假邮件\",\"en\":\"Leave email\"}],"
        + "\"author\":\"luckgram\",\"pro_only\":false,\"model_tier\":\"standard\",\"users\":12345,\"instruction\":\"SECRET\"},"
        + "{\"id\":\"translator-x\",\"name\":{\"zh\":\"翻译官\",\"en\":\"Translator\"},\"description\":{\"zh\":\"中英互译\",\"en\":\"Translate\"},"
        + "\"category\":\"study\",\"avatar\":{\"color\":\"bad\"},\"pro_only\":true,\"model_tier\":\"premium\",\"users\":3},"
        + "{\"name\":{\"zh\":\"没有 id\"}}],\"next_cursor\":\"bzIw\"}";

    @Test
    public void parsesPageWithoutInstruction() throws Exception {
        AgentMarketLogic.Page p = AgentMarketLogic.parsePage(new JSONObject(PAGE));
        assertEquals(2, p.items.size());
        assertEquals("bzIw", p.nextCursor);
        AgentMarketLogic.Agent a = p.items.get(0);
        assertEquals("职场邮件", a.name.pick(true));
        assertEquals("Work email", a.name.pick(false));
        assertEquals(0xFF475569, a.color);
        assertEquals("📧", a.avatarText(true));
        assertEquals(1, a.examples.size());
        assertEquals(12345, a.users);
        assertFalse(a.toJson().toString().contains("SECRET"));
        AgentMarketLogic.Agent b = p.items.get(1);
        assertEquals(AgentMarketLogic.DEFAULT_COLOR, b.color);
        assertTrue(b.proOnly);
        assertTrue(b.isPremium());
        assertEquals("翻", b.avatarText(true));
        assertNull(AgentMarketLogic.parsePage(new JSONObject("{\"items\":[],\"next_cursor\":null}")).nextCursor);
    }

    @Test
    public void categories() throws Exception {
        List<AgentMarketLogic.Category> c = AgentMarketLogic.parseCategories(new JSONObject(
            "{\"categories\":[{\"id\":\"writing\",\"name\":{\"zh\":\"写作\",\"en\":\"Writing\"},\"count\":4},{\"name\":{}}]}"));
        assertEquals(1, c.size());
        assertEquals("写作", c.get(0).name.pick(true));
        assertEquals(4, c.get(0).count);
    }

    @Test
    public void paths() {
        assertEquals("/v1/agents?limit=20", AgentMarketLogic.listPath("", " ", null, 20));
        assertEquals("/v1/agents?limit=20&category=work&q=%E7%BF%BB%E8%AF%91&cursor=bzIw",
            AgentMarketLogic.listPath("work", "翻译", "bzIw", 20));
        assertEquals("/v1/agents?limit=5&q=a%20b", AgentMarketLogic.listPath(null, "a b", "", 5));
        StringBuilder longQ = new StringBuilder();
        for (int i = 0; i < 60; i++) longQ.append('x');
        assertTrue(AgentMarketLogic.listPath(null, longQ.toString(), null, 5).endsWith("&q=" + longQ.substring(0, 50)));
        assertEquals("/v1/agents/email-pro", AgentMarketLogic.detailPath("email-pro"));
        assertEquals("{\"agent_id\":\"email-pro\"}", AgentMarketLogic.chatParams("email-pro").toString());
        assertEquals("market:email-pro", AgentMarketLogic.conversationKey("email-pro"));
    }

    @Test
    public void search() throws Exception {
        AgentMarketLogic.Agent a = AgentMarketLogic.parsePage(new JSONObject(PAGE)).items.get(1);
        assertTrue(a.matches("翻译"));
        assertTrue(a.matches("TRANSL"));
        assertTrue(a.matches(""));
        assertFalse(a.matches("邮件"));
    }

    @Test
    public void cacheAndOffline() throws Exception {
        AgentMarketLogic.Cache c = new AgentMarketLogic.Cache(null);
        c.putFirstPage("", new JSONObject(PAGE));
        c.putCategories(new JSONObject("{\"categories\":[{\"id\":\"work\",\"name\":{\"zh\":\"工作\",\"en\":\"Work\"},\"count\":1}]}"));
        AgentMarketLogic.Cache back = new AgentMarketLogic.Cache(c.toString());
        assertEquals(2, back.firstPage("").items.size());
        assertEquals(1, back.categories().size());
        assertEquals(2, back.offline("", "").size());
        assertEquals(1, back.offline("work", "").size());
        assertEquals("translator-x", back.offline("", "翻译").get(0).id);
        assertEquals(0, back.offline("work", "翻译").size());
        assertEquals("email-pro", back.detail("email-pro").id);
        assertNull(back.detail("nope"));
        assertEquals(0, new AgentMarketLogic.Cache("not json").allAgents().size());
    }

    @Test
    public void usedList() throws Exception {
        List<AgentMarketLogic.Agent> items = AgentMarketLogic.parsePage(new JSONObject(PAGE)).items;
        List<AgentMarketLogic.Agent> used = new ArrayList<>();
        used = AgentMarketLogic.markUsed(used, items.get(0));
        used = AgentMarketLogic.markUsed(used, items.get(1));
        used = AgentMarketLogic.markUsed(used, items.get(0));
        assertEquals(2, used.size());
        assertEquals("email-pro", used.get(0).id);
        List<AgentMarketLogic.Agent> back = AgentMarketLogic.parseUsed(AgentMarketLogic.usedToString(used));
        assertEquals("translator-x", back.get(1).id);
        assertEquals("📧", back.get(0).emoji);
        assertEquals(1, AgentMarketLogic.removeUsed(back, "email-pro").size());
        assertEquals(0, AgentMarketLogic.parseUsed("garbage").size());
    }

    @Test
    public void proGate() throws Exception {
        List<AgentMarketLogic.Agent> items = AgentMarketLogic.parsePage(new JSONObject(PAGE)).items;
        assertFalse(AgentMarketLogic.needsUpgrade(items.get(0), false));
        assertTrue(AgentMarketLogic.needsUpgrade(items.get(1), false));
        assertFalse(AgentMarketLogic.needsUpgrade(items.get(1), true));
    }

    @Test
    public void users() {
        assertEquals("12人使用", AgentMarketLogic.formatUsers(12, true));
        assertEquals("1.2万人使用", AgentMarketLogic.formatUsers(12345, true));
        assertEquals("1 user", AgentMarketLogic.formatUsers(1, false));
        assertEquals("12.3K users", AgentMarketLogic.formatUsers(12345, false));
        assertEquals("2M users", AgentMarketLogic.formatUsers(2_000_000, false));
    }
}
