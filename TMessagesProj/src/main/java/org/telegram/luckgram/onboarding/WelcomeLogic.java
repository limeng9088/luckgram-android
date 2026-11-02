package org.telegram.luckgram.onboarding;

import java.util.ArrayList;
import java.util.List;

/**
 * specs#76: first-launch welcome page, privacy policy confirmation and the step-by-step onboarding
 * before login. When they show, which feature switches each step writes, and their texts (same
 * wording as iOS {@code LuckgramWelcomeLogic}). Pure so it is unit tested.
 */
public final class WelcomeLogic {

    /** Remote config key of the privacy policy page; {@link #privacyUrl} picks a default by language. */
    public static final String PRIVACY_URL_KEY = "app.privacy_url";
    public static final String DEFAULT_PRIVACY_URL_ZH = "https://luckgram.cc/privacy-policy/";
    public static final String DEFAULT_PRIVACY_URL_EN = "https://luckgram.cc/en/privacy-policy/";
    /** LuckgramSettings key: the user tapped Continue in the privacy dialog on this device. */
    public static final String ACCEPTED_KEY = "privacy_policy_accepted";
    /** Remote config key: privacy policy version; raising it asks logged-in users once more (specs#78). */
    public static final String PRIVACY_VERSION_KEY = "app.privacy_version";
    /** LuckgramSettings keys: when (epoch ms) and which policy version the user agreed to. */
    public static final String ACCEPTED_AT_KEY = "privacy_policy_accepted_at";
    public static final String ACCEPTED_VERSION_KEY = "privacy_policy_version";
    /** LuckgramSettings key: the onboarding steps were gone through once; Get Started then goes to login. */
    public static final String ONBOARDED_KEY = "onboarding_done";
    /** LuckgramSettings key: "Subscribe" on the Pro step; the Pro page opens after login (payment needs it). */
    public static final String OPEN_PRO_AFTER_LOGIN_KEY = "onboarding_open_pro";

    // Feature switches the steps write (LuckgramFeatures / feature classes).
    public static final String KEY_TRANSLATE_OUT = "translate_out";
    public static final String KEY_QUICK_REPLIES = "quick_replies";
    public static final String KEY_AI_REPLY = "ai_reply";
    public static final String KEY_AI_WRITING = "ai_writing";
    public static final String KEY_AI_AGENTS = "ai_agents";

    public enum Step {
        /** 01 translate before sending, 02 quick replies, 20/21 AI reply and writing. */
        AI,
        /** 22 AI agents. */
        AGENTS,
        /** 19 chat summary. */
        SUMMARY,
        /** 25 people nearby + 26 square. */
        DISCOVER,
        /** S7 Luckgram Pro: always the last step; "Coming soon" while billing is off. */
        PRO
    }

    private WelcomeLogic() {
    }

    /**
     * The welcome page replaces Telegram's intro only while no account is logged in: never for users
     * who already have an account (upgrade), and adding a second account goes straight to login.
     */
    public static boolean shouldShowWelcome(int activatedAccounts) {
        return activatedAccounts <= 0;
    }

    /** The privacy dialog opens on its own until the user has tapped Continue once. */
    public static boolean shouldAskPrivacy(boolean accepted) {
        return !accepted;
    }

    /**
     * specs#78: a logged-in user is asked again ("privacy policy updated") when the remote policy
     * version is newer than the one they agreed to. Version 0 (default) never asks.
     */
    public static boolean shouldPromptUpdate(boolean loggedIn, long remoteVersion, long acceptedVersion) {
        return loggedIn && remoteVersion > 0 && remoteVersion > acceptedVersion;
    }

    /** The onboarding steps run once; later Get Started goes straight to login. */
    public static boolean shouldShowSteps(boolean onboarded) {
        return !onboarded;
    }

    /** The configured privacy URL if it is https, else the default page in the app's language. */
    public static String privacyUrl(String configured, boolean zh) {
        if (configured != null && configured.trim().startsWith("https://")) {
            return configured.trim();
        }
        return zh ? DEFAULT_PRIVACY_URL_ZH : DEFAULT_PRIVACY_URL_EN;
    }

    /** The steps shown, in order; the Pro page is always the last one (user decision 2026-11-02). */
    public static List<Step> steps() {
        ArrayList<Step> out = new ArrayList<>();
        out.add(Step.AI);
        out.add(Step.AGENTS);
        out.add(Step.SUMMARY);
        out.add(Step.DISCOVER);
        out.add(Step.PRO);
        return out;
    }

    /**
     * Whether the Pro step's subscribe button works. With billing off it shows "Coming soon",
     * disabled; only "not now" can be tapped and nothing is requested from the server.
     */
    public static boolean canSubscribe(Step step, boolean billingEnabled) {
        return step == Step.PRO && billingEnabled;
    }

