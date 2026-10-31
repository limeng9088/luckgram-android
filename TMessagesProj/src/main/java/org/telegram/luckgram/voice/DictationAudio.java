package org.telegram.luckgram.voice;

/** Pure PCM helpers for {@link LuckgramDictationSheet} (no Android dependencies; unit-tested). */
final class DictationAudio {

    private DictationAudio() {
    }


    /** Peak level of 16-bit little-endian PCM, 0..1. */
    static float level(byte[] pcm, int length) {
        int peak = 0;
        for (int i = 0; i + 1 < length; i += 2) {
            int s = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
            peak = Math.max(peak, Math.abs(s));
        }
        return Math.min(1f, peak / 32768f);
    }

    /** Wraps 16-bit mono PCM in a WAV header. */
    static byte[] toWav(byte[] pcm, int sampleRate) {
        int dataLen = pcm.length;
        byte[] out = new byte[44 + dataLen];
        int byteRate = sampleRate * 2;
        writeAscii(out, 0, "RIFF");
        writeLe32(out, 4, 36 + dataLen);
        writeAscii(out, 8, "WAVE");
        writeAscii(out, 12, "fmt ");
        writeLe32(out, 16, 16);
        writeLe16(out, 20, 1); // PCM
        writeLe16(out, 22, 1); // mono
        writeLe32(out, 24, sampleRate);
        writeLe32(out, 28, byteRate);
        writeLe16(out, 32, 2); // block align
        writeLe16(out, 34, 16); // bits per sample
        writeAscii(out, 36, "data");
        writeLe32(out, 40, dataLen);
        System.arraycopy(pcm, 0, out, 44, dataLen);
        return out;
    }

    private static void writeAscii(byte[] b, int off, String s) {
        for (int i = 0; i < s.length(); i++) {
            b[off + i] = (byte) s.charAt(i);
        }
    }

    private static void writeLe32(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }

    private static void writeLe16(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
    }
}
