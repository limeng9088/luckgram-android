package org.telegram.luckgram.announce;

import org.json.JSONObject;
import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramAIError;
import org.telegram.luckgram.LuckgramSettings;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.messenger.NotificationCenter;

import java.util.Collections;
import java.util.List;

/**
 * Chat-list announcements (task S8): fetched with {@code GET /v1/announcements} when the app comes
 * to the foreground (at most every 30 minutes), shown as the hint banner on top of the main chat
 * list (DialogsActivity), one at a time. Closing a banner remembers its ID on this device so it
 * does not come back; an announcement past its end time disappears even before the next fetch.
 * Only device state is sent (platform, version, UI language), so no AI consent is needed.
 */
public final class LuckgramAnnouncements {

    private static final String KEY_DISMISSED = "announcements_dismissed";

    private static List<AnnouncementLogic.Item> items = Collections.emptyList();
    private static long lastSuccessMs;
    private static long lastFailureMs;
    private static boolean inFlight;

    private LuckgramAnnouncements() {
    }

    /** Main thread. */
    public static void refreshIfStale() {
        long now = System.currentTimeMillis();
        if (inFlight || !AnnouncementLogic.shouldRefresh(now, lastSuccessMs, lastFailureMs)) {
            return;
        }
        if (!LuckgramSettings.remoteConfig().getBool("announcements.enabled", true)) {
            setItems(Collections.emptyList());
            return;
        }
        inFlight = true;
        String lang = LuckgramStrings.isChinese() ? "zh-Hans" : "en";
        LuckgramAI.getInstance().requestJson("GET", "/v1/announcements?lang=" + lang, null, new LuckgramAI.Callback<JSONObject>() {
            @Override
            public void onResult(JSONObject result) {
                inFlight = false;
                lastSuccessMs = System.currentTimeMillis();
                lastFailureMs = 0;
                setItems(AnnouncementLogic.parse(result));
            }

            @Override
            public void onError(LuckgramAIError error) {
                inFlight = false;
                lastFailureMs = System.currentTimeMillis();
            }
        });
    }

    private static void setItems(List<AnnouncementLogic.Item> list) {
        items = list;
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.luckgramAnnouncementsChanged);
    }

    /** The banner to show now, or null. */
    public static AnnouncementLogic.Item current() {
        return AnnouncementLogic.pick(items, AnnouncementLogic.parseDismissed(LuckgramSettings.getString(KEY_DISMISSED, "")), System.currentTimeMillis());
    }

    /** The user closed the banner: never show this announcement again on this device. */
    public static void dismiss(String id) {
        List<String> d = AnnouncementLogic.dismiss(AnnouncementLogic.parseDismissed(LuckgramSettings.getString(KEY_DISMISSED, "")), id);
        LuckgramSettings.setString(KEY_DISMISSED, AnnouncementLogic.serializeDismissed(d));
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.luckgramAnnouncementsChanged);
    }

    /** Logout of the last account (LuckgramSettings.onAccountCleared): forget everything. */
    public static void clear() {
        items = Collections.emptyList();
        lastSuccessMs = 0;
        LuckgramSettings.remove(KEY_DISMISSED);
    }
}
