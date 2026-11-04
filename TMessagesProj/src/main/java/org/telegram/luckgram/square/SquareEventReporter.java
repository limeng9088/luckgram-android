package org.telegram.luckgram.square;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramAIError;
import org.telegram.luckgram.LuckgramSettings;
import org.telegram.messenger.AndroidUtilities;

import java.util.List;

/**
 * luckgram-specs#115: sends Square behavior events ({@link SquareEvents}) in batches — every 30 s
 * or 20 events, and when a Square page goes to the background. Failures are silent (retry once,
 * then dropped). The queue is kept on the device until sent and removed when the last account logs
 * out. UI thread only.
 */
public final class SquareEventReporter {

    private SquareEventReporter() {
    }

    private static final String KEY_QUEUE = "square_events_queue";
    private static SquareEvents.Queue queue;
    private static boolean sending;
    private static final Runnable tick = () -> flush(false);

    private static SquareEvents.Queue queue() {
        if (queue == null) queue = new SquareEvents.Queue(LuckgramSettings.getString(KEY_QUEUE, null), System.currentTimeMillis());
        return queue;
    }

    private static void save() {
        LuckgramSettings.setString(KEY_QUEUE, queue().toString());
    }

    public static void add(List<SquareEvents.Event> events) {
        if (events == null || events.isEmpty() || !LuckgramSettings.isEnabled(SquareLogic.FEATURE_KEY)) return;
        queue().addAll(events);
        save();
        flush(false);
    }

    public static void add(SquareEvents.Event event) {
        if (!LuckgramSettings.isEnabled(SquareLogic.FEATURE_KEY)) return;
        queue().add(event);
        save();
        flush(false);
    }

    /** @param now true when leaving the page / going to the background */
    public static void flush(boolean now) {
        AndroidUtilities.cancelRunOnUIThread(tick);
        SquareEvents.Queue q = queue();
        if (q.size() == 0) return;
        if (sending || !(now || q.shouldFlush(System.currentTimeMillis()))) {
            AndroidUtilities.runOnUIThread(tick, SquareEvents.FLUSH_INTERVAL_MS);
            return;
        }
        JSONObject body = q.nextBatch();
        if (body == null) return;
        JSONArray arr = body.optJSONArray("events");
        final int count = arr == null ? 0 : arr.length();
        sending = true;
        LuckgramAI.getInstance().requestJson("POST", SquareEvents.PATH, body, new LuckgramAI.Callback<JSONObject>() {
            @Override
            public void onResult(JSONObject result) {
                sending = false;
                queue().onSent(count, System.currentTimeMillis());
                save();
                if (queue().size() > 0) AndroidUtilities.runOnUIThread(tick, SquareEvents.FLUSH_INTERVAL_MS);
            }

            @Override
            public void onError(LuckgramAIError error) {
                sending = false;
                queue().onFailed(count, error.httpStatus, System.currentTimeMillis());
                save();
                if (queue().size() > 0) AndroidUtilities.runOnUIThread(tick, SquareEvents.FLUSH_INTERVAL_MS);
            }
        });
    }
}
