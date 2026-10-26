/*
 * Luckgram: the AI calls call recording needs (spec 04 §4: /v1/transcribe, then /v1/chat task=summary).
 *
 * All AI traffic must go through the shared LuckgramAI client of feature 00 (gateway, device token,
 * consent dialog, error mapping). That client registers an implementation here; until it does,
 * {@link #get()} returns null and recordings are saved as audio only.
 */
package org.telegram.luckgram.callrec;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;

public interface CallRecAi {

    /** Gateway upload limit for /v1/transcribe (spec 00 §14). */
    long MAX_TRANSCRIBE_BYTES = 25L * 1024 * 1024;

    interface Callback {
        /** Exactly one of result / error is non-null. Called on the UI thread. */
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

    /** POST /v1/transcribe with the audio file; result is the transcript text. */
    void transcribe(@NonNull File audio, @NonNull Callback callback);

    /** POST /v1/chat, task=summary; result is 3–6 bullet points summarising the transcript. */
    void summarize(@NonNull String transcript, @NonNull Callback callback);

    final class Registry {
        private static volatile CallRecAi instance;

        private Registry() {
        }

        /** Called once by the LuckgramAI client (feature 00). */
        public static void register(@Nullable CallRecAi ai) {
            instance = ai;
        }
    }

    static @Nullable CallRecAi get() {
        return Registry.instance;
    }
}
