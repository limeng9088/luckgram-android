/*
 * Luckgram: Ogg/Opus file writer for call recordings (mono, 48 kHz). Natives live in jni/audio.c.
 */
package org.telegram.luckgram.callrec;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@Keep
final class CallRecOpusEncoder {

    static final int SAMPLE_RATE = 48_000;
    /** Samples per Opus frame (20 ms at 48 kHz); must match frame_size in audio.c. */
    static final int FRAME_SAMPLES = 960;
    /** ~14 MB per hour, comfortably inside the 25 MB transcription limit for normal calls. */
    static final int BITRATE_BPS = 32_000;

    private long handle;
    private final ByteBuffer frameBuffer = ByteBuffer.allocateDirect(FRAME_SAMPLES * 2).order(ByteOrder.nativeOrder());
    private long samplesWritten;

    private CallRecOpusEncoder(long handle) {
        this.handle = handle;
    }

    static @Nullable CallRecOpusEncoder create(@NonNull File file) {
        long h = nCreate(file.getAbsolutePath(), SAMPLE_RATE, BITRATE_BPS);
        return h == 0 ? null : new CallRecOpusEncoder(h);
    }

    /** Writes exactly one frame of {@link #FRAME_SAMPLES} samples. */
    boolean writeFrame(@NonNull short[] samples) {
        if (handle == 0) {
            return false;
        }
        frameBuffer.clear();
        frameBuffer.asShortBuffer().put(samples, 0, FRAME_SAMPLES);
        samplesWritten += FRAME_SAMPLES;
        return nWriteFrame(handle, frameBuffer, FRAME_SAMPLES * 2) != 0;
    }

    long getDurationMs() {
        return samplesWritten * 1000L / SAMPLE_RATE;
    }

    /**
     * Writes the last, shorter-than-a-frame chunk (which marks end-of-stream in the Ogg file),
     * then flushes and closes the file. {@code tailCount} may be 0. Safe to call twice.
     */
    boolean finish(@Nullable short[] tail, int tailCount) {
        long h = handle;
        if (h == 0) {
            return false;
        }
        int count = Math.max(0, Math.min(tailCount, FRAME_SAMPLES - 1));
        if (count == 0) {
            // 10 ms of silence so the stream always ends with an EOS packet.
            count = FRAME_SAMPLES / 2;
            tail = new short[count];
        }
        frameBuffer.clear();
        frameBuffer.asShortBuffer().put(tail, 0, count);
        samplesWritten += count;
        nWriteFrame(h, frameBuffer, count * 2);
        handle = 0;
        return nFinish(h);
    }

    /** Closes without the final flush (used when the recording is discarded). */
    void destroy() {
        long h = handle;
        handle = 0;
        if (h != 0) {
            nDestroy(h);
        }
    }

    private static native long nCreate(String path, int sampleRate, int bitrateBps);

    private static native int nWriteFrame(long handle, ByteBuffer frame, int length);

    private static native boolean nFinish(long handle);

    private static native void nDestroy(long handle);
}
