/*
 * Luckgram: translate the message being composed before it is sent (feature 01, "translate_out").
 *
 * Translation goes through Telegram's own messages.translateText; only when that fails is the text
 * sent to the Luckgram API instead (specs#82, {@link LuckgramTranslate}).
 */
package org.telegram.luckgram.translateout;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;

import org.telegram.luckgram.LuckgramSettings;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LanguageDetector;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.TranslateController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.AnimatedEmojiSpan;
import org.telegram.ui.Components.QuoteSpan;
import org.telegram.ui.Components.TextStyleSpan;
import org.telegram.ui.Components.TranslateAlert2;
import org.telegram.ui.Components.URLSpanReplacement;
import org.telegram.ui.Components.URLSpanUserMention;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class TranslateOutController {

    /** Switch key, registered in LuckgramFeatures ("消息与语言" group, default on). */
    public static final String FEATURE_KEY = "translate_out";

    /** Fallback when neither the chat nor the peer's last message gives us a language. */
    public static final String FALLBACK_LANGUAGE = "en";

    /** Most used languages, shown first in the picker. */
    public static final String[] COMMON_LANGUAGES = {"en", "es", "fr", "de", "ru", "zh", "ja", "ko", "pt", "ar", "it", "tr", "uk", "vi", "id", "hi"};

    private static final long REQUEST_TIMEOUT_MS = TranslateFallbackLogic.TELEGRAM_TIMEOUT_MS;

    private static final String ACCOUNT_PREFS_PREFIX = "luckgram_translate_out_";
    private static final String KEY_CHAT_LANG_PREFIX = "lang_";
    private static final String KEY_RECENT = "recent";
    /** specs#104: 「发送翻译」 switched on for this chat. */
    private static final String KEY_CHAT_ON_PREFIX = "on_";
    /** specs#104: LuckgramSettings key; "1" = send the translation without the preview card. */
    public static final String KEY_NO_PREVIEW = "translate_out_no_preview";


    private TranslateOutController() {
    }

    // ---------------------------------------------------------------- switch

    /**
     * Whether the feature is enabled: local switch on "Settings -> Luckgram features" and not
     * switched off remotely (LuckgramSettings, feature 00). Default comes from the registry.
     */
    public static boolean isEnabled() {
        return LuckgramSettings.isEnabled(FEATURE_KEY);
    }

    // ---------------------------------------------------------------- per chat language

    private static SharedPreferences prefs(int account) {
        return ApplicationLoader.applicationContext.getSharedPreferences(ACCOUNT_PREFS_PREFIX + account, Context.MODE_PRIVATE);
    }

    /** Language the user picked for this chat, or null if never picked. */
    public static String getSavedLanguage(int account, long dialogId) {
        return prefs(account).getString(KEY_CHAT_LANG_PREFIX + dialogId, null);
    }

    public static void saveLanguage(int account, long dialogId, String lang) {
        if (TextUtils.isEmpty(lang)) {
            return;
        }
        final SharedPreferences p = prefs(account);
        final ArrayList<String> recent = TranslateOutLogic.updateRecent(getRecentLanguages(account), lang);
        p.edit()
            .putString(KEY_CHAT_LANG_PREFIX + dialogId, lang)
            .putString(KEY_RECENT, TranslateOutLogic.joinRecent(recent))
            .apply();
    }

    /** specs#104: whether 「发送翻译」 is on for this chat (needs a saved language too). */
    public static boolean isChatOn(int account, long dialogId) {
        return prefs(account).getBoolean(KEY_CHAT_ON_PREFIX + dialogId, false) && !TextUtils.isEmpty(getSavedLanguage(account, dialogId));
    }

    public static void setChatOn(int account, long dialogId, boolean on) {
        if (on) {
            prefs(account).edit().putBoolean(KEY_CHAT_ON_PREFIX + dialogId, true).apply();
        } else {
            prefs(account).edit().remove(KEY_CHAT_ON_PREFIX + dialogId).apply();
        }
        org.telegram.messenger.NotificationCenter.getGlobalInstance().postNotificationName(org.telegram.messenger.NotificationCenter.luckgramSettingsChanged);
    }

    /** specs#104 §6: the chats with 「发送翻译」 on. */
    public static ArrayList<Long> chatsOn(int account) {
        final ArrayList<Long> result = new ArrayList<>();
        for (java.util.Map.Entry<String, ?> e : prefs(account).getAll().entrySet()) {
            if (e.getKey().startsWith(KEY_CHAT_ON_PREFIX) && Boolean.TRUE.equals(e.getValue())) {
                try {
                    result.add(Long.parseLong(e.getKey().substring(KEY_CHAT_ON_PREFIX.length())));
                } catch (NumberFormatException ignore) {
                }
            }
        }
        return result;
    }

    public static boolean previewEnabled() {
        return !"1".equals(LuckgramSettings.getString(KEY_NO_PREVIEW, ""));
    }

    public static void setPreviewEnabled(boolean enabled) {
        LuckgramSettings.setString(KEY_NO_PREVIEW, enabled ? "" : "1");
    }

    /** specs#105: puts a language picked elsewhere (reading translation) on top of the recent list. */
    public static void addRecentLanguage(int account, String lang) {
        if (TextUtils.isEmpty(lang)) {
            return;
        }
        prefs(account).edit().putString(KEY_RECENT, TranslateOutLogic.joinRecent(TranslateOutLogic.updateRecent(getRecentLanguages(account), lang))).apply();
    }

    public static ArrayList<String> getRecentLanguages(int account) {
        return TranslateOutLogic.parseRecent(prefs(account).getString(KEY_RECENT, null));
    }

    /** Wipes everything this feature stored for the account (called on logout). */
    public static void clearAccount(int account) {
        try {
            prefs(account).edit().clear().apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /**
     * Resolves the target language for a chat: saved choice, else the language detected
     * in the peer's latest text message, else English.
     *
     * @param messages chat messages, newest first (ChatActivity.messages)
     */
    public static void resolveLanguage(int account, long dialogId, List<MessageObject> messages, LanguageCallback callback) {
        final String saved = getSavedLanguage(account, dialogId);
        if (!TextUtils.isEmpty(saved)) {
            callback.onLanguage(saved);
            return;
        }
        MessageObject lastIncoming = null;
        if (messages != null) {
            for (int i = 0; i < messages.size(); i++) {
                final MessageObject m = messages.get(i);
                if (m == null || m.messageOwner == null || m.isOut()) {
                    continue;
                }
                if (m.messageOwner.action != null || TextUtils.isEmpty(m.messageOwner.message)) {
                    continue;
                }
                lastIncoming = m;
                break;
            }
        }
        if (lastIncoming == null) {
            callback.onLanguage(FALLBACK_LANGUAGE);
            return;
        }
        final String known = normalize(lastIncoming.messageOwner.originalLanguage);
        if (known != null) {
            callback.onLanguage(known);
            return;
        }
        if (!LanguageDetector.hasSupport()) {
            callback.onLanguage(FALLBACK_LANGUAGE);
            return;
        }
        final boolean[] done = new boolean[1];
        final Runnable timeout = () -> {
            if (!done[0]) {
                done[0] = true;
                callback.onLanguage(FALLBACK_LANGUAGE);
            }
        };
        AndroidUtilities.runOnUIThread(timeout, 3000);
        LanguageDetector.detectLanguage(lastIncoming.messageOwner.message, lng -> AndroidUtilities.runOnUIThread(() -> {
            if (done[0]) return;
            done[0] = true;
            AndroidUtilities.cancelRunOnUIThread(timeout);
            final String n = normalize(lng);
            callback.onLanguage(n != null ? n : FALLBACK_LANGUAGE);
        }), err -> AndroidUtilities.runOnUIThread(() -> {
            if (done[0]) return;
            done[0] = true;
            AndroidUtilities.cancelRunOnUIThread(timeout);
            callback.onLanguage(FALLBACK_LANGUAGE);
        }));
    }

    private static String normalize(String lng) {
        return TranslateOutLogic.normalizeLanguage(lng);
    }

    // ---------------------------------------------------------------- translation

    public interface LanguageCallback {
        void onLanguage(String lang);
    }

    public interface TranslateCallback {
        /**
         * @param result       translated text with spans ready for the input field, or null on failure
         * @param errorMessage on failure, why (network / AI quota / too long…), for the user
         */
        void onResult(CharSequence result, String errorMessage);
    }

    /**
     * Translates the composed text via messages.translateText. Emoji, custom emoji, @mentions,
     * links and line breaks are preserved; formatting is kept when the server returns it.
     * Callback runs on the UI thread; null result means failure.
     * <p>
     * specs#82: when Telegram fails (any error, e.g. TRANSLATIONS_DISABLED_ALT, a timeout or an empty
     * result) the plain text is translated by Luckgram AI instead; the Telegram error is logged.
     */
    public static void translate(int account, CharSequence source, String toLang, Paint.FontMetricsInt fontMetrics, TranslateCallback callback) {
        if (TextUtils.isEmpty(source) || TextUtils.isEmpty(toLang)) {
            callback.onResult(null, TranslateOutStrings.failed());
            return;
        }
        final ConnectionsManager connections = ConnectionsManager.getInstance(account);

        final CharSequence[] message = new CharSequence[]{new SpannableStringBuilder(source)};
        ArrayList<TLRPC.MessageEntity> entities = MediaDataController.getInstance(account).getEntities(message, true, false);
        if (entities == null) {
            entities = new ArrayList<>();
        }
        final TLRPC.TL_textWithEntities text = new TLRPC.TL_textWithEntities();
        text.text = message[0].toString();
        text.entities = toServerEntities(text.text, entities);
        if (connections.getConnectionState() == ConnectionsManager.ConnectionStateWaitingForNetwork) {
            // Telegram has no connection (it may be blocked while HTTPS works): go straight to Luckgram AI
            LuckgramTranslate.logTelegramFailure(TranslateFallbackLogic.SOURCE_OUT, TranslateFallbackLogic.NO_CONNECTION);
            translateViaAi(text, toLang, fontMetrics, callback);
            return;
        }

        final TLRPC.TL_messages_translateText req = new TLRPC.TL_messages_translateText();
        req.flags |= 2;
        req.text.add(text);
        req.to_lang = TranslateController.normalizeLanguage(toLang);

        final boolean[] finished = new boolean[1];
        final int[] reqId = new int[1];
        final Runnable timeout = () -> {
            if (finished[0]) return;
            finished[0] = true;
            connections.cancelRequest(reqId[0], true);
            LuckgramTranslate.logTelegramFailure(TranslateFallbackLogic.SOURCE_OUT, null, true);
            translateViaAi(text, toLang, fontMetrics, callback);
        };
        reqId[0] = connections.sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
            if (finished[0]) return;
            finished[0] = true;
            AndroidUtilities.cancelRunOnUIThread(timeout);
            if (res instanceof TLRPC.TL_messages_translateResult) {
                final ArrayList<TLRPC.TL_textWithEntities> result = ((TLRPC.TL_messages_translateResult) res).result;
                if (result != null && !result.isEmpty() && result.get(0) != null && !TextUtils.isEmpty(result.get(0).text)) {
                    try {
                        final TLRPC.TL_textWithEntities translated = TranslateAlert2.preprocess(text, result.get(0));
                        callback.onResult(toEditable(translated, fontMetrics), null);
                        return;
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                }
            }
            LuckgramTranslate.logTelegramFailure(TranslateFallbackLogic.SOURCE_OUT, err, false);
            translateViaAi(text, toLang, fontMetrics, callback);
        }));
        AndroidUtilities.runOnUIThread(timeout, REQUEST_TIMEOUT_MS);
    }

    /** specs#82: Telegram failed; translate the plain text with Luckgram AI. */
    private static void translateViaAi(TLRPC.TL_textWithEntities source, String toLang, Paint.FontMetricsInt fontMetrics, TranslateCallback callback) {
        LuckgramTranslate.viaAi(TranslateFallbackLogic.SOURCE_OUT, source.text, toLang, (result, error) -> {
            if (result == null) {
                callback.onResult(null, LuckgramTranslate.failureMessage(error));
                return;
            }
            final TLRPC.TL_textWithEntities translated = new TLRPC.TL_textWithEntities();
            translated.text = result;
            try {
                callback.onResult(toEditable(translated, fontMetrics), null);
            } catch (Exception e) {
                FileLog.e(e);
                callback.onResult(null, TranslateOutStrings.failed());
            }
        });
    }

    /**
     * Input-field entities -> server entities. Mentions by name become messageEntityMentionName,
     * plain @usernames and links are marked so the translator leaves them untouched.
     */
    private static ArrayList<TLRPC.MessageEntity> toServerEntities(String text, ArrayList<TLRPC.MessageEntity> entities) {
        final ArrayList<TLRPC.MessageEntity> result = new ArrayList<>();
        for (TLRPC.MessageEntity e : entities) {
            if (e instanceof TLRPC.TL_inputMessageEntityMentionName) {
                final TLRPC.TL_inputMessageEntityMentionName in = (TLRPC.TL_inputMessageEntityMentionName) e;
                final TLRPC.TL_messageEntityMentionName out = new TLRPC.TL_messageEntityMentionName();
                out.offset = e.offset;
                out.length = e.length;
                out.user_id = in.user_id != null ? in.user_id.user_id : 0;
                result.add(out);
            } else {
                result.add(e);
            }
        }
        // plain @usernames / links not covered by a non-style entity (style entities may wrap them)
        final ArrayList<int[]> occupied = new ArrayList<>();
        for (TLRPC.MessageEntity e : result) {
            if (!isStyleEntity(e)) {
                occupied.add(new int[]{e.offset, e.offset + e.length});
            }
        }
        for (TranslateOutLogic.ProtectedRange r : TranslateOutLogic.protectedRanges(text, occupied)) {
            final TLRPC.MessageEntity e = r.url ? new TLRPC.TL_messageEntityUrl() : new TLRPC.TL_messageEntityMention();
            e.offset = r.start;
            e.length = r.end - r.start;
            result.add(e);
        }
        MediaDataController.sortEntities(result);
        return result;
    }

    private static boolean isStyleEntity(TLRPC.MessageEntity e) {
        return e instanceof TLRPC.TL_messageEntityBold || e instanceof TLRPC.TL_messageEntityItalic
            || e instanceof TLRPC.TL_messageEntityUnderline || e instanceof TLRPC.TL_messageEntityStrike
            || e instanceof TLRPC.TL_messageEntitySpoiler || e instanceof TLRPC.TL_messageEntityBlockquote;
    }

    /** Server text + entities -> spannable for the input field (same spans as drafts use). */
    private static CharSequence toEditable(TLRPC.TL_textWithEntities t, Paint.FontMetricsInt fontMetrics) {
        final SpannableStringBuilder sb = new SpannableStringBuilder(t.text);
        if (t.entities == null || t.entities.isEmpty()) {
            return sb;
        }
        MediaDataController.sortEntities(t.entities);
        final int len = sb.length();
        for (TLRPC.MessageEntity entity : t.entities) {
            final int start = entity.offset;
            final int end = entity.offset + entity.length;
            if (start < 0 || end > len || start >= end) {
                continue;
            }
            try {
                if (entity instanceof TLRPC.TL_messageEntityMentionName) {
                    sb.setSpan(new URLSpanUserMention("" + ((TLRPC.TL_messageEntityMentionName) entity).user_id, 3), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (entity instanceof TLRPC.TL_inputMessageEntityMentionName) {
                    final TLRPC.InputUser u = ((TLRPC.TL_inputMessageEntityMentionName) entity).user_id;
                    if (u != null) {
                        sb.setSpan(new URLSpanUserMention("" + u.user_id, 3), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                } else if (entity instanceof TLRPC.TL_messageEntityCode || entity instanceof TLRPC.TL_messageEntityPre) {
                    addStyle(sb, TextStyleSpan.FLAG_STYLE_MONO, start, end);
                } else if (entity instanceof TLRPC.TL_messageEntityBold) {
                    addStyle(sb, TextStyleSpan.FLAG_STYLE_BOLD, start, end);
                } else if (entity instanceof TLRPC.TL_messageEntityItalic) {
                    addStyle(sb, TextStyleSpan.FLAG_STYLE_ITALIC, start, end);
                } else if (entity instanceof TLRPC.TL_messageEntityStrike) {
                    addStyle(sb, TextStyleSpan.FLAG_STYLE_STRIKE, start, end);
                } else if (entity instanceof TLRPC.TL_messageEntityUnderline) {
                    addStyle(sb, TextStyleSpan.FLAG_STYLE_UNDERLINE, start, end);
                } else if (entity instanceof TLRPC.TL_messageEntitySpoiler) {
                    addStyle(sb, TextStyleSpan.FLAG_STYLE_SPOILER, start, end);
                } else if (entity instanceof TLRPC.TL_messageEntityTextUrl) {
                    if (!TextUtils.isEmpty(entity.url)) {
                        sb.setSpan(new URLSpanReplacement(entity.url), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                } else if (entity instanceof TLRPC.TL_messageEntityBlockquote) {
                    QuoteSpan.putQuoteToEditable(sb, start, end, entity.collapsed);
                } else if (entity instanceof TLRPC.TL_messageEntityCustomEmoji) {
                    sb.setSpan(new AnimatedEmojiSpan(((TLRPC.TL_messageEntityCustomEmoji) entity).document_id, fontMetrics), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                // mention / url / hashtag entities stay plain text: they are re-detected on send.
            } catch (Exception e) {
                // keep the text, drop the formatting we could not apply
                FileLog.e(e);
            }
        }
        return sb;
    }

    private static void addStyle(SpannableStringBuilder sb, int flag, int start, int end) {
        final TextStyleSpan.TextStyleRun run = new TextStyleSpan.TextStyleRun();
        run.flags |= flag;
        MediaDataController.addStyleToText(new TextStyleSpan(run), start, end, sb, true);
    }

    // ---------------------------------------------------------------- language names

    /** Language name in the UI language ("西班牙语" / "Spanish"). */
    public static String languageName(String code) {
        if (TextUtils.isEmpty(code)) {
            return "";
        }
        if (TranslateOutStrings.isChinese()) {
            try {
                final String name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.SIMPLIFIED_CHINESE);
                if (!TextUtils.isEmpty(name) && !name.equalsIgnoreCase(code)) {
                    return name;
                }
            } catch (Exception ignore) {
            }
        }
        String name = null;
        try {
            name = TranslateAlert2.languageName(code);
        } catch (Exception ignore) {
        }
        if (TextUtils.isEmpty(name)) {
            try {
                name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH);
            } catch (Exception ignore) {
            }
        }
        return TextUtils.isEmpty(name) ? code : TranslateAlert2.capitalFirst(name);
    }

    /** Language name in the language itself ("Español"), for the picker's second line. */
    public static String ownLanguageName(String code) {
        try {
            final Locale l = Locale.forLanguageTag(code);
            final String name = l.getDisplayLanguage(l);
            return TextUtils.isEmpty(name) ? null : TranslateAlert2.capitalFirst(name);
        } catch (Exception e) {
            return null;
        }
    }
}
