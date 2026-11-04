/*
 * Luckgram — spec 01 A: translating received messages inside the bubble.
 * Licensed under GNU GPL v. 2 or later.
 */
package org.telegram.luckgram.translatein;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.ReplacementSpan;

import androidx.annotation.NonNull;

import org.telegram.luckgram.LuckgramAIError;
import org.telegram.luckgram.LuckgramSettings;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.luckgram.translateout.LuckgramTranslate;
import org.telegram.luckgram.translateout.TranslateCache;
import org.telegram.luckgram.translateout.TranslateFallbackLogic;
import org.telegram.luckgram.translateout.TranslateOutController;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LanguageDetector;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.TranslateController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.RestrictedLanguagesSelectActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * spec 01 A: a received message's translation shown in the same bubble, below the original, under
 * 「已翻译 · 英语 → 中文」 and a thin line. Started from the long-press menu or the one-tap button; kept
 * per message until 「撤销翻译」 (also across leaving and re-entering the chat).
 * <p>
 * Translation goes through Telegram first and falls back to Luckgram AI ({@link LuckgramTranslate});
 * results are cached ({@link TranslateCache}), so re-opening a translation sends no request.
 */
public final class LuckgramTranslateIn {

    public interface Done {
        /** On the UI thread: true when the message now shows a translation (or it was removed). */
        void run(boolean ok, String errorMessage);
    }

