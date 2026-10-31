package org.telegram.luckgram.accounts;

import android.view.View;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import org.telegram.ui.SettingsActivity;

import java.util.ArrayList;

/**
 * Luckgram 09: Settings → Manage accounts. Account order (long-press and drag), a local name per
 * account and a notification switch per account. Same layout as iOS LuckgramAccountsScreen.
 */
public class LuckgramAccountsActivity extends UniversalFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int ID_NOTIFY_BASE = 1000;
    private static final int ALIAS_MAX_LENGTH = 32;

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.luckgramSettingsChanged);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.luckgramSettingsChanged);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.luckgramSettingsChanged && listView != null && !listView.isReordering()) {
            listView.adapter.update(true);
        }
    }

    @Override
    public android.view.View createView(android.content.Context context) {
        View view = super.createView(context);
        listView.listenReorder(this::onReordered);
        listView.allowReorder(true);
        return view;
    }

    @Override
    protected CharSequence getTitle() {
        return LuckgramAccounts.manageText();
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        ArrayList<Integer> accounts = LuckgramAccounts.orderedAccounts();
        items.add(UItem.asHeader(LocaleController.getString(R.string.SettingsAccounts)));
        adapter.reorderSectionStart();
        for (int i = 0; i < accounts.size(); i++) {
            items.add(SettingsActivity.AccountCell.Factory.of(i, accounts.get(i)));
        }
        adapter.reorderSectionEnd();
        items.add(UItem.asShadow(LuckgramAccounts.manageInfoText()));

        items.add(UItem.asHeader(LuckgramAccounts.notificationsText()));
        for (int account : accounts) {
            items.add(UItem.asCheck(ID_NOTIFY_BASE + account, LuckgramAccounts.displayName(account))
                    .setChecked(LuckgramAccounts.isNotificationsEnabled(account)));
        }
        items.add(UItem.asShadow(LuckgramAccounts.notificationsInfoText()));
    }

    private void onReordered(int sectionId, ArrayList<UItem> reordered) {
        ArrayList<Integer> order = new ArrayList<>();
        for (UItem item : reordered) {
            if (item.instanceOf(SettingsActivity.AccountCell.Factory.class)) {
                order.add(item.intValue);
            }
        }
        LuckgramAccounts.setOrder(order);
        // Notification rows below follow the same order.
        if (listView != null) {
            listView.post(() -> listView.adapter.update(true));
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.instanceOf(SettingsActivity.AccountCell.Factory.class)) {
            final int account = item.intValue;
            final String current = LuckgramAccounts.getAlias(account);
            ItemOptions options = ItemOptions.makeOptions(this, view)
                    .add(R.drawable.msg_edit, LuckgramAccounts.editAliasText(), () -> editAlias(account, current));
            if (current != null) {
                options.add(R.drawable.msg_delete, LuckgramAccounts.clearAliasText(), true, () -> {
                    LuckgramAccounts.setAlias(account, null);
                    listView.adapter.update(true);
                });
            }
            options.setScrimViewBackground(listView.getClipBackground(view)).show();
        } else if (item.id >= ID_NOTIFY_BASE) {
            final int account = item.id - ID_NOTIFY_BASE;
            boolean enabled = !LuckgramAccounts.isNotificationsEnabled(account);
            LuckgramAccounts.setNotificationsEnabled(account, enabled);
            if (!enabled) {
                org.telegram.messenger.NotificationsController.getInstance(account).luckgramDismissNotifications();
            }
            listView.adapter.update(true);
        }
    }

    private void editAlias(int account, String current) {
        AlertsCreator.createSimpleTextInputAlert(getContext(), this, LuckgramAccounts.aliasText(), null,
                LuckgramAccounts.aliasHintText(), current, ALIAS_MAX_LENGTH,
                LocaleController.getString(R.string.Save), getResourceProvider(), text -> {
                    LuckgramAccounts.setAlias(account, text);
                    if (listView != null) {
                        listView.adapter.update(true);
                    }
                });
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
