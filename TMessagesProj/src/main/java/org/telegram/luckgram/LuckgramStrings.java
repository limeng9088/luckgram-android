package org.telegram.luckgram;

import android.content.res.Resources;

import org.telegram.messenger.LocaleController;

import java.util.Locale;

/**
 * Luckgram UI strings. Telegram ships no Chinese language pack in the APK, so Luckgram strings
 * carry both languages and pick Chinese when the system language or the Telegram language is Chinese.
 */
public final class LuckgramStrings {

    private LuckgramStrings() {
    }

    public static boolean isChinese() {
        try {
            LocaleController.LocaleInfo info = LocaleController.getInstance().getCurrentLocaleInfo();
            if (info != null && info.getLangCode() != null && info.getLangCode().toLowerCase(Locale.ROOT).startsWith("zh")) {
                return true;
            }
        } catch (Throwable ignore) {
        }
        Locale system = Resources.getSystem().getConfiguration().locale;
        return system != null && "zh".equals(system.getLanguage());
    }

    public static String get(String zh, String en) {
        return isChinese() ? zh : en;
    }

    public static String featuresTitle() {
        return get("Luckgram 功能", "Luckgram Features");
    }

    public static String footer(String version) {
        return get("Luckgram " + version + "\nLuckgram 是基于 Telegram 开源代码的独立应用",
            "Luckgram " + version + "\nLuckgram is an independent app based on Telegram's open-source code");
    }

    public static String comingSoon() {
        return get("即将推出", "Coming soon");
    }

    // --- AI
    public static String aiConsentTitle() {
        return get("使用 AI 服务", "Use AI service");
    }

    public static String aiConsentText() {
        return get("你选中的内容会发送到 Luckgram 的 AI 服务，由 OpenAI 处理后返回。",
            "The content you select will be sent to Luckgram's AI service and processed by OpenAI.");
    }

    public static String aiConsentAgree() {
        return get("同意", "Agree");
    }

    public static String aiConsentDecline() {
        return get("不同意", "Decline");
    }

    public static String aiConsentRow() {
        return get("AI 服务授权", "AI service consent");
    }

    public static String aiConsentGranted() {
        return get("已同意，点按撤回", "Allowed. Tap to revoke");
    }

    public static String aiConsentNotGranted() {
        return get("未同意，首次使用 AI 功能时会询问", "Not allowed. You'll be asked the first time you use an AI feature");
    }

    public static String aiRevokeTitle() {
        return get("撤回 AI 授权？", "Revoke AI consent?");
    }

    public static String aiRevokeText() {
        return get("撤回后，下次使用 AI 功能时会重新询问。", "You'll be asked again the next time you use an AI feature.");
    }

    public static String aiRevoke() {
        return get("撤回", "Revoke");
    }

    public static String cancel() {
        return get("取消", "Cancel");
    }

    public static String ok() {
        return get("好", "OK");
    }

    public static String errorMessage(String code) {
        if (code == null) code = "";
        switch (code) {
            case LuckgramAIError.QUOTA_EXCEEDED:
                return get("今天的 AI 额度已用完，请明天再试。", "You've used up today's AI quota. Please try again tomorrow.");
            case LuckgramAIError.CONTENT_REJECTED:
                return get("内容被 AI 服务拒绝。", "The content was rejected by the AI service.");
            case LuckgramAIError.RATE_LIMITED:
                return get("请求太频繁，请稍后再试。", "Too many requests. Please wait a moment and try again.");
            case LuckgramAIError.NETWORK_ERROR:
                return get("网络错误，请检查网络后重试。", "Network error. Please check your connection and try again.");
            case LuckgramAIError.INPUT_TOO_LARGE:
                return get("内容太长，无法处理。", "The content is too large to process.");
            case LuckgramAIError.CONSENT_DENIED:
                return get("你没有同意使用 AI 服务。", "You didn't allow the AI service.");
            case LuckgramAIError.CANCELLED:
                return get("已取消。", "Cancelled.");
            case LuckgramAIError.UPSTREAM_UNAVAILABLE:
            default:
                return get("AI 服务暂时不可用，请稍后再试。", "The AI service is temporarily unavailable. Please try again later.");
        }
    }

    // --- groups
    public static String group(LuckgramFeature.Group group) {
        switch (group) {
            case ACCOUNT_PRIVACY:
                return get("账号与隐私", "Accounts & Privacy");
            case MESSAGES_LANGUAGE:
                return get("消息与语言", "Messages & Language");
            case MEDIA_CALLS_STORAGE:
                return get("媒体、通话与存储", "Media, Calls & Storage");
            case PROFILE_ORGANIZE:
                return get("资料与整理", "Profiles & Organization");
            case AI:
                return "AI";
            case DEBUG:
            default:
                return get("调试", "Debug");
        }
    }
}