    private static final String STATE_FILE = "luckgram_translate_in.json";
    private static final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LuckgramTranslateIn");
        t.setDaemon(true);
        return t;
    });
    private static volatile TranslateCache state;
    private static boolean saveScheduled;
    /** Messages whose language detection is running (no duplicate detections). */
    private static final HashSet<String> detecting = new HashSet<>();

    private LuckgramTranslateIn() {
    }

    public static boolean isEnabled() {
        return LuckgramSettings.isEnabled(TranslateInLogic.FEATURE_KEY);
    }

    /** spec 01 A.4 / specs#93: 「翻译成」 as the user chose it, or null before the first choice. */
    public static String targetLanguage() {
        return TranslateInLogic.targetLanguage(LuckgramSettings.getString(TranslateInLogic.KEY_TO_LANG, ""));
    }

    public static boolean hasTargetLanguage() {
        return targetLanguage() != null;
    }

    // ---------------------------------------------------------------- specs#105: per chat

    private static android.content.SharedPreferences chatPrefs(int account) {
        return org.telegram.messenger.ApplicationLoader.applicationContext.getSharedPreferences("luckgram_translate_in_" + account, android.content.Context.MODE_PRIVATE);
    }

    /** This chat's own 「翻译成」, or null when it follows the default. */
    public static String chatLanguage(int account, long dialogId) {
        return TranslateInLogic.targetLanguage(chatPrefs(account).getString(TranslateInLogic.KEY_CHAT_LANG_PREFIX + dialogId, null));
    }

    /** {@code lang} null = follow the default again. */
    public static void setChatLanguage(int account, long dialogId, String lang) {
        if (lang == null || lang.trim().isEmpty()) {
            chatPrefs(account).edit().remove(TranslateInLogic.KEY_CHAT_LANG_PREFIX + dialogId).apply();
        } else {
            chatPrefs(account).edit().putString(TranslateInLogic.KEY_CHAT_LANG_PREFIX + dialogId, lang.trim()).apply();
        }
    }

    /** The language messages of this chat are translated into: its own choice ?? the default ?? null. */
    public static String targetLanguage(int account, long dialogId) {
        return TranslateInLogic.effectiveLanguage(chatLanguage(account, dialogId), LuckgramSettings.getString(TranslateInLogic.KEY_TO_LANG, ""));
    }

    public static boolean hasTargetLanguage(int account, long dialogId) {
        return targetLanguage(account, dialogId) != null;
    }

    /** Same as {@link #setTargetLanguage}: the default for every chat without its own choice. */
    public static void setDefaultLanguage(String lang) {
        setTargetLanguage(lang);
    }

    /** Wipes the per-chat choices of the account (called on logout). */
    public static void clearAccount(int account) {
        try {
            chatPrefs(account).edit().clear().apply();
        } catch (Exception ignore) {
        }
    }

    public interface ScopeCallback {
        /** {@code chatOnly}: 「仅本聊天」; otherwise 「设为默认」. */
        void run(boolean chatOnly);
    }

    /**
     * specs#105 §2: asks whether {@code languageName} is for this chat only or the new default. Only
     * asks; the caller saves (so other features, e.g. image translation, can reuse it).
     */
    public static void askScope(org.telegram.ui.ActionBar.BaseFragment fragment, String languageName, ScopeCallback callback) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        final boolean zh = LuckgramStrings.isChinese();
        org.telegram.ui.ActionBar.AlertDialog.Builder b = new org.telegram.ui.ActionBar.AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider());
        b.setTitle(zh ? "翻译成" + languageName : "Translate into " + languageName);
        b.setMessage(zh ? "只在这个聊天里使用，还是设为所有聊天的默认语言？" : "Use it only in this chat, or make it the default for all chats?");
        b.setPositiveButton(zh ? "仅本聊天" : "This chat only", (d, w) -> callback.run(true));
        b.setNegativeButton(zh ? "设为默认" : "Set as default", (d, w) -> callback.run(false));
        fragment.showDialog(b.create());
    }

    /**
     * specs#105: the language list for a chat (current choice first, recently used next). The first
     * choice ever becomes the default; later changes ask 「仅本聊天 / 设为默认」. {@code then} runs
     * with the new language once it is saved.
     */
    public static void pickTargetLanguage(org.telegram.ui.ActionBar.BaseFragment fragment, long dialogId, Callback then) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        final int account = fragment.getCurrentAccount();
        final String current = targetLanguage(account, dialogId);
        new org.telegram.luckgram.translateout.TranslateOutLanguageSheet(fragment.getParentActivity(), account,
            TranslateInLogic.base(current), fragment.getResourceProvider(), code -> {
                org.telegram.luckgram.translateout.TranslateOutController.addRecentLanguage(account, code);
                if (targetLanguage() == null) {
                    setDefaultLanguage(code);
                    setChatLanguage(account, dialogId, null);
                    if (then != null) {
                        then.run(code);
                    }
                    return;
                }
                if (code.equals(current) && chatLanguage(account, dialogId) != null) {
                    if (then != null) {
                        then.run(code);
                    }
                    return;
                }
                askScope(fragment, TranslateOutController.languageName(TranslateInLogic.base(code)), chatOnly -> {
                    if (chatOnly) {
                        setChatLanguage(account, dialogId, code);
                    } else {
                        setDefaultLanguage(code);
                        setChatLanguage(account, dialogId, null);
                    }
                    if (then != null) {
                        then.run(code);
                    }
                });
            }).show();
    }

    /**
     * specs#93: the language list (common first, searchable). The choice is saved as the default for
     * every chat, then {@code then} runs with it.
     */
    public static void pickTargetLanguage(org.telegram.ui.ActionBar.BaseFragment fragment, Callback then) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        new org.telegram.luckgram.translateout.TranslateOutLanguageSheet(fragment.getParentActivity(), fragment.getCurrentAccount(),
            TranslateInLogic.base(targetLanguage()), fragment.getResourceProvider(), code -> {
                setTargetLanguage(code);
                if (then != null) {
                    then.run(code);
                }
            }).show();
    }

    public interface Callback {
        void run(String lang);
    }

    /** specs#93: the header 「英语 → 中文」 of a translation; tapping it changes the language. */
    public static final class LanguageSpan extends android.text.style.ClickableSpan {
        public final boolean voice;

        LanguageSpan(boolean voice) {
            this.voice = voice;
        }

        @Override
        public void onClick(@NonNull android.view.View widget) {
        }

        @Override
        public void updateDrawState(@NonNull android.text.TextPaint ds) {
            ds.setUnderlineText(false);
        }
    }

    /** specs#93: translate this message again into the (new) target language. */
    public static void retranslate(MessageObject msg, boolean voice, Done done) {
        if (voice) {
            final String text = voiceTextOf(msg);
            if (text != null && isVoiceTranslated(msg)) {
                forgetKey("v" + stateKey(msg, text));
                msg.luckgramVoiceTranslationBlock = null;
            }
            toggleVoice(msg, done);
        } else {
            final String text = textOf(msg);
            if (text != null && isTranslated(msg)) {
                forget(msg, text);
                msg.luckgramSetTranslation(null);
            }
            toggle(msg, done);
        }
    }

    public static void setTargetLanguage(String lang) {
        LuckgramSettings.setString(TranslateInLogic.KEY_TO_LANG, lang == null ? "" : lang);
    }

    /**
     * spec 01 A.3: 「不翻译的语言」. Shared with Telegram's "Do not translate" list, which also defaults
     * to the app language.
     */
    public static HashSet<String> doNotTranslate() {
        return RestrictedLanguagesSelectActivity.getRestrictedLanguages();
    }

    /** The text that gets translated: the message text or the media caption. */
    public static String textOf(MessageObject msg) {
        if (msg == null || msg.messageOwner == null || msg.isRestrictedMessage || msg.isSponsored()) {
            return null;
        }
        if (msg.isAnimatedEmoji() || msg.isDice() || msg.type == MessageObject.TYPE_ARTICLE || msg.isPoll()) {
            return null;
        }
        final String text = msg.messageOwner.message;
        return TextUtils.isEmpty(text) || text.trim().isEmpty() ? null : text;
    }

    public static boolean isTranslated(MessageObject msg) {
        return msg != null && msg.luckgramTranslationBlock != null;
    }

    // ---------------------------------------------------------------- one-tap button

    /**
     * spec 01 A.2: whether the one-tap button shows for {@code msg}. Starts language detection the
     * first time; {@code onDetected} runs (UI thread) when the answer may have changed.
     */
    public static boolean showButton(MessageObject msg, Runnable onDetected) {
        if (!isEnabled() || msg == null || msg.isOutOwner() || msg.translated || msg.summarized) {
            return false;
        }
        final String text = textOf(msg);
        if (text == null) {
            return false;
        }
        final String lang = msg.messageOwner.originalLanguage;
        if (lang == null) {
            detect(msg, text, onDetected);
            return false;
        }
        return TranslateInLogic.showButton(true, false, true, lang, doNotTranslate(), targetLanguage(msg.currentAccount, msg.getDialogId()));
    }

    private static void detect(MessageObject msg, String text, Runnable onDetected) {
        if (!LanguageDetector.hasSupport()) {
            return;
        }
        final String key = msg.currentAccount + ":" + msg.getDialogId() + ":" + msg.getId();
        if (!detecting.add(key)) {
            return;
        }
        LanguageDetector.detectLanguage(text, lng -> AndroidUtilities.runOnUIThread(() -> {
            detecting.remove(key);
            if (msg.messageOwner != null && msg.messageOwner.originalLanguage == null) {
                msg.messageOwner.originalLanguage = lng == null ? TranslateController.UNKNOWN_LANGUAGE : lng;
            }
            if (onDetected != null) {
                onDetected.run();
            }
        }), err -> AndroidUtilities.runOnUIThread(() -> {
            detecting.remove(key);
            if (msg.messageOwner != null && msg.messageOwner.originalLanguage == null) {
                msg.messageOwner.originalLanguage = TranslateController.UNKNOWN_LANGUAGE;
            }
        }));
    }

    // ---------------------------------------------------------------- toggle

    /**
     * Long-press 「翻译」/「撤销翻译」 and the one-tap button: shows the translation in the bubble, or
     * removes it. {@code done} gets the reason when both Telegram and Luckgram AI failed.
     */
    public static void toggle(MessageObject msg, Done done) {
        final String text = textOf(msg);
        if (text == null) {
            done.run(false, null);
            return;
        }
        if (isTranslated(msg)) {
            forget(msg, text);
            msg.luckgramSetTranslation(null);
            done.run(true, null);
            return;
        }
        final String toLang = targetLanguage(msg.currentAccount, msg.getDialogId());
        if (toLang == null) {
            done.run(false, null); // specs#93: ChatActivity asks first
            return;
        }
        translate(msg.currentAccount, TranslateFallbackLogic.SOURCE_MESSAGE, text, toLang, (translated, fromLang, error) -> {
            if (translated == null) {
                done.run(false, LuckgramTranslate.failureMessage(error));
                return;
            }
            String from = fromLang;
            if (from == null && msg.messageOwner != null) {
                from = msg.messageOwner.originalLanguage;
            }
            if (TranslateController.UNKNOWN_LANGUAGE.equals(from)) {
                from = null;
            }
            remember(msg, text, translated, from, toLang);
            msg.luckgramSetTranslation(buildBlock(msg, translated, from, toLang, false));
            done.run(true, null);
        });
    }

    /** Re-applies a saved translation when the message is shown again (spec 01 A.5). */
    public static void restore(MessageObject msg) {
        if (msg == null || msg.luckgramTranslationBlock != null || msg.luckgramRestoreChecked) {
            return;
        }
        msg.luckgramRestoreChecked = true;
        if (!isEnabled()) {
            return;
        }
        final String text = textOf(msg);
        if (text == null) {
            return;
        }
        final TranslateCache.Entry e = state().get(stateKey(msg, text), System.currentTimeMillis());
        if (e == null) {
            return;
        }
        final String[] langs = TranslateInLogic.decodeLanguages(e.fromLang);
        msg.luckgramSetTranslation(buildBlock(msg, e.text, langs[0], langs[1] != null ? langs[1] : targetLanguage(msg.currentAccount, msg.getDialogId()), false));
    }

    // ---------------------------------------------------------------- voice transcriptions (spec 01 B)

    /** The finished transcription of a voice / round video message, or null. */
    public static String voiceTextOf(MessageObject msg) {
        if (msg == null || msg.messageOwner == null || !msg.isVoiceTranscriptionOpen() || !msg.messageOwner.voiceTranscriptionFinal) {
            return null;
        }
        if (org.telegram.luckgram.voice.LuckgramTranscriber.isFailed(msg)) {
            return null;
        }
        final String text = msg.messageOwner.voiceTranscription;
        return TextUtils.isEmpty(text) || text.trim().isEmpty() ? null : text;
    }

    public static boolean isVoiceTranslated(MessageObject msg) {
        return msg != null && msg.luckgramVoiceTranslationBlock != null;
    }

    /** The 「翻译」 link under a transcription (spec 01 B.1); handled by ChatActivity.didPressMessageUrl. */
    public static final class TranslateLinkSpan extends android.text.style.ClickableSpan {
        @Override
        public void onClick(@NonNull android.view.View widget) {
        }

        @Override
        public void updateDrawState(@NonNull android.text.TextPaint ds) {
            ds.setColor(ds.linkColor);
            ds.setTextSize(ds.getTextSize() * 0.85f);
            ds.setUnderlineText(false);
        }
    }

    /**
     * spec 01 B: what is shown below a transcription: its translation, or a 「翻译」 link when the
     * transcription's language is not a "don't translate" language. {@code text} is returned unchanged
     * otherwise.
     */
    public static CharSequence decorateTranscription(MessageObject msg, CharSequence text) {
        if (msg.luckgramVoiceTranslationBlock != null) {
            return new SpannableStringBuilder(text).append(msg.luckgramVoiceTranslationBlock);
        }
        if (!isEnabled() || msg.translated) {
            return text;
        }
        final String voice = voiceTextOf(msg);
        if (voice == null) {
            return text;
        }
        restoreVoice(msg, voice);
        if (msg.luckgramVoiceTranslationBlock != null) {
            return new SpannableStringBuilder(text).append(msg.luckgramVoiceTranslationBlock);
        }
        final String lang = msg.luckgramVoiceLanguage;
        if (lang == null) {
            detectVoice(msg, voice);
            return text;
        }
        if (TranslateController.UNKNOWN_LANGUAGE.equals(lang)
            ? false
            : !TranslateInLogic.showButton(true, false, true, lang, doNotTranslate(), targetLanguage(msg.currentAccount, msg.getDialogId()))) {
            return text;
        }
        final SpannableStringBuilder sb = new SpannableStringBuilder(text);
        sb.append('\n');
        final int start = sb.length();
        sb.append(TranslateInLogic.menuLabel(false, LuckgramStrings.isChinese()));
        sb.setSpan(new TranslateLinkSpan(), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private static void detectVoice(MessageObject msg, String text) {
        if (!LanguageDetector.hasSupport()) {
            msg.luckgramVoiceLanguage = TranslateController.UNKNOWN_LANGUAGE;
            return;
        }
        final String key = "v:" + msg.currentAccount + ":" + msg.getDialogId() + ":" + msg.getId();
        if (!detecting.add(key)) {
            return;
        }
        LanguageDetector.detectLanguage(text, lng -> AndroidUtilities.runOnUIThread(() -> {
            detecting.remove(key);
            msg.luckgramVoiceLanguage = lng == null ? TranslateController.UNKNOWN_LANGUAGE : lng;
            refreshVoice(msg);
        }), err -> AndroidUtilities.runOnUIThread(() -> {
            detecting.remove(key);
            msg.luckgramVoiceLanguage = TranslateController.UNKNOWN_LANGUAGE;
            refreshVoice(msg);
        }));
    }

    private static void refreshVoice(MessageObject msg) {
        msg.forceUpdate = true;
        org.telegram.messenger.NotificationCenter.getInstance(msg.currentAccount).postNotificationName(
            org.telegram.messenger.NotificationCenter.voiceTranscriptionUpdate, msg);
    }

    /** spec 01 B.1: 「翻译」 under a transcription, or long-press 「撤销翻译」 on it. */
    public static void toggleVoice(MessageObject msg, Done done) {
        final String text = voiceTextOf(msg);
        if (text == null) {
            done.run(false, null);
            return;
        }
        final String key = "v" + stateKey(msg, text);
        if (isVoiceTranslated(msg)) {
            forgetKey(key);
            msg.luckgramVoiceTranslationBlock = null;
            refreshVoice(msg);
            done.run(true, null);
            return;
        }
        final String toLang = targetLanguage(msg.currentAccount, msg.getDialogId());
        if (toLang == null) {
            done.run(false, null); // specs#93: ChatActivity asks first
            return;
        }
        translate(msg.currentAccount, TranslateFallbackLogic.SOURCE_VOICE, text, toLang, (translated, fromLang, error) -> {
            if (translated == null) {
                done.run(false, LuckgramTranslate.failureMessage(error));
                return;
            }
            String from = fromLang != null ? fromLang : msg.luckgramVoiceLanguage;
            if (TranslateController.UNKNOWN_LANGUAGE.equals(from)) {
                from = null;
            }
            state().put(key, translated, TranslateInLogic.encodeLanguages(from, toLang), System.currentTimeMillis());
            scheduleSave();
            msg.luckgramVoiceTranslationBlock = buildBlock(msg, translated, from, toLang, true);
            refreshVoice(msg);
            done.run(true, null);
        });
    }

    private static void restoreVoice(MessageObject msg, String text) {
        if (msg.luckgramVoiceRestoreChecked) {
            return;
        }
        msg.luckgramVoiceRestoreChecked = true;
        final TranslateCache.Entry e = state().get("v" + stateKey(msg, text), System.currentTimeMillis());
        if (e == null) {
            return;
        }
        final String[] langs = TranslateInLogic.decodeLanguages(e.fromLang);
        msg.luckgramVoiceTranslationBlock = buildBlock(msg, e.text, langs[0], langs[1] != null ? langs[1] : targetLanguage(msg.currentAccount, msg.getDialogId()), true);
    }

    // ---------------------------------------------------------------- translation service

    public interface TranslateDone {
        /** UI thread. {@code translated} on success, otherwise {@code error}. */
        void run(String translated, String fromLang, LuckgramAIError error);
    }

    /**
     * spec 01 「翻译服务」: cache → Telegram messages.translateText (8 s) → Luckgram AI. Used by received
     * messages and voice transcriptions.
     */
    public static void translate(int account, String source, String text, String toLang, TranslateDone done) {
        final TranslateCache.Entry hit = LuckgramTranslate.cached(toLang, text);
        if (hit != null) {
            done.run(hit.text, hit.fromLang, null);
            return;
        }
        final ConnectionsManager connections = ConnectionsManager.getInstance(account);
        if (connections.getConnectionState() == ConnectionsManager.ConnectionStateWaitingForNetwork) {
            LuckgramTranslate.logTelegramFailure(source, TranslateFallbackLogic.NO_CONNECTION);
            viaAi(source, text, toLang, done);
            return;
        }
        final TLRPC.TL_textWithEntities t = new TLRPC.TL_textWithEntities();
        t.text = text;
        final TLRPC.TL_messages_translateText req = new TLRPC.TL_messages_translateText();
        req.flags |= 2;
        req.text.add(t);
        req.to_lang = TranslateController.normalizeLanguage(toLang);
        final boolean[] finished = new boolean[1];
        final int[] reqId = new int[1];
        final Runnable timeout = () -> {
            if (finished[0]) return;
            finished[0] = true;
            connections.cancelRequest(reqId[0], true);
            LuckgramTranslate.logTelegramFailure(source, null, true);
            viaAi(source, text, toLang, done);
        };
        reqId[0] = connections.sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
            if (finished[0]) return;
            finished[0] = true;
            AndroidUtilities.cancelRunOnUIThread(timeout);
            if (res instanceof TLRPC.TL_messages_translateResult) {
                final TLRPC.TL_messages_translateResult r = (TLRPC.TL_messages_translateResult) res;
                if (r.result != null && !r.result.isEmpty() && r.result.get(0) != null && !TextUtils.isEmpty(r.result.get(0).text)) {
                    final String translated = r.result.get(0).text;
                    LuckgramTranslate.remember(toLang, text, translated, null);
                    done.run(translated, null, null);
                    return;
                }
            }
            LuckgramTranslate.logTelegramFailure(source, err, false);
            viaAi(source, text, toLang, done);
        }));
        AndroidUtilities.runOnUIThread(timeout, TranslateFallbackLogic.TELEGRAM_TIMEOUT_MS);
    }

    private static void viaAi(String source, String text, String toLang, TranslateDone done) {
        LuckgramTranslate.viaAi(source, text, toLang, (result, error) -> {
            if (result == null) {
                done.run(null, null, error);
                return;
            }
            final String lang = TranslateFallbackLogic.aiLanguage(toLang);
            final TranslateCache.Entry e = lang == null ? null : LuckgramTranslate.cached(lang, text);
            done.run(result, e != null ? e.fromLang : null, null);
        });
    }

    // ---------------------------------------------------------------- rendering

    /** The part appended to the bubble text: header, thin line, translation (spec 01 A.1). */
    public static CharSequence buildBlock(MessageObject msg, String translation, String fromLang, String toLang, boolean voice) {
        final boolean out = msg.isOutOwner();
        final int color = Theme.getColor(out ? Theme.key_chat_outTimeText : Theme.key_chat_inTimeText);
        final String fromName = fromLang == null ? null : TranslateOutController.languageName(TranslateInLogic.base(fromLang));
        final String header = TranslateInLogic.header(fromName, TranslateOutController.languageName(TranslateInLogic.base(toLang)), LuckgramStrings.isChinese());
        final SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append('\n');
        int start = sb.length();
        sb.append(header);
        sb.setSpan(new RelativeSizeSpan(0.8f), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(color), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new LanguageSpan(voice), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); // specs#93
        // specs#105 §1: an obvious 「🌐 中文 ▾」 button on the header line; tapping it changes the language
        sb.append("   ");
        start = sb.length();
        sb.append(TranslateInLogic.languageButton(TranslateOutController.languageName(TranslateInLogic.base(toLang))));
        sb.setSpan(new RelativeSizeSpan(0.8f), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(Theme.getColor(out ? Theme.key_chat_messageLinkOut : Theme.key_chat_messageLinkIn)), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new LanguageSpan(voice), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append('\n');
        start = sb.length();
        sb.append(' ');
        sb.setSpan(new SeparatorSpan(color), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append('\n');
        sb.append(translation);
        return sb;
    }

    /** A thin horizontal line on its own text line. */
    public static final class SeparatorSpan extends ReplacementSpan {
        private final int color;

        SeparatorSpan(int color) {
            this.color = color;
        }

        @Override
        public int getSize(@NonNull Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            if (fm != null) {
                fm.ascent = fm.top = -AndroidUtilities.dp(3);
                fm.descent = fm.bottom = AndroidUtilities.dp(3);
            }
            return AndroidUtilities.dp(160);
        }

        @Override
        public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, @NonNull Paint paint) {
            final int oldColor = paint.getColor();
            final float oldStroke = paint.getStrokeWidth();
            final Paint.Style oldStyle = paint.getStyle();
            paint.setColor(color);
            paint.setAlpha(Math.min(255, (int) (Theme.getColor(Theme.key_chat_inTimeText) >>> 24) / 2 + 60));
            paint.setStrokeWidth(Math.max(1, AndroidUtilities.dp(0.66f)));
            paint.setStyle(Paint.Style.STROKE);
            final float cy = (top + bottom) / 2f;
            canvas.drawLine(x, cy, x + AndroidUtilities.dp(160), cy, paint);
            paint.setColor(oldColor);
            paint.setStrokeWidth(oldStroke);
            paint.setStyle(oldStyle);
        }
    }

    // ---------------------------------------------------------------- saved state (spec 01 A.5)

    private static String stateKey(MessageObject msg, String text) {
        return TranslateInLogic.stateKey(msg.currentAccount, msg.getDialogId(), msg.getId(), TranslateCache.key("", text));
    }

    private static void remember(MessageObject msg, String text, String translated, String from, String to) {
        state().put(stateKey(msg, text), translated, TranslateInLogic.encodeLanguages(from, to), System.currentTimeMillis());
        scheduleSave();
    }

    private static void forget(MessageObject msg, String text) {
        forgetKey(stateKey(msg, text));
    }

    private static void forgetKey(String key) {
        // An expired entry is a removed one: overwrite with time 0.
        final TranslateCache s = state();
        if (s.get(key, System.currentTimeMillis()) != null) {
            s.put(key, " ", null, 0);
            s.prune(System.currentTimeMillis());
            scheduleSave();
        }
    }

    private static TranslateCache state() {
        TranslateCache s = state;
        if (s == null) {
            synchronized (LuckgramTranslateIn.class) {
                s = state;
                if (s == null) {
                    String json = null;
                    try {
                        final File f = new File(ApplicationLoader.getFilesDirFixed(), STATE_FILE);
                        if (f.exists()) {
                            try (FileInputStream in = new FileInputStream(f)) {
                                final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                                final byte[] buf = new byte[8192];
                                int n;
                                while ((n = in.read(buf)) > 0) {
                                    out.write(buf, 0, n);
                                }
                                json = new String(out.toByteArray(), StandardCharsets.UTF_8);
                            }
                        }
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                    s = TranslateCache.parse(json, System.currentTimeMillis());
                    state = s;
                }
            }
        }
        return s;
    }

    private static void scheduleSave() {
        synchronized (LuckgramTranslateIn.class) {
            if (saveScheduled) {
                return;
            }
            saveScheduled = true;
        }
        io.execute(() -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignore) {
            }
            synchronized (LuckgramTranslateIn.class) {
                saveScheduled = false;
            }
            try {
                final File dir = ApplicationLoader.getFilesDirFixed();
                final File tmp = new File(dir, STATE_FILE + ".tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(state().serialize().getBytes(StandardCharsets.UTF_8));
                }
                if (!tmp.renameTo(new File(dir, STATE_FILE))) {
                    tmp.delete();
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    /** For the "Messages & Language" page: names of the "don't translate" languages. */
    public static String doNotTranslateSummary() {
        final StringBuilder sb = new StringBuilder();
        for (String l : doNotTranslate()) {
            if (sb.length() > 0) sb.append(LuckgramStrings.isChinese() ? "、" : ", ");
            sb.append(TranslateOutController.languageName(TranslateInLogic.base(l)));
        }
        return sb.toString();
    }
}
