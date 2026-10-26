/*
 * Luckgram: UI strings for call recording, in Chinese and English.
 *
 * Telegram's language packs have no official Chinese, so these strings follow the in-app language
 * when it is Chinese, otherwise the system language.
 */
package org.telegram.luckgram.callrec;

import org.telegram.messenger.LocaleController;

import java.util.Locale;

public final class CallRecStrings {

    private CallRecStrings() {
    }

    public static boolean isChinese() {
        try {
            final LocaleController.LocaleInfo info = LocaleController.getInstance().getCurrentLocaleInfo();
            if (info != null && info.pluralLangCode != null && info.pluralLangCode.toLowerCase(Locale.ROOT).startsWith("zh")) {
                return true;
            }
            final Locale system = LocaleController.getInstance().getSystemDefaultLocale();
            return system != null && "zh".equalsIgnoreCase(system.getLanguage());
        } catch (Exception e) {
            return "zh".equalsIgnoreCase(Locale.getDefault().getLanguage());
        }
    }

    private static String s(String zh, String en) {
        return isChinese() ? zh : en;
    }

    // --- Feature / settings ---

    public static String featureName() {
        return s("通话录音", "Call Recording");
    }

    public static String featureDescription() {
        return s("一对一通话中录音，挂断后保存到收藏夹", "Record one-on-one calls and save them to Saved Messages");
    }

    public static String enable() {
        return s("启用通话录音", "Enable Call Recording");
    }

    public static String enableInfo() {
        return s("开启后，一对一语音和视频通话界面会出现「录音」按钮。群组通话和直播不支持录音。",
                "Adds a Record button to one-on-one voice and video calls. Group calls and live streams can't be recorded.");
    }

    public static String legalTitle() {
        return s("录音前请注意", "Before You Record");
    }

    public static String legalText() {
        return s("各国和地区对通话录音的法律规定不同，录音前请先告知对方并取得同意。",
                "Laws on recording calls differ between countries and regions. Tell the other person and get their consent before recording.");
    }

    public static String gotIt() {
        return s("我知道了", "Got It");
    }

    public static String cancel() {
        return s("取消", "Cancel");
    }

    public static String autoRecord() {
        return s("通话接通后自动录音", "Record Automatically");
    }

    public static String autoRecordInfo() {
        return s("一对一通话接通后自动开始录音，随时可以点按钮停止。",
                "Starts recording as soon as a one-on-one call connects. Tap the button to stop at any time.");
    }

    public static String aiTranscript() {
        return s("AI 文字稿", "AI Transcript");
    }

    public static String aiTranscriptInfo() {
        return s("挂断后生成文字稿和 3–6 条要点总结，回复在录音下面。录音会发送到 Luckgram 的 AI 服务处理。",
                "After the call, a transcript and a 3–6 point summary are posted as a reply to the recording. The audio is sent to the Luckgram AI service for processing.");
    }

    public static String saveTo() {
        return s("保存到", "Save To");
    }

    public static String savedMessages() {
        return s("收藏夹", "Saved Messages");
    }

    public static String chooseChat() {
        return s("选择聊天", "Choose a Chat");
    }

    public static String resetToSaved() {
        return s("改回收藏夹", "Use Saved Messages");
    }

    public static String saveToInfo() {
        return s("录音只保存在你自己的 Telegram 云端（收藏夹或你选的聊天），上传完成后删除本机临时文件。",
                "Recordings are kept only in your own Telegram cloud (Saved Messages or the chat you pick). The temporary file on this device is deleted once the upload finishes.");
    }

    // --- Call screen ---

    public static String record() {
        return s("录音", "Record");
    }

    /** Label while recording, e.g. "录音 0:21". */
    public static String recording(String elapsed) {
        return isChinese() ? "录音 " + elapsed : "Recording " + elapsed;
    }

    public static String stopRecordingDescription() {
        return s("停止录音", "Stop recording");
    }

    public static String startFailed() {
        return s("无法开始录音", "Couldn't start recording");
    }

    public static String enableFirst() {
        return s("请先在设置中开启通话录音", "Turn on Call Recording in Settings first");
    }

    // --- Saved message ---

    /** File name without extension, e.g. "通话 张三 2026-10-26 15.04". */
    public static String fileTitle(String peerName, String dateTime) {
        return (isChinese() ? "通话 " : "Call ") + peerName + " " + dateTime;
    }

    /** Caption, e.g. "与 张三 的通话 · 12:34". */
    public static String caption(String peerName, String duration) {
        return isChinese() ? "与 " + peerName + " 的通话 · " + duration : "Call with " + peerName + " · " + duration;
    }

    public static String summaryHeader() {
        return s("要点总结", "Summary");
    }

    public static String transcriptHeader() {
        return s("文字稿", "Transcript");
    }

    public static String transcriptContinued() {
        return s("文字稿（续）", "Transcript (continued)");
    }

    public static String transcriptEmpty() {
        return s("（没有识别到说话内容）", "(No speech detected)");
    }

    public static String tooLongForTranscript() {
        return s("录音超过 25 MB，未生成文字稿。", "The recording is larger than 25 MB, so no transcript was made.");
    }

    public static String transcriptFailed(String reason) {
        return isChinese() ? "文字稿生成失败：" + reason : "Couldn't make a transcript: " + reason;
    }

    public static String savedToast(String target) {
        return isChinese() ? "通话录音已保存到「" + target + "」" : "Call recording saved to " + target;
    }
}
