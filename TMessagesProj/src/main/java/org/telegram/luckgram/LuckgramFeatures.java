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
    /** spec 17 */
    public static final String FREE_TRANSCRIBE = "free_transcribe";
    public static final String DICTATION = "dictation";
    public static final String DICTATION_AI_TIDY = "dictation_ai_tidy";
    /** spec 21 */
    public static final String AI_WRITING = "ai_writing";
    /** spec 20 */
    public static final String AI_REPLY = "ai_reply";
    /** spec 24 */
    public static final String AI_POST = "ai_post";

    private static final ArrayList<LuckgramFeature> ALL = new ArrayList<>();

    private static void add(LuckgramFeature feature) {
        ALL.add(feature);
    }

    static {
        // ---- 账号与隐私 / Accounts & Privacy

        // ---- 消息与语言 / Messages & Language

        // ---- 媒体、通话与存储 / Media, Calls & Storage

        // ---- 资料与整理 / Profiles & Organization

        // ---- AI
        add(toggle(AI_WRITING, AI, true,
            "AI 写作", "AI writing",
            "输入框里的 ✦ 按钮：润色、更正式、更友好、更简短、修正语法", "The ✦ button in the message field: polish, formal, friendly, shorter, grammar"));
        add(toggle(AI_POST, AI, true,
            "AI 写帖子", "AI posts",
            "有发帖权限的频道里，✦ 菜单的「写帖子」：按频道风格生成帖子", "\"Write a post\" in the ✦ menu of channels you can post in, in the channel's style"));
        add(toggle(AI_REPLY, AI, true,
            "AI 回复", "AI reply",
            "✦ 菜单里的「帮我回复」：根据对话和草稿给出 3 条候选", "\"Help me reply\" in the ✦ menu: 3 suggestions from the chat and your draft"));
        add(toggle(FREE_TRANSCRIBE, AI, true,
            "免费语音转文字", "Free voice-to-text",
            "不需要会员，把语音消息和圆形视频转成文字", "Transcribe voice and video messages without Premium"));
        add(toggle(DICTATION, AI, true,
            "语音输入", "Voice input",
            "输入框旁的按钮，说话即可输入文字", "A button in the message field to type by speaking"));
        add(toggle(DICTATION_AI_TIDY, AI, false,
            "语音输入 AI 整理", "Tidy voice input with AI",
            "去掉口头禅、补全标点，语言不变", "Remove filler words and add punctuation, same language"));

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
