/*
 * Luckgram — keyword folders.
 * Licensed under GNU GPL v. 2 or later, like the rest of this Telegram-based project.
 */

package org.telegram.luckgram.keywordfolders;

import android.content.DialogInterface;
import android.view.View;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.Premium.LimitReachedBottomSheet;
import org.telegram.ui.FilterCreateActivity;

import java.util.ArrayList;

/** Small UI helpers shared by the chat list, folder settings and the editor. */
public final class KeywordFolderUi {

    private KeywordFolderUi() {
    }

    /** Opens the editor for a new keyword folder, or explains the limit. */
    public static void openCreate(BaseFragment fragment) {
        if (fragment == null) {
            return;
        }
        if (!KeywordFolderController.getInstance(fragment.getCurrentAccount()).canCreate()) {
            BulletinFactory.of(fragment).createErrorBulletin(KeywordFolderStrings.limitReached()).show();
            return;
        }
        fragment.presentFragment(new KeywordFolderEditActivity(0));
    }

    /** Number of cloud folders (excluding "All chats" and local keyword folders). */
    public static int cloudFolderCount(MessagesController mc) {
        int count = 0;
        ArrayList<MessagesController.DialogFilter> filters = mc.getDialogFilters();
        for (int i = 0; i < filters.size(); i++) {
            MessagesController.DialogFilter f = filters.get(i);
            if (!f.isDefault() && !f.isKeywordFolder()) {
                count++;
            }
        }
        return count;
    }

    /** "+" at the end of the folder tab bar: new cloud folder or new keyword folder. */
    public static void showAddMenu(BaseFragment fragment, View anchor) {
        if (fragment == null || anchor == null || fragment.getParentActivity() == null) {
            return;
        }
        ItemOptions.makeOptions(fragment, anchor)
                .add(R.drawable.msg_folders, KeywordFolderStrings.newCloudFolder(), () -> {
                    MessagesController mc = fragment.getMessagesController();
                    int count = cloudFolderCount(mc);
                    if (count >= mc.dialogFiltersLimitDefault && !fragment.getUserConfig().isPremium() || count >= mc.dialogFiltersLimitPremium) {
                        fragment.showDialog(new LimitReachedBottomSheet(fragment, fragment.getParentActivity(), LimitReachedBottomSheet.TYPE_FOLDERS, fragment.getCurrentAccount(), null));
                    } else {
                        fragment.presentFragment(new FilterCreateActivity());
                    }
                })
                .add(R.drawable.msg_search, KeywordFolderStrings.menuItem(), () -> openCreate(fragment))
                .setDimAlpha(0x60)
                .show();
    }

    public static void showDeleteAlert(BaseFragment fragment, KeywordFolder folder, Runnable onDeleted) {
        if (fragment == null || folder == null || fragment.getParentActivity() == null) {
            return;
        }
        final int account = fragment.getCurrentAccount();
        final int folderId = folder.id;
        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity());
        builder.setTitle(KeywordFolderStrings.deleteAlertTitle());
        builder.setMessage(KeywordFolderStrings.deleteAlertText());
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.setPositiveButton(LocaleController.getString(R.string.Delete), (dialog, which) -> {
            KeywordFolderController.getInstance(account).deleteFolder(folderId);
            if (onDeleted != null) {
                onDeleted.run();
            }
        });
        AlertDialog alertDialog = builder.create();
        fragment.showDialog(alertDialog);
        View button = alertDialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (button instanceof TextView) {
            ((TextView) button).setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }

    /** Subtitle for the folder list in Settings → Chat Folders. */
    public static String describe(KeywordFolder folder) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < folder.keywords.size(); i++) {
            if (i > 0) {
                sb.append(KeywordFolderStrings.listSeparator());
            }
            sb.append(folder.keywords.get(i));
        }
        return KeywordFolderStrings.filterInfo(sb.toString());
    }
}
