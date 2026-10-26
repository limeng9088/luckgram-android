/*
 * Luckgram: CallRecAi on top of 00's LuckgramAI client.
 */
package org.telegram.luckgram.callrec;

import android.content.Context;

import androidx.annotation.NonNull;

import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramAIError;
import org.telegram.luckgram.LuckgramSettings;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.function.Consumer;

final class CallRecLuckgramAi implements CallRecAi {

    static final CallRecLuckgramAi INSTANCE = new CallRecLuckgramAi();

    /** Same default as the server's ai.max_input_chars. */
    private static final long DEFAULT_MAX_INPUT_CHARS = 60_000;

    private CallRecLuckgramAi() {
    }

    @Override
    public boolean hasConsent() {
        return LuckgramAI.getInstance().hasConsent();
    }

    @Override
    public void requestConsent(@NonNull Context context, @NonNull Consumer<Boolean> onResult) {
        LuckgramAI.getInstance().requestConsent(context, onResult::accept);
    }

    @Override
    public void transcribe(@NonNull File audio, @NonNull Callback callback) {
        final byte[] data;
        try {
            data = readAll(audio);
        } catch (IOException e) {
            FileLog.e(e);
            callback.onDone(null, new LuckgramAIError(LuckgramAIError.UPSTREAM_UNAVAILABLE, 0).getLocalizedMessage());
            return;
        }
        // Consent was given before the call (settings); no dialog can be shown after hang-up,
        // and LuckgramAI does not ask again once agreed.
        LuckgramAI.getInstance().transcribe(ApplicationLoader.applicationContext, data, "audio/ogg", null, new LuckgramAI.Callback<LuckgramAI.Transcription>() {
            @Override
            public void onResult(LuckgramAI.Transcription t) {
                // #55: a partial transcript keeps the finished segments and says why the rest is missing.
                callback.onDone(t.text, t.partial && t.error != null ? t.error.getLocalizedMessage() : null);
            }

            @Override
            public void onError(LuckgramAIError error) {
                callback.onDone(null, error.getLocalizedMessage());
            }
        });
    }

    @Override
    public int maxSummaryInputChars() {
        return (int) Math.min(Integer.MAX_VALUE, LuckgramSettings.remoteLong("ai.max_input_chars", DEFAULT_MAX_INPUT_CHARS));
    }

    @Override
    public void summarize(@NonNull String transcript, @NonNull Callback callback) {
        LuckgramAI.getInstance().chat(ApplicationLoader.applicationContext, LuckgramAI.TASK_SUMMARY,
                Collections.singletonList(LuckgramAI.Message.user(transcript)), false, new LuckgramAI.ChatListener() {
            @Override
            public void onDelta(String delta, String text) {
            }

            @Override
            public void onComplete(String text) {
                callback.onDone(text, null);
            }

            @Override
            public void onError(LuckgramAIError error) {
                callback.onDone(null, error.getLocalizedMessage());
            }
        });
    }

    private static byte[] readAll(File file) throws IOException {
        final long length = file.length();
        if (length > Integer.MAX_VALUE) {
            throw new IOException("too large");
        }
        final byte[] data = new byte[(int) length];
        try (FileInputStream in = new FileInputStream(file)) {
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0) {
                    throw new IOException("short read");
                }
                off += n;
            }
        }
        return data;
    }
}
