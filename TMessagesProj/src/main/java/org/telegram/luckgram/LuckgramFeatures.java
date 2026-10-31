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
 * Read a switch anywhere with {@code LuckgramSettings.isEnabled("quick_replies")}.
 */
public final class LuckgramFeatures {

    private LuckgramFeatures() {
    }

    public static final String DEBUG_DEMO = "debug_demo";

    private static final ArrayList<LuckgramFeature> ALL = new ArrayList<>();

    private static void add(LuckgramFeature feature) {
        ALL.add(feature);
    }

    static {
        // ---- 账号与隐私 / Accounts & Privacy

        // ---- 消息与语言 / Messages & Language

        // ---- 媒体、通话与存储 / Media, Calls & Storage

        // ---- 资料与整理 / Profiles & Organization
        add(toggle(org.telegram.luckgram.keywordfolders.KeywordFolderController.SETTING_KEY, PROFILE_ORGANIZE, true,
            "关键词文件夹", "Keyword folders",
            "按关键词自动归类聊天（只在本机）", "Group chats by keyword (this device only)"));

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
