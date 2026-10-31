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

    private static final ArrayList<LuckgramFeature> ALL = new ArrayList<>();

    private static void add(LuckgramFeature feature) {
        ALL.add(feature);
    }

    static {
        // ---- 账号与隐私 / Accounts & Privacy

        // ---- 消息与语言 / Messages & Language
        add(toggle(org.telegram.luckgram.selectall.SelectAllFromSender.SETTING_KEY, MESSAGES_LANGUAGE, true,
            "全选某人的消息", "Select all from sender",
            "长按消息一次选中这个人在本聊天的全部消息", "Long-press a message to select everything its sender wrote here"));
        add(toggle(org.telegram.luckgram.savedirect.SaveToSaved.SETTING_KEY, MESSAGES_LANGUAGE, true,
            "一键收藏", "Save in one tap",
            "消息菜单里一点就存到收藏夹", "Save a message to Saved Messages straight from its menu"));

        // ---- 媒体、通话与存储 / Media, Calls & Storage
        add(toggle(org.telegram.luckgram.callrec.CallRecSettings.KEY_ENABLED, MEDIA_CALLS_STORAGE, false,
            "通话录音", "Call recording",
            "一对一通话中录音，挂断后保存到收藏夹", "Record one-on-one calls and save them to Saved Messages")
            .subpage(org.telegram.luckgram.callrec.CallRecSettingsActivity::new)
            // Legal reminder before it is switched on (spec 04 §1, #58).
            .confirmEnable(org.telegram.luckgram.callrec.CallRecSettingsActivity::confirmEnable));

        // ---- 资料与整理 / Profiles & Organization
        add(toggle(org.telegram.luckgram.notes.UserNotesStore.FEATURE_KEY, PROFILE_ORGANIZE, true,
            "私人备注", "Private notes",
            "给联系人、群组和频道写只有自己能看到的备注", "Notes about contacts, groups and channels that only you can see")
            .subpage(org.telegram.luckgram.notes.UserNotesListActivity::new));
        add(toggle(org.telegram.luckgram.keywordfolders.KeywordFolderController.SETTING_KEY, PROFILE_ORGANIZE, true,
            "关键词文件夹", "Keyword folders",
            "按关键词自动归类聊天（只在本机）", "Group chats by keyword (this device only)"));
        add(toggle(org.telegram.luckgram.profileinfo.ProfileInfoFeature.FEATURE_KEY, PROFILE_ORGANIZE, true,
            "资料详情", "Profile details",
            "资料页显示 ID、大致注册时间，简介里的链接可以点开", "Show ID and approximate registration date; links in bios are clickable"));
        add(toggle(org.telegram.luckgram.sharedactivity.SharedActivityLoader.SETTING_KEY, PROFILE_ORGANIZE, true,
            "共同群动态", "Activity in shared groups",
            "用户资料页显示对方最近在共同群组里发的消息", "Show a user's recent messages in your groups in common on their profile"));

        // ---- AI
        add(toggle(AI_WRITING, AI, true,
            "AI 写作", "AI writing",
            "输入框里的 ✦ 按钮：润色、更正式、更友好、更简短、修正语法", "The ✦ button in the message field: polish, formal, friendly, shorter, grammar"));
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
