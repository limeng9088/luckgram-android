package org.telegram.luckgram.backup;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.luckgram.accounts.LuckgramAccounts;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.MainTabsActivity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Luckgram 10: Luckgram Features → Account backup. Pick accounts, export them to an encrypted file,
 * or restore from a file. Opened from the login screen it only offers the restore.
 * Same layout and texts as iOS {@code LuckgramBackupController}.
 */
public class LuckgramBackupActivity extends UniversalFragment {

    private static final int ID_ACCOUNT_BASE = 1000;
    private static final int ID_EXPORT = 1;
    private static final int ID_RESTORE = 2;
    private static final int REQUEST_PICK_FILE = 8410;

    /** Wrong-password back-off, shared by every restore in this process: 1, 2, 4… seconds. */
    private static int failedAttempts;
    private static long nextAttemptAt;

    private final boolean fromLogin;
    private final HashSet<Integer> selected = new HashSet<>();
    private boolean selectionInitialized;

    public LuckgramBackupActivity() {
        this(false);
    }

    public LuckgramBackupActivity(boolean fromLogin) {
        this.fromLogin = fromLogin;
    }

    @Override
    protected CharSequence getTitle() {
        return fromLogin ? LuckgramBackup.restoreText() : LuckgramBackup.titleText();
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (!fromLogin) {
            ArrayList<Integer> accounts = LuckgramAccounts.orderedAccounts();
            if (!selectionInitialized) {
                selected.addAll(accounts);
                selectionInitialized = true;
            }
            items.add(UItem.asHeader(LuckgramBackup.selectHeaderText()));
            for (int account : accounts) {
                items.add(UItem.asCheck(ID_ACCOUNT_BASE + account, LuckgramAccounts.displayName(account)).setChecked(selected.contains(account)));
            }
            items.add(UItem.asShadow(null));
            items.add(UItem.asButton(ID_EXPORT, R.drawable.msg_share, LuckgramBackup.exportText()).accent());
            items.add(UItem.asShadow(LuckgramBackup.warningText()));
        }
        items.add(UItem.asButton(ID_RESTORE, R.drawable.msg_download, LuckgramBackup.restoreText()).accent());
        items.add(UItem.asShadow(LuckgramBackup.infoText()));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id >= ID_ACCOUNT_BASE) {
            int account = item.id - ID_ACCOUNT_BASE;
            if (!selected.remove(account)) {
                selected.add(account);
            }
            listView.adapter.update(true);
        } else if (item.id == ID_EXPORT) {
            ArrayList<Integer> accounts = new ArrayList<>();
            for (int account : LuckgramAccounts.orderedAccounts()) {
                if (selected.contains(account)) {
                    accounts.add(account);
                }
            }
            if (accounts.isEmpty()) {
                toast(LuckgramBackup.selectAtLeastOneText());
                return;
            }
            askNewPassword(accounts);
        } else if (item.id == ID_RESTORE) {
            pickFile();
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private void toast(String text) {
        Context context = getContext() != null ? getContext() : getParentActivity();
        if (context != null) {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- export

    private void askNewPassword(ArrayList<Integer> accounts) {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);

        TextView warning = new TextView(context);
        warning.setText(LuckgramBackup.warningText());
        warning.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        warning.setTextColor(Theme.getColor(Theme.key_text_RedBold, getResourceProvider()));
        container.addView(warning, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 4, 24, 8));

        EditTextBoldCursor first = passwordField(context, LuckgramBackup.passwordHintText());
        EditTextBoldCursor second = passwordField(context, LuckgramBackup.passwordRepeatHintText());
        container.addView(first, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 6, 20, 6));
        container.addView(second, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 6, 20, 6));