    /** "n/N" over the steps actually shown; {@code index} is 0-based. */
    public static String progress(int index, int total) {
        return (index + 1) + "/" + total;
    }

    /** Fraction of the progress bar for step {@code index} (0-based), in (0, 1]. */
    public static float progressFraction(int index, int total) {
        return total <= 0 ? 1f : Math.min(1f, (index + 1) / (float) total);
    }

    /** Feature switches written by the step's two buttons ("turn on" = true, "not now" = false). */
    public static String[] toggles(Step step) {
        switch (step) {
            case AI:
                return new String[]{KEY_TRANSLATE_OUT, KEY_QUICK_REPLIES, KEY_AI_REPLY, KEY_AI_WRITING};
            case AGENTS:
                return new String[]{KEY_AI_AGENTS};
            default:
                return new String[0];
        }
    }

    /** Whether the step has a "not now" choice (steps with switches, and Pro). */
    public static boolean hasSecondary(Step step) {
        return step == Step.AI || step == Step.AGENTS || step == Step.PRO;
    }

    public static final class Texts {
        private final boolean zh;

        public Texts(boolean zh) {
            this.zh = zh;
        }

        private String t(String zh, String en) {
            return this.zh ? zh : en;
        }

        // ---- welcome page

        public String welcomeTo() {
            return t("欢迎使用", "Welcome to");
        }

        public String appName() {
            return "Luckgram";
        }

        public String intro() {
            return t("基于 Telegram 的开源客户端：AI 帮你写、帮你回、帮你总结，还有隐藏聊天、附近的人和广场。",
                "An open-source Telegram client with AI that writes, replies and sums up for you, plus hidden chats, people nearby and the Square.");
        }

        public String getStarted() {
            return t("开始使用", "Get Started");
        }

        // ---- privacy dialog

        public String privacyTitle() {
            return t("Luckgram 隐私政策", "Luckgram Privacy Policy");
        }

        public String privacyText() {
            return t("Luckgram 是非官方的 Telegram 客户端，你在 Luckgram 里用到的很多服务实际由 Telegram 提供。\n\n"
                    + "使用前请先阅读我们的隐私政策。\n\n"
                    + "点击「继续」即表示你已阅读并同意 Luckgram 隐私政策。",
                "Luckgram is an unofficial Telegram client, and many of the services you use in Luckgram are actually provided by Telegram.\n\n"
                    + "Please read our Privacy Policy before using the app.\n\n"
                    + "By tapping Continue, you confirm that you have read and agree to the Luckgram Privacy Policy.");
        }

        public String privacyUpdatedTitle() {
            return t("隐私政策已更新", "Privacy Policy Updated");
        }

        public String privacyUpdatedText() {
            return t("我们更新了 Luckgram 隐私政策，请花一点时间阅读新版本。\n\n"
                    + "点击「继续」即表示你已阅读并同意更新后的 Luckgram 隐私政策。",
                "We've updated the Luckgram Privacy Policy. Please take a moment to read the new version.\n\n"
                    + "By tapping Continue, you confirm that you have read and agree to the updated Luckgram Privacy Policy.");
        }

        public String privacyPolicyButton() {
            return t("隐私政策", "PRIVACY POLICY");
        }

        public String continueButton() {
            return t("继续", "CONTINUE");
        }

        // ---- steps

        public String title(Step s) {
            switch (s) {
                case AI: return t("AI 让聊天更轻松", "Chat Smarter with AI");
                case AGENTS: return t("AI 智能体", "AI Agents");
                case SUMMARY: return t("AI 聊天总结", "AI Chat Summary");
                case DISCOVER: return t("附近的人和广场", "Nearby & Square");
                default: return "Luckgram Pro";
            }
        }

        public String body(Step s) {
            switch (s) {
                case AI:
                    return t("Luckgram 把 AI 放进了输入框：发送前一键翻译成对方的语言，常用回复存成模板一点就发，还能让 AI 帮你回复、润色和改写。",
                        "Luckgram puts AI right in the message field: translate into the other person's language before sending, insert saved replies with a tap, and let AI draft, polish or rewrite your messages.");
                case AGENTS:
                    return t("写作、翻译、出行、学习……每个智能体专注一件事，在 AI 助手里随时找它们帮忙。",
                        "Writing, translating, travel, studying: each agent is focused on one job and ready in the AI assistant whenever you need it.");
                case SUMMARY:
                    return t("群里消息太多看不完？让 AI 把最近的聊天整理成要点，还能接着追问细节。",
                        "Too many messages to catch up on? AI turns the recent chat into key points, and you can ask follow-up questions.");
                case DISCOVER:
                    return t("找到附近同样开启的 Luckgram 用户，在广场上发图文短帖、点赞和评论。随时可以在「Luckgram 功能」里关闭。",
                        "Find Luckgram users nearby who turned it on, and post, like and comment in the Square. You can turn both off anytime in Luckgram Features.");
                default:
                    return t("更多的 AI 额度，让每个 AI 功能都用得尽兴。",
                        "More AI every day, for every AI feature.");
            }
        }

