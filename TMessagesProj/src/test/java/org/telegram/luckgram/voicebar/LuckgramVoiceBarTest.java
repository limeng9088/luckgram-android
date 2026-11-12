package org.telegram.luckgram.voicebar;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LuckgramVoiceBarTest {

    @Test
    public void releasingInTheBottomAreaSends() {
        assertEquals(LuckgramVoiceBar.ZONE_SEND, LuckgramVoiceBar.zoneFor(10, 900, 400, 800));
        assertEquals(LuckgramVoiceBar.ZONE_SEND, LuckgramVoiceBar.zoneFor(390, 800, 400, 800));
    }

    @Test
    public void aboveTheLineLeftCancelsRightConverts() {
        assertEquals(LuckgramVoiceBar.ZONE_CANCEL, LuckgramVoiceBar.zoneFor(199, 799, 400, 800));
        assertEquals(LuckgramVoiceBar.ZONE_TEXT, LuckgramVoiceBar.zoneFor(200, 799, 400, 800));
        assertEquals(LuckgramVoiceBar.ZONE_TEXT, LuckgramVoiceBar.zoneFor(399, 0, 400, 800));
    }

    @Test
    public void beforeLayoutEverythingSends() {
        assertEquals(LuckgramVoiceBar.ZONE_SEND, LuckgramVoiceBar.zoneFor(0, 0, 0, 0));
    }

    @Test
    public void durationIsMinutesAndSeconds() {
        assertEquals("0:00", LuckgramVoiceBar.formatDuration(0));
        assertEquals("0:09", LuckgramVoiceBar.formatDuration(9999));
        assertEquals("1:05", LuckgramVoiceBar.formatDuration(65_000));
        assertEquals("0:00", LuckgramVoiceBar.formatDuration(-5));
    }
}
