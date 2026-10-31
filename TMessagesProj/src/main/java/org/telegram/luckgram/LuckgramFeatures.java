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

    private static final ArrayList<LuckgramFeature> ALL = new ArrayList<>();

    private static void add(LuckgramFeature feature) {
        ALL.add(feature);
    }

    static {
        // ---- 账号与隐私 / Accounts & Privacy
        add(toggle(org.telegram.luckgram.doublebottom.LuckgramDoubleBottom.FEATURE_KEY, ACCOUNT_PRIVACY, false,
            "双重空间", "Double space",
            "用第二个密码解锁时只显示选定的账号", "A second passcode that shows only the accounts you pick")
            .subpage(org.telegram.luckgram.doublebottom.LuckgramDoubleBottomActivity::new)
            .confirmEnable(org.telegram.luckgram.doublebottom.LuckgramDoubleBottomActivity::confirmEnable)
            .confirmDisable(org.telegram.luckgram.doublebottom.LuckgramDoubleBottomActivity::confirmDisable)
            .hiddenWhen(org.telegram.luckgram.doublebottom.LuckgramDoubleBottom::isActive));

        // ---- 消息与语言 / Messages & Language

        // ---- 媒体、通话与存储 / Media, Calls & Storage

        // ---- 资料与整理 / Profiles & Organization
        add(toggle(org.telegram.luckgram.notes.UserNotesStore.FEATURE_KEY, PROFILE_ORGANIZE, true,
            "私人备注", "Private notes",
            "给联系人、群组和频道写只有自己能看到的备注", "Notes about contacts, groups and channels that only you can see")
            .subpage(org.telegram.luckgram.notes.UserNotesListActivity::new));

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
