package org.telegram.luckgram.backup;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.luckgram.accounts.LuckgramAccounts;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * Luckgram Features → Account backup (user 2026-11-13: local file backup, no cloud). Pick accounts,
 * export them to an encrypted {@code .luckgram-backup} file (save to the device or share), or
 * restore from a file. Opened from the login screen it only offers the restore. Same layout and
 * texts as iOS {@code luckgramBackupController}.
 */
public class LuckgramBackupActivity extends UniversalFragment {

    private static final int ID_ACCOUNT_BASE = 1000;
    private static final int ID_EXPORT = 1;
    private static final int ID_RESTORE = 2;
    private static final int REQUEST_PICK_FILE = 8410;
    private static final int REQUEST_SAVE_FILE = 8411;

    private final boolean fromLogin;
    /** The empty account of the login screen, or -1. */
    private final int preferredSlot;
    private final HashSet<Integer> deselected = new HashSet<>();
    /** Encrypted file waiting for the "save to device" document; ciphertext only. */
    private byte[] pendingSave;

    public LuckgramBackupActivity() {
        this(false, -1);
    }

    public LuckgramBackupActivity(boolean fromLogin, int preferredSlot) {
        this.fromLogin = fromLogin;
        this.preferredSlot = preferredSlot;
    }

    @Override
    protected CharSequence getTitle() {
        return fromLogin ? LuckgramBackup.restoreText() : LuckgramBackup.titleText();
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (!fromLogin) {
            items.add(UItem.asHeader(LuckgramBackup.selectHeaderText()));
            for (int account : LuckgramAccounts.orderedAccounts()) {
                items.add(UItem.asCheck(ID_ACCOUNT_BASE + account, LuckgramAccounts.displayName(account)).setChecked(!deselected.contains(account)));
            }
            items.add(UItem.asShadow(null));
            items.add(UItem.asButton(ID_EXPORT, R.drawable.msg_share, LuckgramBackup.exportText()).accent());
            items.add(UItem.asShadow(LuckgramBackup.exportHintText() + "\n\n" + LuckgramBackup.warningText()));
        }
        items.add(UItem.asButton(ID_RESTORE, R.drawable.msg_download, LuckgramBackup.restoreText()).accent());
        items.add(UItem.asShadow(LuckgramBackup.restoreHintText() + "\n\n" + LuckgramBackup.infoText()));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id >= ID_ACCOUNT_BASE) {
            int account = item.id - ID_ACCOUNT_BASE;
            if (!deselected.remove(account)) {
                deselected.add(account);
            }
            listView.adapter.update(true);
        } else if (item.id == ID_EXPORT) {
            ArrayList<Integer> accounts = new ArrayList<>();
            for (int account : LuckgramAccounts.orderedAccounts()) {
                if (!deselected.contains(account)) {
                    accounts.add(account);
                }
            }
            if (accounts.isEmpty()) {
                CloudBackupActivity.toast(getParentActivity(), LuckgramBackup.selectAtLeastOneText());
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

        EditTextBoldCursor first = CloudBackupActivity.passwordField(context, this, LuckgramBackup.passwordHintText());
        EditTextBoldCursor second = CloudBackupActivity.passwordField(context, this, LuckgramBackup.passwordRepeatHintText());
        container.addView(first, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 6, 20, 6));
        container.addView(second, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 6, 20, 6));

        AlertDialog.Builder builder = new AlertDialog.Builder(context, getResourceProvider());
        builder.setTitle(LuckgramBackup.passwordTitleText());
        builder.setView(container);
        builder.setPositiveButton(LuckgramBackup.exportText(), (d, w) -> {
            String password = first.getText().toString();
            if (password.length() < LuckgramBackupFormat.MIN_PASSWORD_LENGTH) {
                AndroidUtilities.shakeView(first);
                CloudBackupActivity.toast(context, LuckgramBackup.passwordTooShortText());
                return;
            }
            if (!password.equals(second.getText().toString())) {
                AndroidUtilities.shakeView(second);
                CloudBackupActivity.toast(context, LuckgramBackup.passwordMismatchText());
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

    private void runExport(ArrayList<Integer> accounts, String password) {
        AlertDialog progress = CloudBackupActivity.showProgress(getParentActivity());
        LuckgramBackup.encrypt(accounts, password, data -> {
            CloudBackupActivity.dismiss(progress);
            if (data == null) {
                CloudBackupActivity.toast(getParentActivity(), LuckgramBackup.exportFailedText());
                return;
            }
            chooseDestination(data);
        });
    }

    /** "Save to device" (system file picker) or "Share…" (share sheet). */
    private void chooseDestination(byte[] data) {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, getResourceProvider());
        builder.setTitle(LuckgramBackup.exportText());
        builder.setItems(new CharSequence[]{LuckgramBackup.saveToDeviceText(), LuckgramBackup.shareText()}, (d, which) -> {
            if (which == 0) {
                saveToDevice(data);
            } else if (!LuckgramBackup.share(activity, data)) {
                CloudBackupActivity.toast(activity, LuckgramBackup.exportFailedText());
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void saveToDevice(byte[] data) {
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/octet-stream");
            intent.putExtra(Intent.EXTRA_TITLE, LuckgramBackup.fileName());
            pendingSave = data;
            startActivityForResult(intent, REQUEST_SAVE_FILE);
        } catch (Throwable e) {
            pendingSave = null;
            // No document picker on this device: fall back to the share sheet.
            if (!LuckgramBackup.share(getParentActivity(), data)) {
                CloudBackupActivity.toast(getParentActivity(), LuckgramBackup.exportFailedText());
            }
        }
    }

    // ---------------------------------------------------------------- restore

    private void pickFile() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQUEST_PICK_FILE);
        } catch (Throwable e) {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            startActivityForResult(intent, REQUEST_PICK_FILE);
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_SAVE_FILE) {
            byte[] file = pendingSave;
            pendingSave = null;
            if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null || file == null) {
                return;
            }
            CloudBackupActivity.toast(getParentActivity(), LuckgramBackup.writeTo(data.getData(), file) ? LuckgramBackup.savedText() : LuckgramBackup.exportFailedText());
            return;
        }
        if (requestCode != REQUEST_PICK_FILE || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        byte[] file;
        try {
            file = LuckgramBackup.readFile(data.getData());
        } catch (Throwable e) {
            CloudBackupActivity.toast(getParentActivity(), LuckgramBackup.notBackupText());
            return;
        }
        CloudBackupActivity.askPassword(this, file, fromLogin ? preferredSlot : -1);
    }
}
