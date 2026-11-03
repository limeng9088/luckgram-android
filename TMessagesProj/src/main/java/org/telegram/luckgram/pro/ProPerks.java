package org.telegram.luckgram.pro;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * specs#87 (29): what the Pro pages show and which actions are Pro-only, without Android types:
 * the 7 perk rows of the subscription page (fixed order, hidden when missing from
 * {@code pro.perks}), the AI credits line, the summary ranges that need Pro, the reasons in the
 * shared "upgrade" sheet and the "why not Pro" survey after "Continue without Pro". Same wording as
 * iOS {@code LuckgramProPerks}.
 *
 * <p>Pure Java (java.* only), unit-tested in {@code ProPerksTest}.
 */
public final class ProPerks {

    /** Display order of the subscription page (spec 29 需求 2). */
    public static final String[] ORDER = {
        ProState.PERK_AGENT_MARKET, ProState.PERK_ACCOUNTS, ProState.PERK_AI, ProState.PERK_SUMMARY,
        ProState.PERK_TRANSLATE, ProState.PERK_COMMUNITY, ProState.PERK_NO_ADS,
    };

    /** Default of {@code pro.monthly_credits} (S12). */
    public static final int DEFAULT_MONTHLY_CREDITS = 50;

    // ---- remote config keys (S4/S12); a missing number leaves it out of the text
    public static final String CFG_PERKS = "pro.perks";
    public static final String CFG_MONTHLY_CREDITS = "pro.monthly_credits";
    public static final String CFG_SUMMARY_MAX = "pro.summary_max_messages";
    public static final String CFG_ACCOUNTS_MAX_PRO = "accounts.max_pro";
    public static final String CFG_CHAT_PRO = "quota.pro.chat_per_day";
    public static final String CFG_CHAT_FREE = "quota.free.chat_per_day";
    public static final String CFG_TRANSLATE_PRO = "quota.pro.translate_per_day";
    public static final String CFG_TRANSLATE_FREE = "quota.free.translate_per_day";

    /** {@code model_tier} of /v1/ai/chat for the premium model (S12 需求 3). */
    public static final String MODEL_TIER_PREMIUM = "premium";
    public static final String ERROR_NOT_PRO = "not_pro";
    public static final String ERROR_CREDITS_EXHAUSTED = "credits_exhausted";

    // ---- "why not Pro" survey (需求 7)
    public static final String SURVEY_SHOWN_KEY = "pro_skip_survey_shown";
    public static final String SURVEY_PATH = "/v1/feedback/pro-skip";
    public static final String SURVEY_SOURCE = "onboarding";
    public static final String[] SURVEY_REASONS = {"too_expensive", "prefer_free", "try_first"};

    /** Why the upgrade sheet opens (需求 6). */
    public enum Reason {QUOTA, PRO_ONLY, SUMMARY_RANGE, FOLLOW_UP, PREMIUM_MODEL}

    private ProPerks() {
    }

    /** Numbers for the perk texts; 0 = unknown (left out of the text). */
    public static final class Numbers {
        public int accountsMaxPro;
        public int chatPro, chatFree;
        public int monthlyCredits = DEFAULT_MONTHLY_CREDITS;
        public int summaryMaxPro = ProState.SUMMARY_MAX_PRO;
        public int translatePro, translateFree;
    }

    public static final class Row {
        public final String perk;
        public final boolean proOnly;
        public final String title;
        public final String subtitle;

        Row(String perk, boolean proOnly, String title, String subtitle) {
            this.perk = perk;
            this.proOnly = proOnly;
            this.title = title;
            this.subtitle = subtitle;
        }
    }

    /**
     * The perks that are live: from {@code /v1/me} when it sent any, else {@code pro.perks} of the
     * remote config (comma separated), else none (the page then keeps its generic text).
     */
    public static List<String> livePerks(List<String> fromMe, String fromConfig) {
        ArrayList<String> out = new ArrayList<>();
        if (fromMe != null && !fromMe.isEmpty()) {
            for (String p : fromMe) if (p != null && !out.contains(p.trim())) out.add(p.trim());
            return out;
        }
        if (fromConfig != null) {
            for (String p : fromConfig.replace("[", "").replace("]", "").replace("\"", "").split(",")) {
                p = p.trim();
                if (!p.isEmpty() && !out.contains(p)) out.add(p);
            }
        }
        return out;
    }

    /** The rows to show: {@link #ORDER} filtered by {@code perks}; unknown perk names are ignored. */
    public static List<Row> rows(List<String> perks, Numbers n, boolean zh) {
        if (perks == null || perks.isEmpty()) return Collections.emptyList();
        Texts t = new Texts(zh);
        ArrayList<Row> out = new ArrayList<>();
        for (String p : ORDER) {
            if (perks.contains(p)) out.add(new Row(p, isProOnlyRow(p), t.perkTitle(p), t.perkSubtitle(p, n)));
        }
        return out;
    }

