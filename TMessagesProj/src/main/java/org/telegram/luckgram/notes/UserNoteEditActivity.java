/*
 * Luckgram — private notes (spec 05).
 * Licensed under GNU GPL v. 2 or later. Layout based on Telegram's ChangeBioActivity.
 */

package org.telegram.luckgram.notes;

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.Spanned;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Components.CodepointsLengthInputFilter;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.NumberTextView;

import java.util.ArrayList;

/** Edits the private note of one peer. Arguments: {@code peer_id} (user id, or -chat id). */
public class UserNoteEditActivity extends BaseFragment {

    private static final int done_button = 1;
    private static final int menu_all_notes = 2;
    private static final int menu_delete = 3;

    private long peerId;
    private String initialText = "";

    private EditTextBoldCursor editText;
    private NumberTextView counterView;
    private TextView infoView;
    private View doneButton;

    public UserNoteEditActivity(long peerId) {
        super(makeArgs(peerId));
    }

    public UserNoteEditActivity(Bundle args) {
        super(args);
    }

    private static Bundle makeArgs(long peerId) {
        Bundle b = new Bundle();
        b.putLong("peer_id", peerId);
        return b;
    }

    @Override
    public boolean onFragmentCreate() {
        peerId = arguments != null ? arguments.getLong("peer_id", 0) : 0;
        if (peerId == 0) {
            return false;
        }
        String note = UserNotesStore.getNote(currentAccount, peerId);
        initialText = note == null ? "" : note;
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(UserNotesStrings.editTitle());
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (onBackPressed(true)) {
                        finishFragment();
                    }
                } else if (id == done_button) {
                    save();
                } else if (id == menu_all_notes) {
                    presentFragment(new UserNotesListActivity());
                } else if (id == menu_delete) {
                    confirmDelete();
                }
            }
        });

        ActionBarMenu menu = actionBar.createMenu();
        doneButton = menu.addItemWithWidth(done_button, R.drawable.ic_ab_done, AndroidUtilities.dp(56));
        doneButton.setContentDescription(LocaleController.getString(R.string.Done));
        org.telegram.ui.ActionBar.ActionBarMenuItem other = menu.addItem(0, R.drawable.ic_ab_other);
        other.setContentDescription(LocaleController.getString(R.string.AccDescrMoreOptions));
        other.addSubItem(menu_all_notes, R.drawable.msg_list, UserNotesStrings.allNotes());
        if (!initialText.isEmpty()) {
            other.addSubItem(menu_delete, R.drawable.msg_delete, UserNotesStrings.deleteNote());
        }

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        fragmentView = scrollView;
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(linearLayout, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        FrameLayout fieldContainer = new FrameLayout(context);
        linearLayout.addView(fieldContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 24, 20, 0));

        editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        editText.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        editText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setBackgroundDrawable(null);
        editText.setLineColors(getThemedColor(Theme.key_windowBackgroundWhiteInputField), getThemedColor(Theme.key_windowBackgroundWhiteInputFieldActivated), getThemedColor(Theme.key_text_RedRegular));
        editText.setPadding(AndroidUtilities.dp(LocaleController.isRTL ? 40 : 0), 0, AndroidUtilities.dp(LocaleController.isRTL ? 0 : 40), AndroidUtilities.dp(6));
        editText.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        editText.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        // Multi-line: Enter inserts a line break.
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        editText.setSingleLine(false);
        editText.setMinLines(3);
        editText.setMinHeight(AndroidUtilities.dp(36));
        editText.setHint(UserNotesStrings.editHint());
        editText.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setCursorSize(AndroidUtilities.dp(20));
        editText.setCursorWidth(1.5f);
        editText.setFilters(new android.text.InputFilter[]{new CodepointsLengthInputFilter(UserNotesStore.MAX_LENGTH) {
            @Override
            public CharSequence filter(CharSequence source, int start, int end, Spanned dest, int dstart, int dend) {
                CharSequence result = super.filter(source, start, end, dest, dstart, dend);
                if (result != null && source != null && result.length() != source.length()) {
                    AndroidUtilities.shakeView(counterView);
                    try {
                        org.telegram.ui.Components.BulletinFactory.of(UserNoteEditActivity.this).createSimpleBulletin(R.raw.error, UserNotesStrings.tooLong()).show();
                    } catch (Exception ignore) {
                    }
                }
                return result;
            }
        }});
        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateCounter(true);
            }
        });
        fieldContainer.addView(editText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 0, 0, 4, 0));

        counterView = new NumberTextView(context);
        counterView.setCenterAlign(true);
        counterView.setTextSize(15);
        counterView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText4));
        counterView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        fieldContainer.addView(counterView, LayoutHelper.createFrame(40, 20, LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT, 0, 4, 0, 0));

        infoView = new TextView(context);
        infoView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        infoView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText8));
        infoView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        infoView.setText(UserNotesStrings.editInfo());
        linearLayout.addView(infoView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT, 24, 10, 24, 24));

        editText.setText(initialText);
        editText.setSelection(editText.length());
        updateCounter(false);
        return fragmentView;
    }

    private void updateCounter(boolean animated) {
        if (counterView == null || editText == null) {
            return;
        }
        Editable s = editText.getText();
        int used = s == null ? 0 : Character.codePointCount(s, 0, s.length());
        int left = UserNotesStore.MAX_LENGTH - used;
        // Only show the counter close to the limit, like Telegram's caption limit.
        counterView.setVisibility(left <= 200 ? View.VISIBLE : View.GONE);
        counterView.setNumber(left, animated);
    }

    private String currentText() {
        return editText == null || editText.getText() == null ? "" : editText.getText().toString().trim();
    }

    private void save() {
        UserNotesStore.setNote(currentAccount, peerId, currentText());
        initialText = currentText();
        finishFragment();
    }

    private void confirmDelete() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(UserNotesStrings.deleteNote())
                .setMessage(UserNotesStrings.deleteConfirm())
                .setPositiveButton(UserNotesStrings.delete(), (d, w) -> {
                    UserNotesStore.setNote(currentAccount, peerId, null);
                    initialText = "";
                    finishFragment();
                })
                .setNegativeButton(UserNotesStrings.cancel(), null)
                .makeRed(AlertDialog.BUTTON_POSITIVE)
                .create();
        showDialog(dialog);
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (editText != null && !currentText().equals(initialText.trim()) && getParentActivity() != null) {
            if (invoked) {
                AlertDialog dialog = new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                        .setTitle(UserNotesStrings.discardTitle())
                        .setPositiveButton(UserNotesStrings.discard(), (d, w) -> {
                            initialText = currentText();
                            finishFragment();
                        })
                        .setNegativeButton(UserNotesStrings.cancel(), null)
                        .makeRed(AlertDialog.BUTTON_POSITIVE)
                        .create();
                showDialog(dialog);
            }
            return false;
        }
        return super.onBackPressed(invoked);
    }

    @Override
    public void onTransitionAnimationEnd(boolean isOpen, boolean backward) {
        if (isOpen && editText != null) {
            editText.requestFocus();
            AndroidUtilities.showKeyboard(editText);
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> list = new ArrayList<>();
        list.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundWhite));
        list.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault));
        list.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        list.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        list.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));
        list.add(new ThemeDescription(editText, ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        list.add(new ThemeDescription(editText, ThemeDescription.FLAG_HINTTEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteHintText));
        list.add(new ThemeDescription(editText, ThemeDescription.FLAG_BACKGROUNDFILTER, null, null, null, null, Theme.key_windowBackgroundWhiteInputField));
        list.add(new ThemeDescription(editText, ThemeDescription.FLAG_BACKGROUNDFILTER | ThemeDescription.FLAG_DRAWABLESELECTEDSTATE, null, null, null, null, Theme.key_windowBackgroundWhiteInputFieldActivated));
        list.add(new ThemeDescription(infoView, ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteGrayText8));
        list.add(new ThemeDescription(counterView, ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteGrayText4));
        return list;
    }
}
