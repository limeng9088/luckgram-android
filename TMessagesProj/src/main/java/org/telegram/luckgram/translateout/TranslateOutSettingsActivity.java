/*
 * Luckgram — specs#104 §3/§6: settings of translate before sending (secondary page of the
 * translate_out switch): preview on/off and the chats where 「发送翻译」 is on.
 * Licensed under GNU GPL v. 2 or later.
 */
package org.telegram.luckgram.translateout;

import android.view.View;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

public class TranslateOutSettingsActivity extends UniversalFragment {

    private static final int ID_PREVIEW = 1;
    private static final int ID_OFF_ALL = 2;
    private static final int ID_CHAT_BASE = 1000;

    private final ArrayList<Long> chats = new ArrayList<>();

    @Override
    protected CharSequence getTitle() {
        return TranslateOutStrings.settingsTitle();
    }

    @Override
    public void onResume() {
        super.onResume();
        update();
    }

    private void update() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private String chatName(long dialogId) {
        final MessagesController mc = getMessagesController();
        if (DialogObject.isUserDialog(dialogId)) {
            final TLRPC.User user = mc.getUser(dialogId);
            return user != null ? UserObject.getUserName(user) : String.valueOf(dialogId);
        }
        final TLRPC.Chat chat = mc.getChat(-dialogId);
        return chat != null ? chat.title : String.valueOf(dialogId);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asCheck(ID_PREVIEW, TranslateOutStrings.preview()).setChecked(TranslateOutController.previewEnabled()));
        items.add(UItem.asShadow(TranslateOutStrings.previewInfo()));

        chats.clear();
        chats.addAll(TranslateOutController.chatsOn(currentAccount));
        items.add(UItem.asHeader(TranslateOutStrings.chatsOn()));
        if (chats.isEmpty()) {
            items.add(UItem.asShadow(TranslateOutStrings.noChatsOn()));
            return;
        }
        for (int i = 0; i < chats.size(); i++) {
            final long dialogId = chats.get(i);
            final String lang = TranslateOutController.getSavedLanguage(currentAccount, dialogId);
            items.add(UItem.asButton(ID_CHAT_BASE + i, chatName(dialogId), TranslateOutController.languageName(lang)));
        }
        items.add(UItem.asButton(ID_OFF_ALL, TranslateOutStrings.turnOffAll()).red());
        items.add(UItem.asShadow(TranslateOutStrings.capsuleHint()));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_PREVIEW) {
            TranslateOutController.setPreviewEnabled(!TranslateOutController.previewEnabled());
            update();
        } else if (item.id == ID_OFF_ALL) {
            for (long dialogId : new ArrayList<>(chats)) {
                TranslateOutController.setChatOn(currentAccount, dialogId, false);
            }
            update();
        } else if (item.id >= ID_CHAT_BASE && item.id - ID_CHAT_BASE < chats.size()) {
            // one chat: turn it off here (its language stays saved for next time)
            TranslateOutController.setChatOn(currentAccount, chats.get(item.id - ID_CHAT_BASE), false);
            update();
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
