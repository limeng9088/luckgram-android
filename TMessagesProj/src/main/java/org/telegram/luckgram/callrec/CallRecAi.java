/*
 * Luckgram: the AI calls call recording needs (spec 04 §4: /v1/transcribe, then /v1/chat task=summary).
 *
 * All AI traffic goes through the shared LuckgramAI client of feature 00 (gateway, device token,
 * consent dialog, error mapping, segmenting); see {@link CallRecLuckgramAi}.
 */
package org.telegram.luckgram.callrec;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;

public interface CallRecAi {

    interface Callback {
        /**
         * Called once on the UI thread. {@code result} null + {@code error} set = failed;
         * both set = partial result (e.g. a long call split into segments where a later segment
         * hit {@code quota_exceeded}); the segments already done must not be dropped.
         * {@code error} is a user-facing message (00 maps the gateway error code).
         */
        void onDone(@Nullable String result, @Nullable String error);
    }

    /**
     * True when the user has already agreed to the AI consent dialog (spec 00 §18) and the client
     * can send requests. Call recording never shows the consent dialog after the call has ended;
     * it asks when the user turns on recording / the AI transcript switch.
     */
    boolean hasConsent();

    /**
     * Shows the shared AI consent dialog if needed. {@code onResult} gets true when the user has
     * agreed (now or earlier). Called on the UI thread.
     */
    void requestConsent(@NonNull android.content.Context context, @NonNull java.util.function.Consumer<Boolean> onResult);

    /**
     * POST /v1/transcribe with the audio file; result is the transcript text. The size limit is
     * the client's business: it splits the file by {@code ai.transcribe_max_mb} from /v1/config
     * (limeng9088/luckgram-specs#53), call recording never applies its own.
     */
    void transcribe(@NonNull File audio, @NonNull Callback callback);

    /** {@code ai.max_input_chars} from /v1/config: the longest text {@link #summarize} accepts. */
    int maxSummaryInputChars();

    /** POST /v1/chat, task=summary; result is 3–6 bullet points summarising the transcript. */
    void summarize(@NonNull String transcript, @NonNull Callback callback);

    /** The LuckgramAI-backed implementation (feature 00). */
    static @NonNull CallRecAi get() {
        return CallRecLuckgramAi.INSTANCE;
    }
}
