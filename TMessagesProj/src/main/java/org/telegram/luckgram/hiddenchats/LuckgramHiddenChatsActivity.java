package org.telegram.luckgram.hiddenchats;

import android.view.View;
import android.widget.Toast;

import org.telegram.messenger.R;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/**
 * Luckgram 12: Luckgram Features → Hidden chats. Passcode before opening, notifications for hidden
 * chats, and a way in. Same layout as iOS.
 */
public class LuckgramHiddenChatsActivity extends UniversalFragment {

    private static final int ID_OPEN = 1;
    private static final int ID_LOCK = 2;
    private static final int ID_NOTIFY = 3;

    @Override
    protected CharSequence getTitle() {
        return LuckgramHiddenChats.titleText();
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asButton(ID_OPEN, R.drawable.msg_archive_hide, LuckgramHiddenChats.openText()).accent());
        items.add(UItem.asShadow(LuckgramHiddenChats.emptyText()));
        items.add(UItem.asCheck(ID_LOCK, LuckgramHiddenChats.lockText()).setChecked(LuckgramHiddenChats.isLockEnabled() && LuckgramHiddenChats.isPasscodeOn()).setEnabled(LuckgramHiddenChats.isPasscodeOn()));
        items.add(UItem.asShadow(LuckgramHiddenChats.lockInfoText()));
        items.add(UItem.asCheck(ID_NOTIFY, LuckgramHiddenChats.notifyText()).setChecked(LuckgramHiddenChats.isNotifyEnabled()));
        items.add(UItem.asShadow(LuckgramHiddenChats.notifyInfoText()));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_OPEN) {
            LuckgramHiddenChats.open(this);
        } else if (item.id == ID_LOCK) {
            if (!LuckgramHiddenChats.isPasscodeOn()) {
                Toast.makeText(getParentActivity(), LuckgramHiddenChats.lockInfoText(), Toast.LENGTH_SHORT).show();
                return;
            }
            LuckgramHiddenChats.setLockEnabled(!LuckgramHiddenChats.isLockEnabled());
            listView.adapter.update(true);
        } else if (item.id == ID_NOTIFY) {
            LuckgramHiddenChats.setNotifyEnabled(!LuckgramHiddenChats.isNotifyEnabled());
            listView.adapter.update(true);
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