    /** Rows marked "Pro 专享" (the others are better with Pro but also exist for free users). */
    public static boolean isProOnlyRow(String perk) {
        return ProState.PERK_AGENT_MARKET.equals(perk) || ProState.PERK_ACCOUNTS.equals(perk);
    }

    // ---------------------------------------------------------------- summary (需求 5)

    /** Free users summarize at most this many messages (S12: free 100). */
    public static final int SUMMARY_FREE_MAX = ProState.SUMMARY_MAX_FREE;

    /** A range of {@code count} messages needs Pro when it is larger than the free limit. */
    public static boolean summaryNeedsPro(int count, boolean pro) {
        return !pro && count > SUMMARY_FREE_MAX;
    }

    /** Most messages offered for "all unread": the Pro limit for Pro users, else the free one. */
    public static int summaryUnreadCap(int unread, boolean pro, int proMax) {
        int cap = pro ? Math.max(SUMMARY_FREE_MAX, proMax) : SUMMARY_FREE_MAX;
        return Math.max(0, Math.min(unread, cap));
    }

    // ---------------------------------------------------------------- survey (需求 7)

    /** Only during onboarding, once per device. */
    public static boolean shouldShowSurvey(boolean fromOnboarding, boolean alreadyShown) {
        return fromOnboarding && !alreadyShown;
    }

    /** Request body for the chosen option, or null when nothing is chosen (then nothing is sent). */
    public static String surveyReason(int choice) {
        return choice >= 0 && choice < SURVEY_REASONS.length ? SURVEY_REASONS[choice] : null;
    }

    // ---------------------------------------------------------------- texts

    public static final class Texts {
        private final boolean zh;

        public Texts(boolean zh) {
            this.zh = zh;
        }

        private String t(String zh, String en) {
            return this.zh ? zh : en;
        }

        public String proOnlyBadge() {
            return t("Pro 专享", "Pro only");
        }

        public String perkTitle(String perk) {
            switch (perk) {
                case ProState.PERK_AGENT_MARKET: return t("AI 智能体市场", "AI Agent Market");
                case ProState.PERK_ACCOUNTS: return t("更多账号与云备份", "More Accounts & Cloud Backup");
                case ProState.PERK_AI: return t("AI 畅用", "More AI");
                case ProState.PERK_SUMMARY: return t("AI 聊天分析", "AI Chat Analysis");
                case ProState.PERK_TRANSLATE: return t("内置翻译", "Built-in Translation");
                case ProState.PERK_COMMUNITY: return t("Pro 社群", "Pro Community");
                case ProState.PERK_NO_ADS: return t("去除赞助消息", "No Sponsored Messages");
                default: return perk;
            }
        }

        public String perkSubtitle(String perk, Numbers n) {
            if (n == null) n = new Numbers();
            switch (perk) {
                case ProState.PERK_AGENT_MARKET:
                    return t("发现并使用更多 AI 智能体。", "Discover and use more AI agents.");
                case ProState.PERK_ACCOUNTS:
                    return n.accountsMaxPro > 0
                        ? t("可同时登录 " + n.accountsMaxPro + " 个账号；账号备份加密存到云端。",
                            "Log in to up to " + n.accountsMaxPro + " accounts at once; account backups are encrypted in the cloud.")
                        : t("可同时登录更多账号；账号备份加密存到云端。",
                            "Log in to more accounts at once; account backups are encrypted in the cloud.");
                case ProState.PERK_AI: {
                    String daily = n.chatPro > 0
                        ? t("AI 对话每天 " + n.chatPro + " 次" + (n.chatFree > 0 ? "（免费 " + n.chatFree + " 次）" : ""),
                            "AI chat " + n.chatPro + " times a day" + (n.chatFree > 0 ? " (free: " + n.chatFree + ")" : ""))
                        : t("AI 对话每天可用次数更多", "More AI chats every day");
                    return daily + t("，每月另送 " + n.monthlyCredits + " 个 AI 点数，用于高级模型。",
                        ", plus " + n.monthlyCredits + " AI credits a month for the premium model.");
                }
                case ProState.PERK_SUMMARY:
                    return t("总结最多 " + n.summaryMaxPro + " 条消息并可追问（免费 " + SUMMARY_FREE_MAX + " 条）。",
                        "Summarize up to " + n.summaryMaxPro + " messages and ask follow-up questions (free: " + SUMMARY_FREE_MAX + ").");
                case ProState.PERK_TRANSLATE:
                    return n.translatePro > 0
                        ? t("每天翻译 " + n.translatePro + " 次" + (n.translateFree > 0 ? "（免费 " + n.translateFree + " 次）" : "") + "。",
                            "Translate " + n.translatePro + " times a day" + (n.translateFree > 0 ? " (free: " + n.translateFree + ")" : "") + ".")
                        : t("每天可翻译的次数更多。", "More translations every day.");
                case ProState.PERK_COMMUNITY:
                    return t("加入 Luckgram Pro 用户私密群。", "Join the private group for Luckgram Pro users.");
                case ProState.PERK_NO_ADS:
                    return t("频道里不显示 Telegram 赞助消息。", "Telegram sponsored messages are hidden in channels.");
                default:
                    return "";
            }
        }

