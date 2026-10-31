/*
 * Luckgram: send a gallery video as a round video message (spec 03-round-video).
 */
package org.telegram.luckgram.roundvideo;


import org.telegram.messenger.ChatObject;
import org.telegram.messenger.MediaController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;

import java.io.File;

/**
 * Entry point and switch of the "send gallery video as round video" feature.
 *
 * The switch {@link #KEY} is registered in {@code LuckgramFeatures} (group "媒体、通话与存储",
 * default on) and stored by {@code LuckgramSettings} in the same "luckgram_settings" file and key
 * as before, so existing values carry over.
 */
public final class RoundVideoFeature {

    public static final String KEY = org.telegram.luckgram.LuckgramFeatures.ROUND_VIDEO_FROM_GALLERY;

    /** Longest round video Telegram accepts, ms. */
    public static final long MAX_DURATION_MS = RoundVideoGeometry.MAX_DURATION_MS;

    private RoundVideoFeature() {
    }

    /** Registered in {@link org.telegram.luckgram.LuckgramFeatures}; includes the remote switch (S4). */
    public static boolean isEnabled() {
        return org.telegram.luckgram.LuckgramSettings.isEnabled(KEY);
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

    private static final String[] EN = {
            "Send as Round Video",
            "Round Video",
            "Cancel",
            "Send",
            "Drag to move, pinch to zoom",
            "Converting… %d%%",
            "Couldn't convert this video.",
            "Selected %s",
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
    };

    /** The one Luckgram language rule (00, issue #42). */
    public static boolean isChinese() {
        return org.telegram.luckgram.LuckgramStrings.isChinese();
    }

    public static String getString(int id) {
        return (isChinese() ? ZH : EN)[id];
    }
}
