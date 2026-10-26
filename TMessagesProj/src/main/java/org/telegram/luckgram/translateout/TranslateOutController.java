/*
 * Luckgram: translate the message being composed before it is sent (feature 01, "translate_out").
 *
 * Translation goes through Telegram's own messages.translateText; nothing is sent anywhere else.
 */
package org.telegram.luckgram.translateout;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;

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

    /** Switch key, registered in the Luckgram feature registry ("消息与语言" group). Default: on. */
    public static final String FEATURE_KEY = "translate_out";
    public static final boolean FEATURE_DEFAULT = true;

    /** Fallback when neither the chat nor the peer's last message gives us a language. */
    public static final String FALLBACK_LANGUAGE = "en";

    /** Most used languages, shown first in the picker. */
    public static final String[] COMMON_LANGUAGES = {"en", "es", "fr", "de", "ru", "zh", "ja", "ko", "pt", "ar", "it", "tr", "uk", "vi", "id", "hi"};

    private static final long REQUEST_TIMEOUT_MS = 15_000;

    private static final String DEVICE_PREFS = "luckgram_settings";
    private static final String ACCOUNT_PREFS_PREFIX = "luckgram_translate_out_";
    private static final String KEY_CHAT_LANG_PREFIX = "lang_";
    private static final String KEY_RECENT = "recent";


    private TranslateOutController() {
    }

    // ---------------------------------------------------------------- switch

    /**
     * Whether the feature is enabled. Device level setting shared by all accounts.
     * Reads the same key the Luckgram settings page (feature 00, LuckgramSettings) writes.
     */
    public static boolean isEnabled() {
        try {
            return ApplicationLoader.applicationContext
                .getSharedPreferences(DEVICE_PREFS, Context.MODE_PRIVATE)
                .getBoolean(FEATURE_KEY, FEATURE_DEFAULT);
        } catch (Exception e) {
            return FEATURE_DEFAULT;
        }
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
        /** @param result translated text with spans ready for the input field, or null on failure */
        void onResult(CharSequence result);
    }

    /**
     * Translates the composed text via messages.translateText. Emoji, custom emoji, @mentions,
     * links and line breaks are preserved; formatting is kept when the server returns it.
     * Callback runs on the UI thread; null result means failure.
     */
    public static void translate(int account, CharSequence source, String toLang, Paint.FontMetricsInt fontMetrics, TranslateCallback callback) {
        if (TextUtils.isEmpty(source) || TextUtils.isEmpty(toLang)) {
            callback.onResult(null);
            return;
        }
        final ConnectionsManager connections = ConnectionsManager.getInstance(account);
        if (connections.getConnectionState() == ConnectionsManager.ConnectionStateWaitingForNetwork) {
            callback.onResult(null);
            return;
        }

        final CharSequence[] message = new CharSequence[]{new SpannableStringBuilder(source)};
        ArrayList<TLRPC.MessageEntity> entities = MediaDataController.getInstance(account).getEntities(message, true, false);
        if (entities == null) {
            entities = new ArrayList<>();
        }
        final TLRPC.TL_textWithEntities text = new TLRPC.TL_textWithEntities();
        text.text = message[0].toString();
        text.entities = toServerEntities(text.text, entities);

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
            callback.onResult(null);
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
                        callback.onResult(toEditable(translated, fontMetrics));
                        return;
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                }
            }
            callback.onResult(null);
        }));
        AndroidUtilities.runOnUIThread(timeout, REQUEST_TIMEOUT_MS);
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
