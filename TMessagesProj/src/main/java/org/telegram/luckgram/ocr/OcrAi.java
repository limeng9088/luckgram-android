package org.telegram.luckgram.ocr;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * specs#106 需求 2: "Recognize with Luckgram AI" when on-device recognition finds nothing or fails.
 * {@code POST /v1/ai/ocr} {@code {image: base64 JPEG, lang_hint?}} → {@code {language, blocks:[{text,
 * language, box:{x,y,width,height}}]}} (normalized, origin top-left, reading order). Shown only while
 * the remote config {@value #CONFIG_KEY} is true (the server endpoint is new). Same as iOS
 * {@code LuckgramOcrAI}.
 *
 * <p>Pure Java (java.* and org.json), unit-tested in {@code OcrAiTest}.
 */
public final class OcrAi {

    public static final String PATH = "/v1/ai/ocr";
    public static final String CONFIG_KEY = "ai.ocr_enabled";
    /** Longest side of the uploaded JPEG, and its size limit. */
    public static final int MAX_SIDE = 2048;
    public static final int MAX_BYTES = 4 * 1024 * 1024;

    private OcrAi() {
    }

    /** Offered when the switch is on and the device found no text (or failed). */
    public static boolean offer(boolean configOn, boolean deviceFoundText) {
        return configOn && !deviceFoundText;
    }

    public static JSONObject body(String base64Jpeg, String langHint) {
        JSONObject j = new JSONObject();
        try {
            j.put("image", base64Jpeg);
            if (langHint != null && !langHint.isEmpty()) j.put("lang_hint", langHint);
        } catch (Exception ignore) {
        }
        return j;
    }

    /** The server's answer as an {@link OcrResult} with {@code source=ai}; boxes clamped to 0..1. */
    public static OcrResult parse(JSONObject j, int imageWidth, int imageHeight) {
        ArrayList<OcrResult.Block> blocks = new ArrayList<>();
        JSONArray arr = j == null ? null : j.optJSONArray("blocks");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject b = arr.optJSONObject(i);
                if (b == null) continue;
                String text = b.optString("text", "").replace("\r\n", "\n");
                if (text.trim().isEmpty()) continue;
                JSONObject box = b.optJSONObject("box");
                OcrResult.Box bx = null;
                if (box != null) {
                    float x = clamp(box.optDouble("x", 0)), y = clamp(box.optDouble("y", 0));
                    float w = Math.min(clamp(box.optDouble("width", 0)), 1f - x), h = Math.min(clamp(box.optDouble("height", 0)), 1f - y);
                    bx = new OcrResult.Box(x, y, w, h);
                }
                String lang = b.isNull("language") ? null : b.optString("language", null);
                ArrayList<OcrResult.Line> lines = new ArrayList<>();
                for (String l : text.split("\n")) lines.add(new OcrResult.Line(l, null, null));
                blocks.add(new OcrResult.Block(text, lang, bx, null, lines));
            }
        }
        String lang = j == null || j.isNull("language") ? null : j.optString("language", null);
        if (lang == null) lang = OcrResult.mainLanguage(blocks);
        return new OcrResult(imageWidth, imageHeight, lang, OcrResult.SOURCE_AI, blocks);
    }

    private static float clamp(double v) {
        return (float) Math.max(0d, Math.min(1d, v));
    }
}
