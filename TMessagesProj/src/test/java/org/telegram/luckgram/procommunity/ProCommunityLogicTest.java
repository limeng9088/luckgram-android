package org.telegram.luckgram.procommunity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class ProCommunityLogicTest {

    @Test
    public void inviteLinkIsJoin() throws Exception {
        ProCommunityLogic.Invite i = ProCommunityLogic.parse(new JSONObject("{\"invite_link\":\"https://t.me/+AbC123\",\"expires_at\":\"2026-11-04T00:00:00Z\"}"));
        assertEquals(ProCommunityLogic.Invite.JOIN, i.kind);
        assertEquals("https://t.me/+AbC123", i.link);
    }

    @Test
    public void alreadyMemberInBody() throws Exception {
        ProCommunityLogic.Invite i = ProCommunityLogic.parse(new JSONObject("{\"already_member\":true,\"chat_id\":-1001234567890}"));
        assertEquals(ProCommunityLogic.Invite.ALREADY_MEMBER, i.kind);
        assertEquals(1234567890L, i.channelId);
        assertNull(i.link);
    }

    @Test
    public void alreadyMemberAsErrorKeepsLink() throws Exception {
        ProCommunityLogic.Invite i = ProCommunityLogic.alreadyMember(new JSONObject("{\"code\":\"already_member\",\"chat_link\":\"https://t.me/c/1234567890\"}"));
        assertEquals(ProCommunityLogic.Invite.ALREADY_MEMBER, i.kind);
        assertEquals("https://t.me/c/1234567890", i.link);
        assertEquals(0L, i.channelId);
        assertEquals(ProCommunityLogic.Invite.ALREADY_MEMBER, ProCommunityLogic.alreadyMember(null).kind);
    }

    @Test
    public void nonTelegramOrMissingLinkIsBad() throws Exception {
        assertEquals(ProCommunityLogic.Invite.BAD_ANSWER, ProCommunityLogic.parse(new JSONObject("{\"invite_link\":\"https://luckgram.cc/+x\"}")).kind);
        assertEquals(ProCommunityLogic.Invite.BAD_ANSWER, ProCommunityLogic.parse(new JSONObject("{}")).kind);
        assertEquals(ProCommunityLogic.Invite.BAD_ANSWER, ProCommunityLogic.parse(null).kind);
    }

    @Test
    public void links() {
        assertTrue(ProCommunityLogic.isOpenableLink("https://t.me/+x"));
        assertTrue(ProCommunityLogic.isOpenableLink("tg://join?invite=x"));
        assertFalse(ProCommunityLogic.isOpenableLink("https://t.me/"));
        assertFalse(ProCommunityLogic.isOpenableLink("http://t.me/+x"));
        assertFalse(ProCommunityLogic.isOpenableLink(null));
    }

    @Test
    public void channelIds() {
        assertEquals(1234567890L, ProCommunityLogic.channelId(-1001234567890L));
        assertEquals(42L, ProCommunityLogic.channelId(42L));
        assertEquals(0L, ProCommunityLogic.channelId(-42L));
        assertEquals(0L, ProCommunityLogic.channelId(0));
    }

    @Test
    public void rowNeedsFeatureAndPerk() {
        assertTrue(ProCommunityLogic.showRow(true, true));
        assertFalse(ProCommunityLogic.showRow(false, true));
        assertFalse(ProCommunityLogic.showRow(true, false));
    }
}
