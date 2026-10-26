package org.telegram.luckgram;

import static org.telegram.luckgram.LuckgramFeature.Group.*;
import static org.telegram.luckgram.LuckgramFeature.toggle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Luckgram feature registry. Adding a feature = adding one {@code add(...)} line here; the
 * "Settings → Luckgram Features" page is generated from this list (rows keep the order of the
 * lines inside each group). Keep keys and defaults in sync with iOS
 * (submodules/LuckgramUI/Sources/LuckgramFeatures.swift).
 *
 * <pre>
 * add(toggle("quick_replies", MESSAGES_LANGUAGE, true,
 *         "快捷回复", "Quick replies",
 *         "输入 / 选择常用回复", "Type / to pick a saved reply")
 *     .subpage(QuickRepliesSettingsActivity::new));
 * </pre>
 *
 * A feature that must ask before it is switched on (e.g. a legal notice, #58):
 * <pre>
 * add(toggle("call_recording", MEDIA_CALLS_STORAGE, false, …)
 *     .subpage(CallRecSettingsActivity::new)
 *     .confirmEnable((fragment, done) -&gt; CallRecSettingsActivity.requestEnable(fragment, done)));
 * </pre>
 *
 * Read a switch anywhere with {@code LuckgramSettings.isEnabled("quick_replies")}.
 */
public final class LuckgramFeatures {

    private LuckgramFeatures() {
    }

    public static final String DEBUG_DEMO = "debug_demo";
    public static final String ROUND_VIDEO_FROM_GALLERY = "round_video_from_gallery";

    private static final ArrayList<LuckgramFeature> ALL = new ArrayList<>();

    private static void add(LuckgramFeature feature) {
        ALL.add(feature);
    }

    static {
        // ---- 账号与隐私 / Accounts & Privacy

        // ---- 消息与语言 / Messages & Language
        add(toggle("translate_out", MESSAGES_LANGUAGE, true,
            "发送前翻译", "Translate before sending",
            "在输入框里先翻译成对方的语言再发送", "Translate your message into the other person's language before sending"));

        // ---- 媒体、通话与存储 / Media, Calls & Storage
        add(toggle(ROUND_VIDEO_FROM_GALLERY, MEDIA_CALLS_STORAGE, true,
            "相册视频发成圆形视频", "Gallery video as round video",
            "附件菜单里选一个视频，可作为圆形视频发送", "Send a video from the gallery as a round video message"));

        // ---- 资料与整理 / Profiles & Organization

        // ---- AI

        // ---- 调试 / Debug (debug builds or unlocked debug tools only)
        add(toggle(DEBUG_DEMO, DEBUG, false,
            "测试开关", "Test switch",
            "仅用于验证开关框架，不影响任何功能", "Only for testing the switch framework; changes nothing")
            .debugOnly());
    }

    public static List<LuckgramFeature> all() {
        return Collections.unmodifiableList(ALL);
    }

    public static LuckgramFeature find(String key) {
        for (LuckgramFeature f : ALL) {
            if (f.key.equals(key)) {
                return f;
            }
        }
        return null;
    }
}