        /** "本月 AI 点数" row title. */
        public String creditsTitle() {
            return t("本月 AI 点数", "AI credits this month");
        }

        /**
         * "剩余 N / 50，X 日重置"; without a reset time just "剩余 N / 50". {@code total} 0 falls back
         * to the configured monthly amount.
         */
        public String creditsValue(int remaining, int total, long resetsAtMs, TimeZone tz) {
            String left = t("剩余 " + Math.max(0, remaining) + " / " + total, Math.max(0, remaining) + " / " + total + " left");
            if (resetsAtMs <= 0) return left;
            Calendar c = Calendar.getInstance(tz != null ? tz : TimeZone.getDefault(), Locale.US);
            c.setTimeInMillis(resetsAtMs);
            String en = String.format(Locale.US, "%tb %d", c, c.get(Calendar.DAY_OF_MONTH));
            return left + t("，" + c.get(Calendar.DAY_OF_MONTH) + " 日重置", ", resets " + en);
        }

        /** "本月 AI 点数 剩余 N / 50，X 日重置" in one line. */
        public String creditsLine(int remaining, int total, long resetsAtMs, TimeZone tz) {
            return creditsTitle() + t(" ", ": ") + creditsValue(remaining, total, resetsAtMs, tz);
        }

        // ---- model choice (需求 3)

        public String modelTitle() {
            return t("模型", "Model");
        }

        public String modelStandard() {
            return t("标准模型", "Standard model");
        }

        public String modelPremium() {
            return t("高级模型 · 每条 1 点", "Premium model · 1 credit per message");
        }

        public String creditsExhausted() {
            return t("本月 AI 点数已用完，已切回标准模型。", "You've used all AI credits for this month. Switched back to the standard model.");
        }

        public String notPro() {
            return t("高级模型是 Pro 专享。", "The premium model is for Pro members.");
        }

        // ---- upgrade sheet (需求 6)

        public String upgradeTitle() {
            return t("升级 Luckgram Pro", "Upgrade to Luckgram Pro");
        }

        public String upgradeReason(Reason r) {
            switch (r) {
                case QUOTA:
                    return t("今天的 AI 额度已用完。开通 Pro 每天可以用更多次。", "You've used up today's AI quota. Pro gives you more every day.");
                case SUMMARY_RANGE:
                    return t("总结 " + SUMMARY_FREE_MAX + " 条以上的消息是 Pro 专享。", "Summarizing more than " + SUMMARY_FREE_MAX + " messages is for Pro members.");
                case FOLLOW_UP:
                    return t("对总结结果追问是 Pro 专享。", "Asking follow-up questions about a summary is for Pro members.");
                case PREMIUM_MODEL:
                    return t("高级模型是 Pro 专享，每月送 AI 点数。", "The premium model is for Pro members, with AI credits every month.");
                case PRO_ONLY:
                default:
                    return t("这是 Pro 专享功能。", "This feature is for Pro members.");
            }
        }

        public String upgradeButton() {
            return t("查看 Pro", "See Pro");
        }

        // ---- summary (需求 5)

        public String allUnread(int n) {
            return t("全部未读（" + n + " 条）", "All unread (" + n + ")");
        }

        public String followUpHintFree() {
            return t("追问（Pro 专享）", "Ask follow-ups (Pro only)");
        }

        // ---- survey (需求 7)

        public String surveySubtitle() {
            return t("想听听你的想法", "We'd love your thoughts");
        }

        /** Title; {@link #surveyTitleAccent()} is drawn in the accent color. */
        public String surveyTitle() {
            return t("为什么暂不开通 Pro？", "Why not Pro for now?");
        }

        public String surveyTitleAccent() {
            return "Pro";
        }

        public String[] surveyOptions() {
            return new String[]{t("价格太贵", "It's too expensive"), t("我更想免费使用", "I'd rather use it for free"), t("想先试用再决定", "I'd like to try it first")};
        }

        public String surveyContinue() {
            return t("继续", "Continue");
        }

        public String surveySkip() {
            return t("跳过", "Skip");
        }
    }
}
