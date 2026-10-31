/*
 * Luckgram — save a message to Saved Messages in one tap (spec 15).
 * Licensed under GNU GPL v. 2 or later, like the rest of this Telegram-based project.
 */

package org.telegram.luckgram.savedirect;

import org.telegram.luckgram.LuckgramSettings;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;

/**
 * "Save" forwards messages to the account's own chat (Saved Messages) exactly like a normal
 * forward, so the original sender is kept. Same rules and wording as iOS {@code LuckgramSaveToSaved}.
 */
public final class SaveToSaved {

    /** Feature switch, registered in {@link org.telegram.luckgram.LuckgramFeatures} (default on). */
    public static final String SETTING_KEY = "save_to_saved";

    private SaveToSaved() {
    }

    public static boolean isEnabled() {
        return LuckgramSettings.isEnabled(SETTING_KEY);
    }

    /**
     * Whether "Save" may be offered in this chat at all: switch on, not Saved Messages itself,
     * not a secret chat, and the chat does not forbid forwarding (protected content).
     */
    public static boolean availableInChat(int account, long dialogId, TLRPC.Chat chat, TLRPC.UserFull userFull) {
        if (!isEnabled() || dialogId == 0 || DialogObject.isEncryptedDialog(dialogId)) {
            return false;
        }
        if (dialogId == UserConfig.getInstance(account).getClientUserId()) {
            return false;
        }
        MessagesController mc = MessagesController.getInstance(account);
        return chat != null ? !mc.isChatNoForwards(chat) : !mc.isUserNoForwards(userFull);
    }

    /** Per message: a real, sent message that is not itself protected. */
    public static boolean canSave(MessageObject message) {
        return message != null && message.messageOwner != null && message.getId() > 0
            && !message.messageOwner.noforwards && !message.isSponsored();
    }

    /**
     * Forwards {@code messages} (any order) to Saved Messages, oldest first, and returns how many
     * were sent. Telegram splits large forwards into 100-message requests itself.
     */
    public static int save(int account, ArrayList<MessageObject> messages) {
        ArrayList<MessageObject> list = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (canSave(messages.get(i))) {
                list.add(messages.get(i));
            }
        }
        if (list.isEmpty()) {
            return 0;
        }
        Collections.sort(list, (a, b) -> Integer.compare(a.getId(), b.getId()));
        long self = UserConfig.getInstance(account).getClientUserId();
        SendMessagesHelper.getInstance(account).sendMessage(list, self, false, false, true, 0, 0);
        return list.size();
    }

    // region strings (Chinese + English, same wording as iOS)

    public static String menuTitle() {
        return LuckgramStrings.get("收藏", "Save");
    }

    public static String savedText() {
        return LuckgramStrings.get("已保存到收藏夹", "Saved to Saved Messages");
    }

    public static String viewText() {
        return LuckgramStrings.get("查看", "View");
    }

    // endregion
}
