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
        assertTrue(LuckgramVoiceBarLogic.isSilent(29.9));
        assertFalse(LuckgramVoiceBarLogic.isSilent(30));
        assertFalse(LuckgramVoiceBarLogic.isSilent(1500));
    }

    @Test
    public void stockSilencePhrasesAreNotTranscripts() {
        assertTrue(LuckgramVoiceBarLogic.isSilenceHallucination("Thank you so much for watching."));
        assertTrue(LuckgramVoiceBarLogic.isSilenceHallucination(" ご視聴ありがとうございました "));
        assertTrue(LuckgramVoiceBarLogic.isSilenceHallucination("私のビデオを見てくれてありがとう。"));
        assertFalse(LuckgramVoiceBarLogic.isSilenceHallucination("Thank you so much for watching, see you at 8"));
        assertFalse(LuckgramVoiceBarLogic.isSilenceHallucination("晚上八点见"));
        assertFalse(LuckgramVoiceBarLogic.isSilenceHallucination(null));
    }
}
