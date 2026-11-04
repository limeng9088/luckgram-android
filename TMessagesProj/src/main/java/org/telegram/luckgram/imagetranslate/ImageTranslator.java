package org.telegram.luckgram.imagetranslate;

import android.text.TextUtils;

import org.telegram.luckgram.LuckgramAIError;
import org.telegram.luckgram.translatein.LuckgramTranslateIn;
import org.telegram.luckgram.translateout.LuckgramTranslate;
import org.telegram.luckgram.translateout.TranslateCache;
import org.telegram.luckgram.translateout.TranslateFallbackLogic;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.TranslateController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.List;

/**
 * specs#108: translates the recognized text blocks of one image (needs 5: only the text is sent,
 * never the image). Same channel as spec 01: cache → Telegram {@code messages.translateText}
 * (batched, 8 s) → Luckgram AI per block.
 */
public final class ImageTranslator {

    static final String SOURCE = "image";

    public interface Done {
        /** UI thread. {@code translations} has one entry per input (null = left as is); {@code error} when nothing could be translated. */
        void run(List<String> translations, String fromLang, LuckgramAIError error);
    }

    private ImageTranslator() {
    }

    /**
     * @param texts block texts; {@code null} entries are not translated (numbers, already in the target).
     */
    public static void translate(int account, List<String> texts, String toLang, Done done) {
        final int n = texts.size();
        final String[] out = new String[n];
        final ArrayList<Integer> todo = new ArrayList<>();
        final ArrayList<String> todoTexts = new ArrayList<>();
        String fromLang = null;
        for (int i = 0; i < n; i++) {
            String t = texts.get(i);
            if (TextUtils.isEmpty(t)) {
                continue;
            }
            TranslateCache.Entry hit = LuckgramTranslate.cached(toLang, t);
            if (hit != null) {
                out[i] = hit.text;
                if (fromLang == null) fromLang = hit.fromLang;
            } else {
                todo.add(i);
                todoTexts.add(t);
            }
        }
        final String cachedFrom = fromLang;
        if (todo.isEmpty()) {
            done.run(list(out), cachedFrom, null);
            return;
        }
        final List<List<Integer>> batches = ImageTranslateLogic.batches(todoTexts);
        final int[] remaining = {batches.size()};
        final LuckgramAIError[] lastError = new LuckgramAIError[1];
        for (List<Integer> batch : batches) {
            ArrayList<String> pieces = new ArrayList<>();
            for (int k : batch) pieces.add(todoTexts.get(k));
            translateBatch(account, pieces, toLang, (results, error) -> {
                for (int j = 0; j < batch.size(); j++) {
                    String r = results == null ? null : results.get(j);
                    if (r != null) out[todo.get(batch.get(j))] = r;
                }
                if (error != null) lastError[0] = error;
                if (--remaining[0] == 0) {
                    boolean any = false;
                    for (int i = 0; i < n; i++) {
                        if (out[i] != null) { any = true; break; }
                    }
                    done.run(list(out), cachedFrom, any ? null : lastError[0]);
                }
            });
        }
    }

    private static List<String> list(String[] a) {
        ArrayList<String> l = new ArrayList<>(a.length);
        for (String s : a) l.add(s);
        return l;
    }

    private interface BatchDone {
        void run(List<String> results, LuckgramAIError error);
    }

    /** One Telegram request for the batch; on failure each piece goes to Luckgram AI. */
    private static void translateBatch(int account, List<String> pieces, String toLang, BatchDone done) {
        final ConnectionsManager connections = ConnectionsManager.getInstance(account);
        if (connections.getConnectionState() == ConnectionsManager.ConnectionStateWaitingForNetwork) {
            LuckgramTranslate.logTelegramFailure(SOURCE, TranslateFallbackLogic.NO_CONNECTION);
            viaAi(pieces, toLang, done);
            return;
        }
        final TLRPC.TL_messages_translateText req = new TLRPC.TL_messages_translateText();
        req.flags |= 2;
        for (String p : pieces) {
            TLRPC.TL_textWithEntities t = new TLRPC.TL_textWithEntities();
            t.text = p;
            req.text.add(t);
        }
        req.to_lang = TranslateController.normalizeLanguage(toLang);
        final boolean[] finished = new boolean[1];
        final int[] reqId = new int[1];
        final Runnable timeout = () -> {
            if (finished[0]) return;
            finished[0] = true;
            connections.cancelRequest(reqId[0], true);
            LuckgramTranslate.logTelegramFailure(SOURCE, null, true);
            viaAi(pieces, toLang, done);
        };
        reqId[0] = connections.sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
            if (finished[0]) return;
            finished[0] = true;
            AndroidUtilities.cancelRunOnUIThread(timeout);
            if (res instanceof TLRPC.TL_messages_translateResult) {
                TLRPC.TL_messages_translateResult r = (TLRPC.TL_messages_translateResult) res;
                if (r.result != null && r.result.size() == pieces.size()) {
                    ArrayList<String> results = new ArrayList<>();
                    for (int i = 0; i < pieces.size(); i++) {
                        String text = r.result.get(i) == null ? null : r.result.get(i).text;
                        results.add(TextUtils.isEmpty(text) ? null : text);
                        if (!TextUtils.isEmpty(text)) LuckgramTranslate.remember(toLang, pieces.get(i), text, null);
                    }
                    done.run(results, null);
                    return;
                }
            }
            LuckgramTranslate.logTelegramFailure(SOURCE, err, false);
            viaAi(pieces, toLang, done);
        }));
        AndroidUtilities.runOnUIThread(timeout, TranslateFallbackLogic.TELEGRAM_TIMEOUT_MS);
    }

    /** Luckgram AI, one block after another (keeps the AI consent dialog to one and the load low). */
    private static void viaAi(List<String> pieces, String toLang, BatchDone done) {
        final ArrayList<String> results = new ArrayList<>();
        final LuckgramAIError[] error = new LuckgramAIError[1];
        next(pieces, toLang, results, error, done);
    }

    private static void next(List<String> pieces, String toLang, ArrayList<String> results, LuckgramAIError[] error, BatchDone done) {
        int i = results.size();
        if (i >= pieces.size()) {
            done.run(results, error[0]);
            return;
        }
        if (error[0] != null && (LuckgramAIError.CONSENT_DENIED.equals(error[0].code) || "quota_exceeded".equals(error[0].code))) {
            // Asking again for every block would repeat the dialog / error.
            results.add(null);
            next(pieces, toLang, results, error, done);
            return;
        }
        LuckgramTranslate.viaAi(SOURCE, pieces.get(i), toLang, (result, err) -> {
            results.add(result);
            if (err != null) error[0] = err;
            next(pieces, toLang, results, error, done);
        });
    }

    /** Spec 01's "both failed" message. */
    public static String failureMessage(LuckgramAIError error) {
        return LuckgramTranslate.failureMessage(error);
    }

    /** Reading-translation language (#105) of this chat, else its default, or null. */
    static String readingTarget(int account, long dialogId) {
        return dialogId != 0 ? LuckgramTranslateIn.targetLanguage(account, dialogId) : LuckgramTranslateIn.targetLanguage();
    }
}