        AlertDialog.Builder builder = new AlertDialog.Builder(context, getResourceProvider());
        builder.setTitle(LuckgramBackup.passwordTitleText());
        builder.setView(container);
        builder.setPositiveButton(LuckgramBackup.exportText(), (d, w) -> {
            String password = first.getText().toString();
            if (password.length() < LuckgramBackupFormat.MIN_PASSWORD_LENGTH) {
                AndroidUtilities.shakeView(first);
                toast(LuckgramBackup.passwordTooShortText());
                return;
            }
            if (!password.equals(second.getText().toString())) {
                AndroidUtilities.shakeView(second);
                toast(LuckgramBackup.passwordMismatchText());
                return;
            }
            d.dismiss();
            first.setText("");
            second.setText("");
            runExport(accounts, password);
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), (d, w) -> d.dismiss());
        AlertDialog dialog = builder.create();
        dialog.setDismissDialogByButtons(false);
        dialog.setOnShowListener(d -> {
            first.requestFocus();
            AndroidUtilities.showKeyboard(first);
        });
        showDialog(dialog);
    }

    private EditTextBoldCursor passwordField(Context context, String hint) {
        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, getResourceProvider()));
        editText.setHintTextColor(Theme.getColor(Theme.key_groupcreate_hintText, getResourceProvider()));
        editText.setHint(hint);
        editText.setSingleLine(true);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        editText.setTransformationMethod(PasswordTransformationMethod.getInstance());
        editText.setPadding(dp(16), dp(11), dp(16), dp(11));
        editText.setCursorWidth(1.5f);
        editText.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4, getResourceProvider()));
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(22));
        background.setColor(Theme.multAlpha(Theme.getColor(Theme.key_dialogTextBlack, getResourceProvider()), 0.06f));
        editText.setBackground(background);
        return editText;
    }

    private void runExport(ArrayList<Integer> accounts, String password) {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        AlertDialog progress = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(false);
        progress.show();
        LuckgramBackup.export(activity, accounts, password, ok -> {
            progress.dismiss();
            if (!ok) {
                toast(LuckgramBackup.exportFailedText());
            }
        });
    }

    // ---------------------------------------------------------------- restore

    private void pickFile() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQUEST_PICK_FILE);
        } catch (Exception e) {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            startActivityForResult(intent, REQUEST_PICK_FILE);
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_PICK_FILE || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        byte[] file;
        try {
            file = LuckgramBackup.readFile(data.getData());
        } catch (Exception e) {
            toast(LuckgramBackup.notBackupText());
            return;
        }
        askPassword(file);
    }

    private void askPassword(byte[] file) {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        EditTextBoldCursor field = passwordField(context, LuckgramBackup.passwordHintText());
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(field, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 9, 20, 9));

        AlertDialog.Builder builder = new AlertDialog.Builder(context, getResourceProvider());
        builder.setTitle(LuckgramBackup.enterPasswordTitleText());
        builder.setView(container);
        builder.setPositiveButton(LocaleController.getString(R.string.OK), (d, w) -> {
            long wait = nextAttemptAt - SystemClock.elapsedRealtime();
            if (wait > 0) {
                AndroidUtilities.shakeView(field);
                toast(LuckgramBackup.waitText((int) ((wait + 999) / 1000)));
                return;
            }
            String password = field.getText().toString();
            if (password.isEmpty()) {
                AndroidUtilities.shakeView(field);
                return;
            }
            d.dismiss();
            field.setText("");
            decrypt(file, password);
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), (d, w) -> d.dismiss());
        AlertDialog dialog = builder.create();
        dialog.setDismissDialogByButtons(false);
        dialog.setOnShowListener(d -> {
            field.requestFocus();
            AndroidUtilities.showKeyboard(field);
        });
        showDialog(dialog);
    }

    private void decrypt(byte[] file, String password) {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        AlertDialog progress = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(false);
        progress.show();
        Utilities.globalQueue.postRunnable(() -> {
            List<LuckgramBackupFormat.Account> accounts = null;
            int error = 0;
            try {
                accounts = LuckgramBackupFormat.decrypt(file, password);
            } catch (LuckgramBackupFormat.WrongPasswordException e) {
                error = 1;
            } catch (Throwable e) {
                error = 2;
            }
            final List<LuckgramBackupFormat.Account> result = accounts;
            final int errorCode = error;
            AndroidUtilities.runOnUIThread(() -> {
                progress.dismiss();
                if (errorCode == 1) {
                    failedAttempts++;
                    nextAttemptAt = SystemClock.elapsedRealtime() + (1000L << Math.min(failedAttempts - 1, 10));
                    toast(LuckgramBackup.wrongPasswordText());
                    askPassword(file);
                } else if (errorCode == 2 || result == null) {
                    toast(LuckgramBackup.notBackupText());
                } else {
                    failedAttempts = 0;
                    nextAttemptAt = 0;
                    presentFragment(new RestoreActivity(result, fromLogin ? currentAccount : -1));
                }
            });
        });
    }

    // ---------------------------------------------------------------- choose accounts to restore

    /** The accounts found in a backup file; keeps the decrypted keys in memory until it closes. */
    public static class RestoreActivity extends UniversalFragment {

        private static final int ID_ITEM_BASE = 1000;
        private static final int ID_RESTORE = 1;

        private final List<LuckgramBackupFormat.Account> accounts;
        private final int preferredSlot;
        private final HashSet<Integer> selected = new HashSet<>();
        private boolean restoring;

        public RestoreActivity(List<LuckgramBackupFormat.Account> accounts, int preferredSlot) {
            this.accounts = accounts;
            this.preferredSlot = preferredSlot;
            for (int i = 0; i < accounts.size(); i++) {
                if (!LuckgramBackup.isLoggedIn(accounts.get(i).userId)) {
                    selected.add(i);
                }
            }
        }

        @Override
        public void onFragmentDestroy() {
            super.onFragmentDestroy();
            if (!restoring) {
                for (LuckgramBackupFormat.Account account : accounts) {
                    account.wipe();
                }
            }
        }

        @Override
        protected CharSequence getTitle() {
            return LuckgramBackup.restoreText();
        }

        @Override
        protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
            items.add(UItem.asHeader(LuckgramBackup.chooseAccountsText()));
            for (int i = 0; i < accounts.size(); i++) {
                LuckgramBackupFormat.Account account = accounts.get(i);
                boolean loggedIn = LuckgramBackup.isLoggedIn(account.userId);
                String label = LuckgramBackup.accountLabel(account.userId, account.name);
                if (loggedIn) {
                    label += " · " + LuckgramBackup.alreadyLoggedInText();
                }
                items.add(UItem.asCheck(ID_ITEM_BASE + i, label).setChecked(!loggedIn && selected.contains(i)).setEnabled(!loggedIn));
            }
            items.add(UItem.asShadow(null));
            items.add(UItem.asButton(ID_RESTORE, R.drawable.msg_download, LuckgramBackup.restoreButtonText()).accent());
            items.add(UItem.asShadow(null));
        }

        @Override
        protected void onClick(UItem item, View view, int position, float x, float y) {
            if (restoring) {
                return;
            }
            if (item.id >= ID_ITEM_BASE) {
                int index = item.id - ID_ITEM_BASE;
                if (LuckgramBackup.isLoggedIn(accounts.get(index).userId)) {
                    Toast.makeText(getParentActivity(), LuckgramBackup.alreadyLoggedInText(), Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!selected.remove(index)) {
                    selected.add(index);
                }
                listView.adapter.update(true);
            } else if (item.id == ID_RESTORE) {
                restore();
            }
        }

        @Override
        protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
            return false;
        }

        private void restore() {
            ArrayList<LuckgramBackupFormat.Account> chosen = new ArrayList<>();
            for (int i = 0; i < accounts.size(); i++) {
                if (selected.contains(i) && !LuckgramBackup.isLoggedIn(accounts.get(i).userId)) {
                    chosen.add(accounts.get(i));
                }
            }
            if (chosen.isEmpty()) {
                Toast.makeText(getParentActivity(), LuckgramBackup.selectAtLeastOneText(), Toast.LENGTH_SHORT).show();
                return;
            }
            restoring = true;
            // Keys that are not restored are wiped here; restore() wipes the others.
            for (LuckgramBackupFormat.Account account : accounts) {
                if (!chosen.contains(account)) {
                    account.wipe();
                }
            }
            Activity activity = getParentActivity();
            AlertDialog progress = activity != null ? new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER) : null;
            if (progress != null) {
                progress.setCanCancel(false);
                progress.show();
            }
            LuckgramBackup.restore(chosen, preferredSlot, result -> {
                if (progress != null) {
                    progress.dismiss();
                }
                String text = result.noRoom > 0 && result.restored == 0 ? LuckgramBackup.noRoomText() : LuckgramBackup.resultText(result.restored, result.skipped, result.failed + result.noRoom);
                Activity a = getParentActivity();
                if (a != null) {
                    Toast.makeText(a, text, Toast.LENGTH_LONG).show();
                }
                if (result.firstAccount >= 0) {
                    openAccount(result.firstAccount);
                } else {
                    restoring = false;
                    finishFragment();
                }
            });
        }

        private void openAccount(int account) {
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.mainUserInfoChanged);
            if (account != UserConfig.selectedAccount && getParentActivity() instanceof LaunchActivity) {
                ((LaunchActivity) getParentActivity()).switchToAccount(account, true, obj -> {
                    MainTabsActivity mainTabsActivity = new MainTabsActivity();
                    mainTabsActivity.prepareDialogsActivity(new Bundle());
                    return mainTabsActivity;
                });
                return;
            }
            // Restored into the account of the login screen: replace the login stack.
            ArrayList<BaseFragment> old = new ArrayList<>(getParentLayout().getFragmentStack());
            MainTabsActivity mainTabsActivity = new MainTabsActivity();
            mainTabsActivity.prepareDialogsActivity(new Bundle());
            presentFragment(mainTabsActivity, false, true);
            for (BaseFragment fragment : old) {
                fragment.removeSelfFromStack();
            }
        }
    }
}