        /** Bold question under the text, or null. */
        public String question(Step s) {
            switch (s) {
                case AI: return t("要开启这些 AI 功能吗？", "Turn on these AI features?");
                case AGENTS: return t("要开启 AI 智能体吗？", "Turn on AI Agents?");
                default: return null;
            }
        }

        public String primary(Step s) {
            switch (s) {
                case AI: return t("开启 AI 功能", "Turn On AI Features");
                case AGENTS: return t("开启 AI 智能体", "Turn On AI Agents");
                case PRO: return t("订阅", "Subscribe");
                default: return t("继续", "Continue");
            }
        }

        public String comingSoon() {
            return t("即将推出", "Coming soon");
        }

        /** The Pro button with the cheapest plan, e.g. "订阅 · 250 ⭐ / 30 天". */
        public String subscribe(int stars, int days) {
            if (stars <= 0) return primary(Step.PRO);
            return days > 0
                ? t("订阅 · " + stars + " ⭐ / " + days + " 天", "Subscribe · " + stars + " ⭐ / " + days + " days")
                : t("订阅 · " + stars + " ⭐", "Subscribe · " + stars + " ⭐");
        }

        public String secondary(Step s) {
            switch (s) {
                case AI: return t("暂不开启", "Continue without AI");
                case AGENTS: return t("暂不开启", "Continue without AI Agents");
                case PRO: return t("暂不订阅", "Continue without Pro");
                default: return null;
            }
        }

        // ---- Pro step

        public String[][] proFeatures() {
            return new String[][]{
                {t("更多 AI 额度", "More AI every day"),
                    t("AI 对话、写作、总结、智能体、生图和语音转文字，每天可用次数更多。", "Higher daily limits for AI chat, writing, summaries, agents, images and transcription.")},
                {t("所有设备共享", "Shared across devices"),
                    t("绑定同一个 Telegram 账号的设备共享 Pro。", "Every device linked to your Telegram account shares Pro.")},
                {t("用 Telegram Stars 付款", "Paid with Telegram Stars"),
                    t("不需要绑卡，已是 Pro 时再买会从到期时间往后延。", "No card needed. Buying again while Pro extends from the current end date.")},
            };
        }

        public String proFootnote() {
            return t("登录 Telegram 后完成付款。", "You'll complete the payment after logging in to Telegram.");
        }

        // ---- illustration cards (made-up names and messages)

        public String demoChatName() { return t("小雨", "Mia"); }
        public String demoChatStatus() { return t("刚刚在线", "last seen just now"); }
        public String demoIncoming() { return t("周末一起去爬山吗？", "Hiking this weekend?"); }
        public String demoQuickTitle() { return t("快捷回复", "QUICK REPLIES"); }
        public String[] demoQuickReplies() {
            return new String[]{t("好啊，几点出发？", "Sure, what time?"), t("这周有点忙", "Busy this week"), t("改天约", "Maybe next time")};
        }
        public String demoTranslated() { return "¡Claro! ¿A qué hora?"; }
        public String demoTranslatedLabel() { return t("发送前翻译", "Translated"); }

        public String demoAgentsTitle() { return t("AI 智能体", "AI Agents"); }
        public String[][] demoAgents() {
            return new String[][]{
                {t("写作助手", "Writer"), t("邮件、文案、帖子，告诉它要点就行", "Emails, posts and captions from a few notes")},
                {t("出行规划", "Trip Planner"), t("按天排好行程、交通和预算", "Day-by-day plans with transport and budget")},
                {t("学习伙伴", "Study Buddy"), t("把难懂的概念讲清楚，还会出题考你", "Explains hard topics and quizzes you")},
            };
        }

        public String demoSummaryTitle() { return t("AI 聊天总结", "AI Chat Summary"); }
        public String demoSummaryHead() { return t("✦ 总结好了，要点如下：", "✦ Here are the key points:"); }
        public String[] demoSummaryPoints() {
            return new String[]{t("新版本定在周五上线", "New version ships on Friday"), t("设计稿周三前定稿", "Designs final by Wednesday"),
                t("小林负责安排测试", "Leo sets up the testing"), t("下周一开复盘会", "Review meeting next Monday")};
        }

        public String[][] demoNearby() {
            return new String[][]{{t("阿杰", "Jake"), t("300 米", "300 m")}, {"Luna", t("1.2 公里", "1.2 km")}};
        }
        public String demoSquarePost() { return t("今天的晚霞太好看了 🌇", "Tonight's sunset 🌇"); }
        public String demoSquareTitle() { return t("广场", "Square"); }
        public String demoNearbyTitle() { return t("附近的人", "People Nearby"); }
    }
}
