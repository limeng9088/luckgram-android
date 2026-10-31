package org.telegram.luckgram;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits an audio file that is larger than the server's single-request limit
 * ({@code ai.transcribe_max_mb}) into segments that are each a valid file of the same format,
 * so they can be transcribed one after another (docs/api-server.md §3.1 item 6).
 *
 * <ul>
 *   <li>OGG (Opus / Vorbis, Telegram voice notes): cut at page boundaries; every segment starts with
 *       the stream's header pages (the leading pages with granule position 0).</li>
 *   <li>WAV: cut the PCM data at frame boundaries and write a fresh RIFF header per segment.</li>
 *   <li>MP3 / ADTS AAC: cut at a frame sync word (both formats are self-synchronising).</li>
 *   <li>Other containers (MP4/M4A, WebM, FLAC) cannot be cut without remuxing: {@link #split} returns null.</li>
 * </ul>
 * Pure Java (no Android APIs) so it can be unit-tested on a plain JVM.
 */
public final class LuckgramAudioSplitter {

    private LuckgramAudioSplitter() {
    }

    /** @return the segments in order (one element if {@code audio} already fits), or null if it can't be split. */
    public static List<byte[]> split(byte[] audio, String mimeType, int maxBytes) {
        ArrayList<byte[]> out = new ArrayList<>();
        if (audio.length <= maxBytes) {
            out.add(audio);
            return out;
        }
        String mime = mimeType == null ? "" : mimeType.toLowerCase();
        int sep = mime.indexOf(';');
        if (sep >= 0) mime = mime.substring(0, sep).trim();
        switch (mime) {
            case "audio/ogg":
            case "audio/opus":
                return splitOgg(audio, maxBytes);
            case "audio/wav":
            case "audio/x-wav":
            case "audio/wave":
                return splitWav(audio, maxBytes);
            case "audio/mpeg":
            case "audio/mp3":
                return splitAtSync(audio, maxBytes, 0xE0);
            case "audio/aac":
                return splitAtSync(audio, maxBytes, 0xF0);
            default:
                return null;
        }
    }

    // ---------------------------------------------------------------- OGG

    private static List<byte[]> splitOgg(byte[] a, int maxBytes) {
        ArrayList<int[]> pages = new ArrayList<>(); // {offset, length}
        int pos = 0;
        while (pos < a.length) {
            if (pos + 27 > a.length || a[pos] != 'O' || a[pos + 1] != 'g' || a[pos + 2] != 'g' || a[pos + 3] != 'S') {
                return null;
            }
            int segments = a[pos + 26] & 0xFF;
            if (pos + 27 + segments > a.length) return null;
            int len = 27 + segments;
            for (int i = 0; i < segments; i++) {
                len += a[pos + 27 + i] & 0xFF;
            }
            if (pos + len > a.length) return null;
            pages.add(new int[]{pos, len});
            pos += len;
        }
        int headerPages = 0;
        int headerBytes = 0;
        while (headerPages < pages.size() && granule(a, pages.get(headerPages)[0]) == 0) {
            headerBytes += pages.get(headerPages)[1];
            headerPages++;
        }
        if (headerPages == 0 || headerPages == pages.size()) return null;

        ArrayList<byte[]> out = new ArrayList<>();
        ByteArrayOutputStream cur = null;
        for (int i = headerPages; i < pages.size(); i++) {
            int[] p = pages.get(i);
            if (headerBytes + p[1] > maxBytes) return null;
            if (cur != null && cur.size() + p[1] > maxBytes) {
                out.add(cur.toByteArray());
                cur = null;
            }
            if (cur == null) {
                cur = new ByteArrayOutputStream(maxBytes);
                cur.write(a, 0, headerBytes);
            }
            cur.write(a, p[0], p[1]);
        }
        if (cur != null) out.add(cur.toByteArray());
        return out;
    }

    private static long granule(byte[] a, int pageOffset) {
        long g = 0;
        for (int i = 7; i >= 0; i--) {
            g = (g << 8) | (a[pageOffset + 6 + i] & 0xFF);
        }
        return g;
    }

    // ---------------------------------------------------------------- WAV

    private static List<byte[]> splitWav(byte[] a, int maxBytes) {
        if (a.length < 12 || !tag(a, 0, "RIFF") || !tag(a, 8, "WAVE")) return null;
        int pos = 12;
        int fmtOff = -1, fmtLen = 0, dataOff = -1, dataLen = 0;
        while (pos + 8 <= a.length) {
            int len = le32(a, pos + 4);
            if (len < 0) return null;
            if (tag(a, pos, "fmt ")) {
                fmtOff = pos;
                fmtLen = len;
            } else if (tag(a, pos, "data")) {
                dataOff = pos + 8;
                dataLen = Math.min(len, a.length - dataOff);
                break;
            }
            pos += 8 + len + (len & 1);
        }
        if (fmtOff < 0 || dataOff < 0 || fmtLen < 16) return null;
        int blockAlign = (a[fmtOff + 8 + 12] & 0xFF) | ((a[fmtOff + 8 + 13] & 0xFF) << 8);
        if (blockAlign <= 0) return null;
        int headerLen = 12 + 8 + fmtLen + 8;
        int perSegment = (maxBytes - headerLen) / blockAlign * blockAlign;
        if (perSegment <= 0) return null;

        ArrayList<byte[]> out = new ArrayList<>();
        for (int start = 0; start < dataLen; start += perSegment) {
            int n = Math.min(perSegment, dataLen - start);
            byte[] seg = new byte[headerLen + n];
            System.arraycopy("RIFF".getBytes(), 0, seg, 0, 4);
            putLe32(seg, 4, seg.length - 8);
            System.arraycopy("WAVE".getBytes(), 0, seg, 8, 4);
            System.arraycopy(a, fmtOff, seg, 12, 8 + fmtLen);
            System.arraycopy("data".getBytes(), 0, seg, 12 + 8 + fmtLen, 4);
            putLe32(seg, 12 + 8 + fmtLen + 4, n);
            System.arraycopy(a, dataOff + start, seg, headerLen, n);
            out.add(seg);
        }
        return out;
    }

    private static boolean tag(byte[] a, int off, String t) {
        for (int i = 0; i < 4; i++) {
            if (a[off + i] != t.charAt(i)) return false;
        }
        return true;
    }

    private static int le32(byte[] a, int off) {
        return (a[off] & 0xFF) | ((a[off + 1] & 0xFF) << 8) | ((a[off + 2] & 0xFF) << 16) | ((a[off + 3] & 0xFF) << 24);
    }

    private static void putLe32(byte[] a, int off, int v) {
        a[off] = (byte) v;
        a[off + 1] = (byte) (v >> 8);
        a[off + 2] = (byte) (v >> 16);
        a[off + 3] = (byte) (v >> 24);
    }

    // ---------------------------------------------------------------- MP3 / ADTS

    /** Cuts at the last frame sync (0xFF, next byte & mask == mask) at or before each limit. */
    private static List<byte[]> splitAtSync(byte[] a, int maxBytes, int mask) {
        ArrayList<byte[]> out = new ArrayList<>();
        int start = 0;
        while (start < a.length) {
            int end = Math.min(a.length, start + maxBytes);
            if (end < a.length) {
                int cut = -1;
                int floor = Math.max(start + 1, end - 64 * 1024);
                for (int i = end - 1; i >= floor; i--) {
                    if ((a[i] & 0xFF) == 0xFF && i + 1 < a.length && (a[i + 1] & mask) == mask) {
                        cut = i;
                        break;
                    }
                }
                if (cut > start) end = cut;
            }
            byte[] seg = new byte[end - start];
            System.arraycopy(a, start, seg, 0, seg.length);
            out.add(seg);
            start = end;
        }
        return out;
    }
}
