/*
 * Luckgram: mixes the two sides of a call into one mono 48 kHz stream.
 *
 * Near end = the microphone block WebRtcAudioRecord hands to WebRTC, far end = the decoded block
 * WebRtcAudioTrack is about to play. Both arrive every 10 ms on their own audio threads; this class
 * is only touched from the recorder's single worker thread.
 *
 * Pure Java on purpose (no Android types) so it can be unit tested.
 */
package org.telegram.luckgram.callrec;

final class CallRecMixer {

    interface FrameSink {
        void onFrame(short[] frame);
    }

    static final int RATE = 48_000;
    /** Samples per Opus frame (20 ms at 48 kHz); must match frame_size in jni/audio.c. */
    static final int FRAME = 960;
    /**
     * When one side has stopped delivering (e.g. playout paused, mic restarting after a route
     * change), the other side is not held back for longer than this; the missing side is mixed
     * in as silence.
     */
    static final int MAX_LAG_SAMPLES = RATE * 3 / 10;

    private final ShortFifo near = new ShortFifo(RATE);
    private final ShortFifo far = new ShortFifo(RATE);
    private final short[] nearFrame = new short[FRAME];
    private final short[] farFrame = new short[FRAME];
    private final short[] mixFrame = new short[FRAME];

    /** PCM16 little-endian, any rate / channel count. */
    void pushNear(byte[] pcm, int length, int sampleRate, int channels, boolean muted) {
        push(near, pcm, length, sampleRate, channels, muted);
    }

    void pushFar(byte[] pcm, int length, int sampleRate, int channels) {
        push(far, pcm, length, sampleRate, channels, false);
    }

    /** Emits every complete frame that can be mixed now. */
    void drain(FrameSink sink) {
        while (true) {
            final int n = near.size();
            final int f = far.size();
            final boolean haveNear = n >= FRAME;
            final boolean haveFar = f >= FRAME;
            if (haveNear && haveFar) {
                emit(sink, true, true, FRAME);
            } else if (haveNear && n >= MAX_LAG_SAMPLES) {
                emit(sink, true, false, FRAME);
            } else if (haveFar && f >= MAX_LAG_SAMPLES) {
                emit(sink, false, true, FRAME);
            } else {
                return;
            }
        }
    }

    /**
     * Emits everything that is left as full frames and returns the final partial frame in
     * {@code tailOut} (length FRAME). Returns the number of valid samples in it (0..FRAME-1).
     */
    int flush(FrameSink sink, short[] tailOut) {
        while (Math.max(near.size(), far.size()) >= FRAME) {
            emit(sink, near.size() > 0, far.size() > 0, FRAME);
        }
        final int count = Math.max(near.size(), far.size());
        if (count == 0) {
            return 0;
        }
        mix(near.size() > 0, far.size() > 0, count);
        System.arraycopy(mixFrame, 0, tailOut, 0, count);
        return count;
    }

    private void emit(FrameSink sink, boolean useNear, boolean useFar, int count) {
        mix(useNear, useFar, count);
        sink.onFrame(mixFrame);
    }

    private void mix(boolean useNear, boolean useFar, int count) {
        int gotNear = useNear ? near.read(nearFrame, count) : 0;
        int gotFar = useFar ? far.read(farFrame, count) : 0;
        for (int i = 0; i < count; i++) {
            int a = i < gotNear ? nearFrame[i] : 0;
            int b = i < gotFar ? farFrame[i] : 0;
            int s = a + b;
            if (s > Short.MAX_VALUE) {
                s = Short.MAX_VALUE;
            } else if (s < Short.MIN_VALUE) {
                s = Short.MIN_VALUE;
            }
            mixFrame[i] = (short) s;
        }
    }

    private static void push(ShortFifo fifo, byte[] pcm, int length, int sampleRate, int channels, boolean muted) {
        if (pcm == null || length <= 0 || sampleRate <= 0) {
            return;
        }
        if (channels <= 0) {
            channels = 1;
        }
        final int frames = length / (2 * channels);
        if (frames == 0) {
            return;
        }
        final short[] mono = new short[frames];
        if (!muted) {
            for (int i = 0; i < frames; i++) {
                int sum = 0;
                for (int c = 0; c < channels; c++) {
                    int idx = (i * channels + c) * 2;
                    sum += (short) ((pcm[idx] & 0xff) | (pcm[idx + 1] << 8));
                }
                mono[i] = (short) (sum / channels);
            }
        }
        if (sampleRate == RATE) {
            fifo.write(mono, frames);
        } else {
            final short[] resampled = resample(mono, frames, sampleRate);
            fifo.write(resampled, resampled.length);
        }
    }

    /** Linear resampling of one block to {@link #RATE}. Blocks are 10 ms, so edge effects are inaudible. */
    static short[] resample(short[] in, int count, int fromRate) {
        final int outCount = (int) ((long) count * RATE / fromRate);
        final short[] out = new short[outCount];
        if (count == 0 || outCount == 0) {
            return out;
        }
        final double step = (double) fromRate / RATE;
        for (int i = 0; i < outCount; i++) {
            double pos = i * step;
            int i0 = (int) pos;
            int i1 = Math.min(i0 + 1, count - 1);
            double t = pos - i0;
            out[i] = (short) Math.round(in[Math.min(i0, count - 1)] * (1 - t) + in[i1] * t);
        }
        return out;
    }

    /** Growable ring buffer of samples. */
    static final class ShortFifo {
        private short[] buf;
        private int head;
        private int size;

        ShortFifo(int initialCapacity) {
            buf = new short[Math.max(16, initialCapacity)];
        }

        int size() {
            return size;
        }

        void write(short[] src, int count) {
            if (size + count > buf.length) {
                int cap = buf.length;
                while (cap < size + count) {
                    cap *= 2;
                }
                short[] n = new short[cap];
                int first = Math.min(size, buf.length - head);
                System.arraycopy(buf, head, n, 0, first);
                System.arraycopy(buf, 0, n, first, size - first);
                buf = n;
                head = 0;
            }
            int tail = (head + size) % buf.length;
            int first = Math.min(count, buf.length - tail);
            System.arraycopy(src, 0, buf, tail, first);
            System.arraycopy(src, first, buf, 0, count - first);
            size += count;
        }

        int read(short[] dst, int count) {
            int n = Math.min(count, size);
            int first = Math.min(n, buf.length - head);
            System.arraycopy(buf, head, dst, 0, first);
            System.arraycopy(buf, 0, dst, first, n - first);
            head = (head + n) % buf.length;
            size -= n;
            return n;
        }
    }
}
