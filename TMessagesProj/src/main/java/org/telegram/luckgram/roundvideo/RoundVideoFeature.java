/*
 * Luckgram: send a gallery video as a round video message (spec 03-round-video).
 */
package org.telegram.luckgram.roundvideo;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;

import java.io.File;
import java.util.Locale;

/**
 * Entry point and switch of the "send gallery video as round video" feature.
 *
 * The switch key is {@link #KEY}; it defaults to on. Until the shared Luckgram settings
 * framework (spec 00) lands, the value is read from the device-wide "luckgram_settings"
 * preferences file under the same key, so wiring it into {@code LuckgramSettings} later is a
 * one-line change in {@link #isEnabled()}.
 */
public final class RoundVideoFeature {

    public static final String KEY = "round_video_from_gallery";
    public static final boolean DEFAULT_VALUE = true;

    /** Longest round video Telegram accepts, ms. */
    public static final long MAX_DURATION_MS = RoundVideoGeometry.MAX_DURATION_MS;

    private RoundVideoFeature() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("luckgram_settings", Context.MODE_PRIVATE);
    }

    public static boolean isEnabled() {
        try {
            return prefs().getBoolean(KEY, DEFAULT_VALUE);
        } catch (Throwable e) {
            return DEFAULT_VALUE;
        }
    }

    public static void setEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY, enabled).apply();
    }

    /**
     * Whether the "send as round video" option may be offered for the given selection.
     * Only a single, real video (no live photo, no paid media) in a normal chat qualifies.
     */
    public static boolean canOffer(ChatActivity chatActivity, MediaController.PhotoEntry entry, int selectedCount, boolean editing, long starsPrice) {
        if (chatActivity == null || entry == null || chatActivity.getParentActivity() == null) {
            return false;
        }
        TLRPC.Chat chat = chatActivity.getCurrentChat();
        return RoundVideoGeometry.canOffer(isEnabled(), editing, selectedCount, entry.isVideo, entry.isLivePhoto(),
                entry.path != null && new File(entry.path).exists(), starsPrice, chatActivity.isInScheduleMode(),
                chat == null || ChatObject.canSendRoundVideo(chat));
    }

    /** Opens the crop screen; {@code onSent} runs after the round video was handed to the chat. */
    public static void open(ChatActivity chatActivity, MediaController.PhotoEntry entry, Runnable onSent) {
        if (chatActivity == null || chatActivity.getParentActivity() == null || entry == null || entry.path == null) {
            return;
        }
        RoundVideoCropDialog dialog = new RoundVideoCropDialog(chatActivity, entry.path, onSent);
        dialog.show();
    }

    // ---- strings (zh / en) ----

    public static final int STR_SEND_AS_ROUND = 0;
    public static final int STR_TITLE = 1;
    public static final int STR_CANCEL = 2;
    public static final int STR_SEND = 3;
    public static final int STR_HINT = 4;
    public static final int STR_CONVERTING = 5;
    public static final int STR_FAILED = 6;
    public static final int STR_SELECTED = 7;
    public static final int STR_TRIM_HINT = 8;

    private static final String[] EN = {
            "Send as Round Video",
            "Round Video",
            "Cancel",
            "Send",
            "Drag to move, pinch to zoom",
            "Converting… %d%%",
            "Couldn't convert this video.",
            "Selected %s",
            "Videos longer than 60 seconds: choose up to 60 seconds",
    };

    private static final String[] ZH = {
            "作为圆形视频发送",
            "圆形视频",
            "取消",
            "发送",
            "拖动调整位置，双指缩放",
            "正在转换… %d%%",
            "无法转换这个视频。",
            "已选 %s",
            "视频超过 60 秒，请选取不超过 60 秒的一段",
    };

    public static boolean isChinese() {
        try {
            String lang = LocaleController.getLocaleStringIso639();
            if (lang != null && lang.toLowerCase(Locale.ROOT).startsWith("zh")) {
                return true;
            }
            boolean userPickedLanguage = MessagesController.getGlobalMainSettings().getString("language", null) != null;
            if (!userPickedLanguage) {
                String sys = LocaleController.getSystemLocaleStringIso639();
                return sys != null && sys.toLowerCase(Locale.ROOT).startsWith("zh");
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    public static String getString(int id) {
        return (isChinese() ? ZH : EN)[id];
    }
}
