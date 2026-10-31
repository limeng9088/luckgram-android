package org.telegram.luckgram;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;

/** Plain-JVM tests for {@link LuckgramAudioSplitter} (spec 00 / docs/api-server.md §3.1 item 6). */
public class LuckgramAudioSplitterTest {

    private static final int LIMIT = 64 * 1024;

    @Test
    public void smallFileIsReturnedAsIs() {
        byte[] a = new byte[100];
        List<byte[]> out = LuckgramAudioSplitter.split(a, "audio/mp4", LIMIT);
        assertEquals(1, out.size());
        assertTrue(out.get(0) == a);
    }

    @Test
    public void unsplittableFormatsReturnNull() {
        byte[] big = new byte[LIMIT + 1];
        assertNull(LuckgramAudioSplitter.split(big, "audio/mp4", LIMIT));
        assertNull(LuckgramAudioSplitter.split(big, "audio/webm", LIMIT));
        assertNull(LuckgramAudioSplitter.split(big, "audio/flac", LIMIT));
    }

    // ---------------------------------------------------------------- WAV

    private static byte[] wav(int dataBytes, int blockAlign) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        ascii(b, "RIFF");
        le32(b, 36 + dataBytes);
        ascii(b, "WAVEfmt ");
        le32(b, 16);
        le16(b, 1);
        le16(b, blockAlign / 2);
        le32(b, 16000);
        le32(b, 16000 * blockAlign);
        le16(b, blockAlign);
        le16(b, 16);
        ascii(b, "data");
        le32(b, dataBytes);
        for (int i = 0; i < dataBytes; i++) b.write(i * 31 + 7);
        return b.toByteArray();
    }

    @Test
    public void wavSegmentsAreValidAndKeepAllSamples() {
        byte[] in = wav(200_000, 4);
        List<byte[]> out = LuckgramAudioSplitter.split(in, "audio/wav", LIMIT);
        assertTrue(out.size() > 1);
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (byte[] seg : out) {
            assertTrue(seg.length <= LIMIT);
            assertEquals("RIFF", new String(seg, 0, 4));
            assertEquals(seg.length - 8, readLe32(seg, 4));
            assertEquals("WAVE", new String(seg, 8, 4));
            assertEquals("fmt ", new String(seg, 12, 4));
            assertEquals("data", new String(seg, 36, 4));
            int n = readLe32(seg, 40);
            assertEquals(seg.length - 44, n);
            assertEquals(0, n % 4);
            data.write(seg, 44, n);
        }
        assertArrayEquals(Arrays.copyOfRange(in, 44, in.length), data.toByteArray());
    }

    // ---------------------------------------------------------------- OGG

    private static void oggPage(ByteArrayOutputStream b, long granule, int payload, int seq) {
        int segments = (payload + 254) / 255;
        if (payload % 255 == 0) segments++;
        ascii(b, "OggS");
        b.write(0);
        b.write(granule == 0 && seq < 2 ? 2 : 0);
        for (int i = 0; i < 8; i++) b.write((int) (granule >> (8 * i)));
        le32(b, 1234);
        le32(b, seq);
        le32(b, 0);
        b.write(segments);
        int left = payload;
        for (int i = 0; i < segments; i++) {
            int l = Math.min(255, left);
            b.write(l);
            left -= l;
        }
        for (int i = 0; i < payload; i++) b.write(seq + i);
    }

    @Test
    public void oggSegmentsStartWithHeaderPagesAndKeepAllAudioPages() {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        oggPage(b, 0, 19, 0);   // OpusHead
        oggPage(b, 0, 60, 1);   // OpusTags
        int headerLen = b.size();
        for (int i = 0; i < 100; i++) oggPage(b, 960L * (i + 1), 2000, i + 2);
        byte[] in = b.toByteArray();
        byte[] header = Arrays.copyOf(in, headerLen);

        List<byte[]> out = LuckgramAudioSplitter.split(in, "audio/ogg", LIMIT);
        assertTrue(out.size() > 1);
        ByteArrayOutputStream audio = new ByteArrayOutputStream();
        for (byte[] seg : out) {
            assertTrue(seg.length <= LIMIT);
            assertArrayEquals(header, Arrays.copyOf(seg, headerLen));
            audio.write(seg, headerLen, seg.length - headerLen);
        }
        assertArrayEquals(Arrays.copyOfRange(in, headerLen, in.length), audio.toByteArray());
    }

    @Test
    public void brokenOggReturnsNull() {
        byte[] junk = new byte[LIMIT * 2];
        assertNull(LuckgramAudioSplitter.split(junk, "audio/ogg", LIMIT));
    }

    // ---------------------------------------------------------------- MP3

    @Test
    public void mp3IsCutAtFrameSync() {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int f = 0; f < 600; f++) {
            b.write(0xFF);
            b.write(0xFB);
            for (int i = 0; i < 415; i++) b.write(0x11); // no 0xFF inside frames
        }
        byte[] in = b.toByteArray();
        List<byte[]> out = LuckgramAudioSplitter.split(in, "audio/mpeg", LIMIT);
        assertTrue(out.size() > 1);
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (byte[] seg : out) {
            assertTrue(seg.length <= LIMIT);
            assertEquals(0xFF, seg[0] & 0xFF);
            assertEquals(0xFB, seg[1] & 0xFF);
            all.write(seg, 0, seg.length);
        }
        assertArrayEquals(in, all.toByteArray());
    }

    // ---------------------------------------------------------------- helpers

    private static void ascii(ByteArrayOutputStream b, String s) {
        for (char c : s.toCharArray()) b.write(c);
    }

    private static void le16(ByteArrayOutputStream b, int v) {
        b.write(v);
        b.write(v >> 8);
    }

    private static void le32(ByteArrayOutputStream b, int v) {
        le16(b, v);
        le16(b, v >> 16);
    }

    private static int readLe32(byte[] a, int o) {
        return (a[o] & 0xFF) | ((a[o + 1] & 0xFF) << 8) | ((a[o + 2] & 0xFF) << 16) | ((a[o + 3] & 0xFF) << 24);
    }
}
