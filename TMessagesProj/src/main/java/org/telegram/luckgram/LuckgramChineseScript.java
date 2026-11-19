package org.telegram.luckgram;

import java.util.Locale;

/**
 * luckgram-android#139: the {@code ?script=} sent with /v1/ai/transcribe, so a Chinese transcript
 * comes back in the script of the current UI (the server converts; without it the registration
 * locale wins). Same rule as {@link LuckgramStrings#isChinese()}: the Telegram UI language first,
 * then the system language. Same as iOS {@code LuckgramChineseScript}.
 *
 * <p>Pure (no Android) so it is unit tested on plain javac.
 */
public final class LuckgramChineseScript {

    private LuckgramChineseScript() {
    }

    /**
     * "Hans" / "Hant" for a Chinese language code ("zh-hans-beta", "zh-hant-beta", "zh-Hant-TW",
     * "zh_TW", "classic-zh-cn", "taiwan", …), null for any other language.
     */
    public static String of(String code) {
        if (code == null || code.isEmpty()) return null;
        code = code.toLowerCase(Locale.ROOT).replace('_', '-');
        boolean chinese = code.startsWith("zh");
        boolean traditional = false;
        for (String t : code.split("-")) {
            if (t.equals("zh") || t.equals("taiwan")) chinese = true;
            if (t.startsWith("hant") || t.equals("tw") || t.equals("hk") || t.equals("mo") || t.equals("taiwan")) traditional = true;
        }
        if (!chinese) return null;
        return traditional ? "Hant" : "Hans";
    }

    /** The first of {@code codes} (UI language, its base pack, the system language) that is Chinese. */
    public static String pick(String... codes) {
        for (String c : codes) {
            String s = of(c);
            if (s != null) return s;
        }
        return null;
    }
}
