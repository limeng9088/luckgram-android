package org.telegram.luckgram.voice;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class DictationAudioTest {

    @Test
    public void wavHeaderDescribes16kMonoPcm() {
        byte[] pcm = new byte[3200];
        Arrays.fill(pcm, (byte) 7);
        byte[] wav = DictationAudio.toWav(pcm, 16000);
        ByteBuffer b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(44 + 3200, wav.length);
        assertEquals("RIFF", new String(wav, 0, 4, StandardCharsets.US_ASCII));
        assertEquals(36 + 3200, b.getInt(4));
        assertEquals("WAVE", new String(wav, 8, 4, StandardCharsets.US_ASCII));
        assertEquals("fmt ", new String(wav, 12, 4, StandardCharsets.US_ASCII));
        assertEquals(16, b.getInt(16));
        assertEquals(1, b.getShort(20)); // PCM
        assertEquals(1, b.getShort(22)); // mono
        assertEquals(16000, b.getInt(24));
        assertEquals(32000, b.getInt(28));
        assertEquals(2, b.getShort(32));
        assertEquals(16, b.getShort(34));
        assertEquals("data", new String(wav, 36, 4, StandardCharsets.US_ASCII));
        assertEquals(3200, b.getInt(40));
        assertArrayEquals(pcm, Arrays.copyOfRange(wav, 44, wav.length));
    }

    @Test
    public void levelIsPeakOfSignedSamples() {
        ByteBuffer b = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) 1000).putShort((short) -16384).putShort((short) 200);
        assertEquals(0.5f, DictationAudio.level(b.array(), 6), 1e-4);
        assertEquals(0f, DictationAudio.level(new byte[4], 4), 0);
        ByteBuffer max = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(Short.MIN_VALUE);
        assertEquals(1f, DictationAudio.level(max.array(), 2), 0);
    }

    @Test
    public void levelIgnoresTrailingOddByte() {
        ByteBuffer b = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) 3277).put((byte) 0x7f);
        assertEquals(0.1f, DictationAudio.level(b.array(), 3), 1e-3);
    }
}
