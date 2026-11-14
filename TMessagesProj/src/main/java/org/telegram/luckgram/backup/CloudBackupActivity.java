package org.telegram.luckgram.backup;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramAIError;
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
import org.telegram.ui.Cells.CheckBoxCell;
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
 * Luckgram 32: Luckgram Features → Luckgram Pro → Cloud backup (Pro). Pick accounts, back them up
 * to the cloud (encrypted on this device), restore, delete, weekly automatic backup. There is no
 * file export / import. Same layout and texts as iOS {@code luckgramCloudBackupController}.
 *
 * <p>{@link #startRestore} is also used by the login screen ("Restore from cloud").
 */
public class CloudBackupActivity extends UniversalFragment {

    private static final int ID_ACCOUNT_BASE = 1000;
    private static final int ID_PRO = 1;
    private static final int ID_UPLOAD = 2;
    private static final int ID_RESTORE = 3;
    private static final int ID_DELETE = 4;
    private static final int ID_AUTO = 5;

    private static final int CLOUD_LOADING = 0;
    private static final int CLOUD_NONE = 1;
    private static final int CLOUD_READY = 2;
    private static final int CLOUD_ERROR = 3;
    private int cloudState = CLOUD_LOADING;
    private CloudBackupLogic.Meta cloudMeta;
    private String cloudError;
    private boolean cloudRequested;

    /** Wrong-password back-off, shared by every restore in this process: 1, 2, 4… seconds. */
    private static int failedAttempts;
    private static long nextAttemptAt;

    private final HashSet<Integer> deselected = new HashSet<>();

    @Override
    protected CharSequence getTitle() {
        return CloudBackup.headerText();
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (!LuckgramAccounts.isPro()) {
            items.add(UItem.asHeader(CloudBackup.headerText()));
            items.add(UItem.asButton(ID_PRO, R.drawable.msg_settings_premium, CloudBackup.uploadText(), "Pro"));
            items.add(UItem.asShadow(CloudBackup.proOnlyText() + "\n\n" + CloudBackup.cloudWarningText()));
            return;
        }
        if (!cloudRequested) {
            cloudRequested = true;
            loadCloud();
        }
        items.add(UItem.asHeader(LuckgramBackup.selectHeaderText()));
        for (int account : LuckgramAccounts.orderedAccounts()) {
            items.add(UItem.asCheck(ID_ACCOUNT_BASE + account, LuckgramAccounts.displayName(account)).setChecked(!deselected.contains(account)));
        }
        items.add(UItem.asShadow(LuckgramBackup.infoText()));
        items.add(UItem.asButton(ID_UPLOAD, R.drawable.msg_archive, CloudBackup.uploadText()).accent());
        if (cloudState == CLOUD_READY) {
            items.add(UItem.asButton(ID_RESTORE, R.drawable.msg_download, CloudBackup.restoreText()).accent());
            items.add(UItem.asButton(ID_DELETE, R.drawable.msg_delete, CloudBackup.deleteText()).red());
        }
        String status;
        if (cloudState == CLOUD_READY && cloudMeta != null) {
            status = CloudBackup.statusText(cloudMeta);
        } else if (cloudState == CLOUD_NONE) {
            status = CloudBackup.noneText();
        } else if (cloudState == CLOUD_ERROR) {
            status = cloudError;
        } else {
            status = CloudBackup.loadingText();
        }
        items.add(UItem.asShadow(status + "\n\n" + LuckgramBackup.warningText() + "\n" + CloudBackup.cloudWarningText()));
        items.add(UItem.asSwitch(ID_AUTO, CloudBackup.autoText()).setChecked(CloudBackup.isAutoEnabled()));
        items.add(UItem.asShadow(CloudBackup.autoInfoText()));
    }

    private void loadCloud() {
        cloudState = CLOUD_LOADING;
        CloudBackup.fetch(currentAccount, new LuckgramAI.Callback<CloudBackupLogic.Meta>() {
            @Override
            public void onResult(CloudBackupLogic.Meta result) {
                cloudMeta = result;
                cloudState = result != null ? CLOUD_READY : CLOUD_NONE;
                updateList();
            }

            @Override
            public void onError(LuckgramAIError error) {
                cloudError = CloudBackup.errorText(error);
                cloudState = CLOUD_ERROR;
                updateList();
            }
        });
    }

    private void updateList() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id >= ID_ACCOUNT_BASE) {
            int account = item.id - ID_ACCOUNT_BASE;
            if (!deselected.remove(account)) {
                deselected.add(account);
            }
            updateList();
        } else if (item.id == ID_PRO) {
            LuckgramAccounts.showUpgrade(this, CloudBackup.proOnlyText());
        } else if (item.id == ID_UPLOAD) {
            ArrayList<Integer> accounts = selectedAccounts();
            if (accounts.isEmpty()) {
                toast(getParentActivity(), LuckgramBackup.selectAtLeastOneText());
                return;
            }
            askNewPassword(accounts, false);
        } else if (item.id == ID_RESTORE) {
            startRestore(this, currentAccount, -1);
        } else if (item.id == ID_DELETE) {
            confirmDelete();
        } else if (item.id == ID_AUTO) {
            if (CloudBackup.isAutoEnabled()) {
                CloudBackup.setAuto(null);
                updateList();
            } else {
                askNewPassword(LuckgramAccounts.orderedAccounts(), true);
            }
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private ArrayList<Integer> selectedAccounts() {
        ArrayList<Integer> accounts = new ArrayList<>();
        for (int account : LuckgramAccounts.orderedAccounts()) {
            if (!deselected.contains(account)) {
                accounts.add(account);
            }
        }
        return accounts;
    }

    // ---------------------------------------------------------------- upload / delete

    /**
     * New password twice, with "remember on this device" (weekly backup). {@code autoOnly}: turning
     * on the weekly backup, which needs the box and backs up every account.
     */
    private void askNewPassword(ArrayList<Integer> accounts, boolean autoOnly) {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);

        TextView warning = new TextView(context);
        warning.setText(LuckgramBackup.warningText() + "\n\n" + CloudBackup.cloudWarningText());
        warning.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        warning.setTextColor(Theme.getColor(Theme.key_text_RedBold, getResourceProvider()));
        container.addView(warning, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 4, 24, 8));

        EditTextBoldCursor first = passwordField(context, this, LuckgramBackup.passwordHintText());
        EditTextBoldCursor second = passwordField(context, this, LuckgramBackup.passwordRepeatHintText());
        container.addView(first, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 6, 20, 6));
        container.addView(second, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 6, 20, 6));
        TextView error = passwordError(context, this, first, second);
        container.addView(error, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 2, 24, 2));
        CheckBoxCell remember = new CheckBoxCell(context, CheckBoxCell.TYPE_CHECK_BOX_DEFAULT, getResourceProvider());
        remember.setBackground(Theme.getSelectorDrawable(false));
        remember.setText(CloudBackup.rememberText(), "", autoOnly || CloudBackup.isAutoEnabled(), false);
        remember.setOnClickListener(v -> remember.setChecked(!remember.isChecked(), true));
        container.addView(remember, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 8, 4, 8, 0));

        AlertDialog.Builder builder = new AlertDialog.Builder(context, getResourceProvider());
        builder.setTitle(LuckgramBackup.passwordTitleText());
        builder.setView(container);
        builder.setPositiveButton(CloudBackup.uploadText(), (d, w) -> {
            String password = first.getText().toString();
            if (password.length() < LuckgramBackupFormat.MIN_PASSWORD_LENGTH) {
                showPasswordError(error, first, LuckgramBackup.passwordTooShortText());
                return;
            }
            if (!password.equals(second.getText().toString())) {
                showPasswordError(error, second, LuckgramBackup.passwordMismatchText());
                return;
            }
            boolean keep = remember.isChecked();
            if (autoOnly && !keep) {
                AndroidUtilities.shakeView(remember);
                return;
            }
            d.dismiss();
            first.setText("");
            second.setText("");
            if (!keep && CloudBackup.isAutoEnabled()) {
                CloudBackup.setAuto(null);
            }
            runUpload(accounts, password, keep);
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), (d, w) -> d.dismiss());
        AlertDialog dialog = builder.create();
        dialog.setDismissDialogByButtons(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnShowListener(d -> {
            first.requestFocus();
            AndroidUtilities.showKeyboard(first);
        });
        showDialog(dialog);
    }

    /** Encrypts on this device and uploads only the ciphertext. {@code remember}: keep the password for the weekly backup. */
    private void runUpload(ArrayList<Integer> accounts, String password, boolean remember) {
        AlertDialog progress = showProgress(getParentActivity());
        CloudBackup.upload(currentAccount, accounts, password, new LuckgramAI.Callback<CloudBackupLogic.Meta>() {
            @Override
            public void onResult(CloudBackupLogic.Meta result) {
                dismiss(progress);
                if (remember) {
                    CloudBackup.setAuto(password);
                }
                CloudBackup.markUploaded();
                cloudMeta = result;
                cloudState = CLOUD_READY;
                toast(getParentActivity(), CloudBackup.uploadedText());
                updateList();
            }

            @Override
            public void onError(LuckgramAIError error) {
                dismiss(progress);
                showError(CloudBackupActivity.this, error);
            }
        });
    }

    private void confirmDelete() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context, getResourceProvider());
        builder.setTitle(CloudBackup.deleteText());
        builder.setMessage(CloudBackup.deleteConfirmText());
        builder.setPositiveButton(LocaleController.getString(R.string.Delete), (d, w) -> {
            AlertDialog progress = showProgress(getParentActivity());
            CloudBackup.delete(currentAccount, new LuckgramAI.Callback<Boolean>() {
                @Override
                public void onResult(Boolean result) {
                    dismiss(progress);
                    cloudMeta = null;
                    cloudState = CLOUD_NONE;
                    toast(getParentActivity(), CloudBackup.deletedText());
                    updateList();
                }

                @Override
                public void onError(LuckgramAIError error) {
                    dismiss(progress);
                    showError(CloudBackupActivity.this, error);
                }
            });
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        TextView button = (TextView) dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (button != null) {
            button.setTextColor(Theme.getColor(Theme.key_text_RedBold, getResourceProvider()));
        }
    }

    // ---------------------------------------------------------------- restore (page and login screen)

    /**
     * "Restore from cloud": download the ciphertext with {@code account} (any logged-in account,
     * linked to the same Telegram user, S6) → password → pick accounts → restore.
     * {@code preferredSlot}: the empty account of the login screen, or -1.
     */
    public static void startRestore(BaseFragment host, int account, int preferredSlot) {
        Activity activity = host.getParentActivity();
        if (account < 0) {
            toast(activity, CloudBackup.needAccountText());
            return;
        }
        AlertDialog progress = showProgress(activity);
        CloudBackup.download(account, new LuckgramAI.Callback<byte[]>() {
            @Override
            public void onResult(byte[] file) {
                dismiss(progress);
                if (file == null) {
                    toast(host.getParentActivity(), CloudBackup.noneText());
                    return;
                }
                askPassword(host, file, preferredSlot);
            }

            @Override
            public void onError(LuckgramAIError error) {
                dismiss(progress);
                showError(host, error);
            }
        });
    }

    /** Login screen: restore with any account already logged in on this device. */
    public static void startRestoreFromLogin(BaseFragment loginFragment) {
        startRestore(loginFragment, CloudBackup.anyLoggedInAccount(), loginFragment.getCurrentAccount());
    }

    /** Password → decrypt → choose accounts → restore; shared by the file backup page ({@link LuckgramBackupActivity}). */
    static void askPassword(BaseFragment host, byte[] file, int preferredSlot) {
        Context context = host.getParentActivity();
        if (context == null) {
            return;
        }
        EditTextBoldCursor field = passwordField(context, host, LuckgramBackup.passwordHintText());
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(field, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 9, 20, 9));

        AlertDialog.Builder builder = new AlertDialog.Builder(context, host.getResourceProvider());
        builder.setTitle(LuckgramBackup.enterPasswordTitleText());
        builder.setView(container);
        builder.setPositiveButton(LocaleController.getString(R.string.OK), (d, w) -> {
            long wait = nextAttemptAt - SystemClock.elapsedRealtime();
            if (wait > 0) {
                AndroidUtilities.shakeView(field);
                toast(context, LuckgramBackup.waitText((int) ((wait + 999) / 1000)));
                return;
            }
            String password = field.getText().toString();
            if (password.isEmpty()) {
                AndroidUtilities.shakeView(field);
                return;
            }
            d.dismiss();
            field.setText("");
            decrypt(host, file, password, preferredSlot);
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), (d, w) -> d.dismiss());
        AlertDialog dialog = builder.create();
        dialog.setDismissDialogByButtons(false);
        dialog.setOnShowListener(d -> {
            field.requestFocus();
            AndroidUtilities.showKeyboard(field);
        });
        host.showDialog(dialog);
    }

    private static void decrypt(BaseFragment host, byte[] file, String password, int preferredSlot) {
        AlertDialog progress = showProgress(host.getParentActivity());
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
                dismiss(progress);
                if (errorCode == 1) {
                    failedAttempts++;
                    nextAttemptAt = SystemClock.elapsedRealtime() + (1000L << Math.min(failedAttempts - 1, 10));
                    toast(host.getParentActivity(), LuckgramBackup.wrongPasswordText());
                    askPassword(host, file, preferredSlot);
                } else if (errorCode == 2 || result == null) {
                    toast(host.getParentActivity(), LuckgramBackup.notBackupText());
                } else {
                    failedAttempts = 0;
                    nextAttemptAt = 0;
                    host.presentFragment(new RestoreActivity(result, preferredSlot));
                }
            });
        });
    }

    // ---------------------------------------------------------------- helpers

    private static void showError(BaseFragment host, LuckgramAIError error) {
        if (error != null && "not_pro".equals(error.code) && host.getParentActivity() != null) {
            LuckgramAccounts.showUpgrade(host, CloudBackup.errorText(error));
            return;
        }
        toast(host.getParentActivity(), CloudBackup.errorText(error));
    }

    static AlertDialog showProgress(Activity activity) {
        if (activity == null) {
            return null;
        }
        AlertDialog progress = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(false);
        progress.show();
        return progress;
    }

    static void dismiss(AlertDialog dialog) {
        if (dialog != null) {
            dialog.dismiss();
        }
    }

    static void toast(Context context, String text) {
        if (context != null && text != null) {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Red line under the password fields (specs#172). A toast is easy to miss while the keyboard is
     * up, so password errors are shown inside the dialog; it hides again as soon as the user types.
     */
    static TextView passwordError(Context context, BaseFragment host, EditTextBoldCursor... fields) {
        TextView error = new TextView(context);
        error.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        error.setTextColor(Theme.getColor(Theme.key_text_RedRegular, host.getResourceProvider()));
        error.setVisibility(View.GONE);
        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                error.setVisibility(View.GONE);
            }
        };
        for (EditTextBoldCursor field : fields) {
            field.addTextChangedListener(watcher);
        }
        return error;
    }

    static void showPasswordError(TextView error, View field, String text) {
        error.setText(text);
        error.setVisibility(View.VISIBLE);
        AndroidUtilities.shakeView(field);
    }

    static EditTextBoldCursor passwordField(Context context, BaseFragment host, String hint) {
        Theme.ResourcesProvider rp = host.getResourceProvider();
        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, rp));
        editText.setHintTextColor(Theme.getColor(Theme.key_groupcreate_hintText, rp));
        editText.setHint(hint);
        editText.setSingleLine(true);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        editText.setTransformationMethod(PasswordTransformationMethod.getInstance());
        editText.setPadding(dp(16), dp(11), dp(16), dp(11));
        editText.setCursorWidth(1.5f);
        editText.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4, rp));
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(22));
        background.setColor(Theme.multAlpha(Theme.getColor(Theme.key_dialogTextBlack, rp), 0.06f));
        editText.setBackground(background);
        return editText;
    }

    // ---------------------------------------------------------------- choose accounts to restore

    /** The accounts found in a backup; keeps the decrypted keys in memory until it closes. */
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
                    toast(getParentActivity(), LuckgramBackup.alreadyLoggedInText());
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
            int skipped = 0;
            for (int i = 0; i < accounts.size(); i++) {
                if (LuckgramBackup.isLoggedIn(accounts.get(i).userId)) {
                    skipped++;
                } else if (selected.contains(i)) {
                    chosen.add(accounts.get(i));
                }
            }
            if (chosen.isEmpty()) {
                toast(getParentActivity(), LuckgramBackup.selectAtLeastOneText());
                return;
            }
            final int alreadyLoggedIn = skipped;
            restoring = true;
            // Keys that are not restored are wiped here; restore() wipes the others.
            for (LuckgramBackupFormat.Account account : accounts) {
                if (!chosen.contains(account)) {
                    account.wipe();
                }
            }
            AlertDialog progress = showProgress(getParentActivity());
            LuckgramBackup.restore(chosen, preferredSlot, result -> {
                dismiss(progress);
                // Accounts left out by the account limit are told apart from real failures.
                String text = LuckgramBackup.resultText(result.restored, result.skipped + alreadyLoggedIn, result.failed, result.noRoom);
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
