package org.telegram.luckgram.doublebottom;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.telegram.luckgram.LuckgramSettings;
import org.telegram.luckgram.accounts.LuckgramAccounts;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
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
import org.telegram.ui.PasscodeActivity;

import java.util.ArrayList;
import java.util.Set;

/**
 * Luckgram 11: Luckgram Features → Double space. Second passcode, the accounts visible with it,
 * and switching the feature off (main passcode required). Same layout as iOS.
 */
public class LuckgramDoubleBottomActivity extends UniversalFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int ID_OPEN_PASSCODE = 1;
    private static final int ID_SET_PASSCODE = 2;
    private static final int ID_TURN_OFF = 3;
    private static final int ID_ACCOUNT_BASE = 1000;

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didSetPasscode);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.didSetPasscode);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null) {
            listView.adapter.update(false);
        }
    }

    @Override
    protected CharSequence getTitle() {
        return LuckgramDoubleBottom.titleText();
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (!LuckgramDoubleBottom.isPasscodeOn()) {
            items.add(UItem.asButton(ID_OPEN_PASSCODE, R.drawable.msg_permissions, LuckgramDoubleBottom.openPasscodeText()).accent());
            items.add(UItem.asShadow(LuckgramDoubleBottom.needPasscodeText()));
            return;
        }
        boolean hasSecond = LuckgramDoubleBottom.hasSecondPasscode();
        items.add(UItem.asButton(ID_SET_PASSCODE, R.drawable.msg_permissions, hasSecond ? LuckgramDoubleBottom.changePasscodeText() : LuckgramDoubleBottom.setPasscodeText()).accent());
        items.add(UItem.asShadow(LuckgramDoubleBottom.descriptionText()));

        items.add(UItem.asHeader(LuckgramDoubleBottom.visibleHeaderText()));
        Set<Long> visible = LuckgramDoubleBottom.visibleUserIds();
        for (int account : LuckgramAccounts.allOrderedAccounts()) {
            long userId = UserConfig.getInstance(account).getClientUserId();
            items.add(UItem.asCheck(ID_ACCOUNT_BASE + account, LuckgramAccounts.displayName(account)).setChecked(visible.contains(userId)));
        }
        items.add(UItem.asShadow(LuckgramDoubleBottom.visibleInfoText()));

        if (hasSecond) {
            items.add(UItem.asButton(ID_TURN_OFF, LuckgramDoubleBottom.turnOffText()).red());
            items.add(UItem.asShadow(null));
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_OPEN_PASSCODE) {
            presentFragment(PasscodeActivity.determineOpenFragment());
        } else if (item.id == ID_SET_PASSCODE) {
            askSecondPasscode(this, () -> {
                ensureVisibleDefault();
                updateEnabled();
                toast(LuckgramDoubleBottom.savedText());
                listView.adapter.update(true);
            });
        } else if (item.id == ID_TURN_OFF) {
            askMainPasscode(this, () -> {
                LuckgramDoubleBottom.turnOff();
                finishFragment();
            });
        } else if (item.id >= ID_ACCOUNT_BASE) {
            int account = item.id - ID_ACCOUNT_BASE;
            long userId = UserConfig.getInstance(account).getClientUserId();
            Set<Long> visible = LuckgramDoubleBottom.visibleUserIds();
            if (visible.contains(userId)) {
                if (visible.size() <= 1) {
                    toast(LuckgramDoubleBottom.atLeastOneText());
                    return;
                }
                visible.remove(userId);
            } else {
                visible.add(userId);
            }
            LuckgramDoubleBottom.setVisibleUserIds(visible);
            updateEnabled();
            listView.adapter.update(true);
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private void toast(String text) {
        if (getParentActivity() != null) {
            Toast.makeText(getParentActivity(), text, Toast.LENGTH_SHORT).show();
        }
    }

    /** The current account is visible by default. */
    private void ensureVisibleDefault() {
        Set<Long> visible = LuckgramDoubleBottom.visibleUserIds();
        if (visible.isEmpty()) {
            visible.add(UserConfig.getInstance(currentAccount).getClientUserId());
            LuckgramDoubleBottom.setVisibleUserIds(visible);
        }
    }

    /** The switch turns on once a second passcode and at least one visible account are set. */
    private static void updateEnabled() {
        if (LuckgramDoubleBottom.hasSecondPasscode() && !LuckgramDoubleBottom.visibleUserIds().isEmpty() && !LuckgramSettings.isEnabled(LuckgramDoubleBottom.FEATURE_KEY)) {
            LuckgramSettings.setEnabled(LuckgramDoubleBottom.FEATURE_KEY, true);
        }
    }

    // ---------------------------------------------------------------- dialogs (also used by the feature switch)

    /** Switching on from Luckgram Features: needs the app passcode and a second passcode first. */
    public static void confirmEnable(BaseFragment fragment, Utilities.Callback<Boolean> done) {
        if (!LuckgramDoubleBottom.isPasscodeOn()) {
            AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity());
            builder.setTitle(LuckgramDoubleBottom.titleText());
            builder.setMessage(LuckgramDoubleBottom.needPasscodeText());
            builder.setPositiveButton(LuckgramDoubleBottom.openPasscodeText(), (d, w) -> fragment.presentFragment(PasscodeActivity.determineOpenFragment()));
            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            fragment.showDialog(builder.create());
            done.run(false);
        } else if (!LuckgramDoubleBottom.hasSecondPasscode() || LuckgramDoubleBottom.visibleUserIds().isEmpty()) {
            fragment.presentFragment(new LuckgramDoubleBottomActivity());
            done.run(false);
        } else {
            done.run(true);
        }
    }

    /** Switching off needs the main passcode. */
    public static void confirmDisable(BaseFragment fragment, Utilities.Callback<Boolean> done) {
        askMainPasscode(fragment, () -> {
            LuckgramDoubleBottom.turnOff();
            done.run(true);
        });
    }

    private static boolean isPin() {
        return SharedConfig.passcodeType == SharedConfig.PASSCODE_TYPE_PIN;
    }

    private static EditTextBoldCursor field(BaseFragment fragment, Context context, String hint) {
        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, fragment.getResourceProvider()));
        editText.setHintTextColor(Theme.getColor(Theme.key_groupcreate_hintText, fragment.getResourceProvider()));
        editText.setHint(hint);
        editText.setSingleLine(true);
        if (isPin()) {
            editText.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            editText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        } else {
            editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        editText.setTransformationMethod(PasswordTransformationMethod.getInstance());
        editText.setPadding(dp(16), dp(11), dp(16), dp(11));
        editText.setCursorWidth(1.5f);
        editText.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4, fragment.getResourceProvider()));
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(22));
        background.setColor(Theme.multAlpha(Theme.getColor(Theme.key_dialogTextBlack, fragment.getResourceProvider()), 0.06f));
        editText.setBackground(background);
        return editText;
    }

    private static void askSecondPasscode(BaseFragment fragment, Runnable done) {
        Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        EditTextBoldCursor first = field(fragment, context, LuckgramDoubleBottom.enterSecondText());
        EditTextBoldCursor second = field(fragment, context, LuckgramDoubleBottom.repeatSecondText());
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(first, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 6, 20, 6));
        container.addView(second, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 6, 20, 6));

        AlertDialog.Builder builder = new AlertDialog.Builder(context, fragment.getResourceProvider());
        builder.setTitle(LuckgramDoubleBottom.setPasscodeText());
        builder.setView(container);
        builder.setPositiveButton(LocaleController.getString(R.string.Save), (d, w) -> {
            String code = first.getText().toString();
            if (isPin() ? !code.matches("\\d{4}") : code.isEmpty()) {
                AndroidUtilities.shakeView(first);
                if (isPin()) {
                    Toast.makeText(context, LuckgramDoubleBottom.pinRuleText(), Toast.LENGTH_SHORT).show();
                }
                return;
            }
            if (!code.equals(second.getText().toString())) {
                AndroidUtilities.shakeView(second);
                Toast.makeText(context, LuckgramDoubleBottom.mismatchText(), Toast.LENGTH_SHORT).show();
                return;
            }
            if (SharedConfig.checkPasscode(code)) {
                AndroidUtilities.shakeView(first);
                Toast.makeText(context, LuckgramDoubleBottom.sameAsMainText(), Toast.LENGTH_SHORT).show();
                return;
            }
            LuckgramDoubleBottom.setSecondPasscode(code);
            first.setText("");
            second.setText("");
            d.dismiss();
            done.run();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), (d, w) -> d.dismiss());
        AlertDialog dialog = builder.create();
        dialog.setDismissDialogByButtons(false);
        dialog.setOnShowListener(d -> {
            first.requestFocus();
            AndroidUtilities.showKeyboard(first);
        });
        fragment.showDialog(dialog);
    }

    private static void askMainPasscode(BaseFragment fragment, Runnable done) {
        Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        EditTextBoldCursor field = field(fragment, context, LuckgramDoubleBottom.enterMainText());
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(field, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 9, 20, 9));

        AlertDialog.Builder builder = new AlertDialog.Builder(context, fragment.getResourceProvider());
        builder.setTitle(LuckgramDoubleBottom.enterMainText());
        builder.setView(container);
        builder.setPositiveButton(LocaleController.getString(R.string.OK), (d, w) -> {
            String code = field.getText().toString();
            if (code.isEmpty() || !SharedConfig.checkPasscode(code)) {
                AndroidUtilities.shakeView(field);
                field.setText("");
                Toast.makeText(context, LuckgramDoubleBottom.wrongMainText(), Toast.LENGTH_SHORT).show();
                return;
            }
            field.setText("");
            d.dismiss();
            done.run();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), (d, w) -> d.dismiss());
        AlertDialog dialog = builder.create();
        dialog.setDismissDialogByButtons(false);
        dialog.setOnShowListener(d -> {
            field.requestFocus();
            AndroidUtilities.showKeyboard(field);
        });
        fragment.showDialog(dialog);
    }
}
