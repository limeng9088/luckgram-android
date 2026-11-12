package org.telegram.luckgram.voicebar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LuckgramVoiceBarLogicTest {

    @Test
    public void releasingInTheBottomAreaSends() {
        assertEquals(LuckgramVoiceBarLogic.ZONE_SEND, LuckgramVoiceBarLogic.zoneFor(10, 900, 400, 800));
        assertEquals(LuckgramVoiceBarLogic.ZONE_SEND, LuckgramVoiceBarLogic.zoneFor(390, 800, 400, 800));
    }

    @Test
    public void aboveTheLineLeftCancelsRightConverts() {
        assertEquals(LuckgramVoiceBarLogic.ZONE_CANCEL, LuckgramVoiceBarLogic.zoneFor(199, 799, 400, 800));
        assertEquals(LuckgramVoiceBarLogic.ZONE_TEXT, LuckgramVoiceBarLogic.zoneFor(200, 799, 400, 800));
        assertEquals(LuckgramVoiceBarLogic.ZONE_TEXT, LuckgramVoiceBarLogic.zoneFor(399, 0, 400, 800));
    }

    @Test
    public void beforeLayoutEverythingSends() {
        assertEquals(LuckgramVoiceBarLogic.ZONE_SEND, LuckgramVoiceBarLogic.zoneFor(0, 0, 0, 0));
    }

    @Test
    public void durationIsMinutesAndSeconds() {
        assertEquals("0:00", LuckgramVoiceBarLogic.formatDuration(0));
        assertEquals("0:09", LuckgramVoiceBarLogic.formatDuration(9999));
        assertEquals("1:05", LuckgramVoiceBarLogic.formatDuration(65_000));
        assertEquals("0:00", LuckgramVoiceBarLogic.formatDuration(-5));
    }

    @Test
    public void onlyNearZeroInputIsSilence() {
        assertTrue(LuckgramVoiceBarLogic.isSilent(0));
        assertTrue(LuckgramVoiceBarLogic.isSilent(59.9));
        assertFalse(LuckgramVoiceBarLogic.isSilent(60));
        assertFalse(LuckgramVoiceBarLogic.isSilent(1500));
    }

    @Test
    public void stockSilencePhrasesAreNotTranscripts() {
        assertTrue(LuckgramVoiceBarLogic.isSilenceHallucination("Thank you so much for watching."));
        assertTrue(LuckgramVoiceBarLogic.isSilenceHallucination(" ご視聴ありがとうございました "));
        assertTrue(LuckgramVoiceBarLogic.isSilenceHallucination("私のビデオを見てくれてありがとう。"));
        assertTrue(LuckgramVoiceBarLogic.isSilenceHallucination("メッセージなし。ご視聴ありがとうございました。"));
        assertFalse(LuckgramVoiceBarLogic.isSilenceHallucination("Thank you so much for watching my long video about cooking, see you at 8"));
        assertFalse(LuckgramVoiceBarLogic.isSilenceHallucination("晚上八点见"));
        assertFalse(LuckgramVoiceBarLogic.isSilenceHallucination(null));
    }

    @Test
    public void loopsAreNotTranscripts() {
        assertTrue(LuckgramVoiceBarLogic.isSilenceHallucination("BREATHING IN DEEPLY BREATHING OUT DEEPLY BREATHING IN DEEPLY BREATHING OUT DEEPLY"));
        assertTrue(LuckgramVoiceBarLogic.isSilenceHallucination("下方影片の説明については、下方のリンク先をクリックすることで、下方のリンク先をクリックすることができます。"));
        assertFalse(LuckgramVoiceBarLogic.isSilenceHallucination("哈哈哈哈哈哈哈哈哈哈哈哈"));
        assertFalse(LuckgramVoiceBarLogic.isSilenceHallucination("好的好的好的"));
        assertFalse(LuckgramVoiceBarLogic.isSilenceHallucination("明天上午十点开会，记得带上周的报告，下午三点再和客户开会"));
        assertFalse(LuckgramVoiceBarLogic.isSilenceHallucination("I will be there at eight, and I will bring the documents you asked for"));
    }

    @Test
    public void aClickIsNotAVoice() {
        double loud = LuckgramVoiceBarLogic.loudSeconds(0, 1500, 2.0);
        assertEquals(0.25, loud, 0.0001);
        assertTrue(LuckgramVoiceBarLogic.isSilent(1500, loud));
        loud = LuckgramVoiceBarLogic.loudSeconds(loud, 20, 0.05);
        assertEquals(0.25, loud, 0.0001);
        for (int i = 0; i < 4; i++) {
            loud = LuckgramVoiceBarLogic.loudSeconds(loud, 400, 0.05);
        }
        assertFalse(LuckgramVoiceBarLogic.isSilent(1500, loud));
        assertTrue(LuckgramVoiceBarLogic.isSilent(20, 5.0));
    }

    @Test
    public void repeatedAndFillerSentencesAreNotTranscripts() {
        assertTrue(LuckgramVoiceBarLogic.isSilenceHallucination("That's it. Thank you. God bless. God bless. God bless."));
        assertTrue(LuckgramVoiceBarLogic.isSilenceHallucination("Thank you. Bye.", true));
        assertTrue(LuckgramVoiceBarLogic.isSilenceHallucination("谢谢。", true));
        // said out loud, these are real
        assertFalse(LuckgramVoiceBarLogic.isSilenceHallucination("Thank you. Bye."));
        assertFalse(LuckgramVoiceBarLogic.isSilenceHallucination("谢谢"));
        assertFalse(LuckgramVoiceBarLogic.isSilenceHallucination("Thank you, see you at eight.", true));
        assertFalse(LuckgramVoiceBarLogic.isSilenceHallucination("好的。好的。", true));
        assertTrue(LuckgramVoiceBarLogic.isWeak(100));
        assertFalse(LuckgramVoiceBarLogic.isWeak(180));
    }
}
