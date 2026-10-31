package org.telegram.luckgram.callrec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;

public class CallRecMixerTest {

    private static byte[] pcm(short... samples) {
        byte[] b = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            b[i * 2] = (byte) (samples[i] & 0xff);
            b[i * 2 + 1] = (byte) ((samples[i] >> 8) & 0xff);
        }
        return b;
    }

    private static byte[] constant(int count, short value) {
        short[] s = new short[count];
        java.util.Arrays.fill(s, value);
        return pcm(s);
    }

    private static ArrayList<short[]> drain(CallRecMixer m) {
        ArrayList<short[]> frames = new ArrayList<>();
        m.drain(f -> frames.add(f.clone()));
        return frames;
    }

    @Test
    public void mixesBothSidesFrameByFrame() {
        CallRecMixer m = new CallRecMixer();
        m.pushNear(constant(CallRecMixer.FRAME, (short) 100), CallRecMixer.FRAME * 2, 48000, 1, false);
        assertEquals("waits for the far end", 0, drain(m).size());
        m.pushFar(constant(CallRecMixer.FRAME, (short) 23), CallRecMixer.FRAME * 2, 48000, 1);
        ArrayList<short[]> frames = drain(m);
        assertEquals(1, frames.size());
        assertEquals(123, frames.get(0)[0]);
        assertEquals(123, frames.get(0)[CallRecMixer.FRAME - 1]);
    }

    @Test
    public void clampsInsteadOfWrapping() {
        CallRecMixer m = new CallRecMixer();
        m.pushNear(constant(CallRecMixer.FRAME, (short) 30000), CallRecMixer.FRAME * 2, 48000, 1, false);
        m.pushFar(constant(CallRecMixer.FRAME, (short) 30000), CallRecMixer.FRAME * 2, 48000, 1);
        assertEquals(Short.MAX_VALUE, drain(m).get(0)[0]);
        m.pushNear(constant(CallRecMixer.FRAME, (short) -30000), CallRecMixer.FRAME * 2, 48000, 1, false);
        m.pushFar(constant(CallRecMixer.FRAME, (short) -30000), CallRecMixer.FRAME * 2, 48000, 1);
        assertEquals(Short.MIN_VALUE, drain(m).get(0)[0]);
    }

    @Test
    public void mutedMicrophoneIsSilence() {
        CallRecMixer m = new CallRecMixer();
        m.pushNear(constant(CallRecMixer.FRAME, (short) 5000), CallRecMixer.FRAME * 2, 48000, 1, true);
        m.pushFar(constant(CallRecMixer.FRAME, (short) 7), CallRecMixer.FRAME * 2, 48000, 1);
        assertEquals(7, drain(m).get(0)[0]);
    }

    @Test
    public void silentSideDoesNotHoldBackTheOtherForever() {
        // The other side stops delivering (e.g. network loss): near end keeps going alone after 300 ms.
        CallRecMixer m = new CallRecMixer();
        int lag = CallRecMixer.MAX_LAG_SAMPLES;
        m.pushNear(constant(lag - CallRecMixer.FRAME, (short) 1), (lag - CallRecMixer.FRAME) * 2, 48000, 1, false);
        assertEquals(0, drain(m).size());
        m.pushNear(constant(CallRecMixer.FRAME, (short) 1), CallRecMixer.FRAME * 2, 48000, 1, false);
        ArrayList<short[]> frames = drain(m);
        // Exactly one frame leaves as soon as the backlog reaches 300 ms; the rest stays buffered
        // so the far end can still catch up.
        assertEquals(1, frames.size());
        assertEquals(1, frames.get(0)[0]);
        // Each further 20 ms of near-end audio releases one more frame.
        m.pushNear(constant(CallRecMixer.FRAME, (short) 1), CallRecMixer.FRAME * 2, 48000, 1, false);
        assertEquals(1, drain(m).size());
    }

    @Test
    public void flushKeepsEverythingRecordedAsFramesPlusTail() {
        // Hang-up mid-frame: nothing already captured may be lost.
        CallRecMixer m = new CallRecMixer();
        int total = CallRecMixer.FRAME * 2 + 100;
        m.pushNear(constant(total, (short) 2), total * 2, 48000, 1, false);
        ArrayList<short[]> frames = new ArrayList<>();
        short[] tail = new short[CallRecMixer.FRAME];
        int tailCount = m.flush(f -> frames.add(f.clone()), tail);
        assertEquals(2, frames.size());
        assertEquals(100, tailCount);
        assertEquals(2, tail[99]);
        assertEquals(0, m.flush(f -> frames.add(f), tail));
    }

    @Test
    public void downmixesStereo() {
        CallRecMixer m = new CallRecMixer();
        short[] stereo = new short[CallRecMixer.FRAME * 2];
        for (int i = 0; i < CallRecMixer.FRAME; i++) {
            stereo[i * 2] = 100;
            stereo[i * 2 + 1] = 300;
        }
        m.pushFar(pcm(stereo), stereo.length * 2, 48000, 2);
        m.pushNear(constant(CallRecMixer.FRAME, (short) 0), CallRecMixer.FRAME * 2, 48000, 1, false);
        assertEquals(200, drain(m).get(0)[0]);
    }

    @Test
    public void resamplesToFortyEightKilohertz() {
        short[] in = new short[160]; // 10 ms at 16 kHz
        java.util.Arrays.fill(in, (short) 42);
        short[] out = CallRecMixer.resample(in, in.length, 16000);
        assertEquals(480, out.length);
        assertEquals(42, out[0]);
        assertEquals(42, out[479]);
        // 16 kHz blocks still produce whole 20 ms frames once enough arrives.
        CallRecMixer m = new CallRecMixer();
        for (int i = 0; i < 2; i++) {
            m.pushNear(pcm(in), in.length * 2, 16000, 1, false);
            m.pushFar(pcm(in), in.length * 2, 16000, 1);
        }
        ArrayList<short[]> frames = drain(m);
        assertEquals(1, frames.size());
        assertEquals(84, frames.get(0)[500]);
    }

    @Test
    public void fifoGrowsAndWrapsAround() {
        CallRecMixer.ShortFifo fifo = new CallRecMixer.ShortFifo(16);
        short[] out = new short[10];
        for (int round = 0; round < 50; round++) {
            short[] in = new short[7];
            for (int i = 0; i < 7; i++) {
                in[i] = (short) (round * 7 + i);
            }
            fifo.write(in, 7);
            if (round % 2 == 1) {
                int n = fifo.read(out, 10);
                assertEquals(10, n);
            }
        }
        assertEquals(50 * 7 - 25 * 10, fifo.size());
        short[] rest = new short[fifo.size()];
        fifo.read(rest, rest.length);
        assertEquals(250, rest[0]);
        assertArrayEquals(new short[]{349}, new short[]{rest[rest.length - 1]});
    }
}
