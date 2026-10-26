/*
 * Luckgram — keyword folders.
 * Licensed under GNU GPL v. 2 or later, like the rest of this Telegram-based project.
 */

package org.telegram.luckgram.keywordfolders;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

/** Create / edit a keyword folder: name, 1–10 keywords, chat types. */
public class KeywordFolderEditActivity extends BaseFragment {

    private static final int DONE_BUTTON = 1;
    private static final int MAX_NAME_LENGTH = 12;
    private static final int MAX_KEYWORD_LENGTH = 64;

    private final int folderId;
    private KeywordFolder editing;

    private EditTextBoldCursor nameField;
    private LinearLayout keywordsContainer;
    private TextCell addKeywordCell;
    private final ArrayList<EditTextBoldCursor> keywordFields = new ArrayList<>();
    private TextCheckCell allCell, privateCell, groupsCell, channelsCell;
    private int scope = KeywordFolder.SCOPE_ALL;

    /** @param folderId 0 to create a new folder */
    public KeywordFolderEditActivity(int folderId) {
        this.folderId = folderId;
    }

    @Override
    public boolean onFragmentCreate() {
        if (folderId != 0) {
            KeywordFolder f = KeywordFolderController.getInstance(currentAccount).getFolder(folderId);
            if (f == null) {
                return false;
            }
            editing = f.copy();
            scope = editing.scope;
        }
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(editing == null ? KeywordFolderStrings.newFolder() : KeywordFolderStrings.editFolder());
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == DONE_BUTTON) {
                    save();
                }
            }
        });
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItemWithWidth(DONE_BUTTON, R.drawable.ic_ab_done, dp(56), LocaleController.getString(R.string.Done));

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = scrollView;

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(root, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        // Name
        LinearLayout nameSection = section(context);
        root.addView(nameSection, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        HeaderCell nameHeader = new HeaderCell(context);
        nameHeader.setText(KeywordFolderStrings.nameHeader());
        nameSection.addView(nameHeader);
        nameField = createField(context, KeywordFolderStrings.nameHint(), MAX_NAME_LENGTH);
        nameField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        nameSection.addView(nameField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40, 21, 0, 21, 12));
        if (editing != null) {
            nameField.setText(editing.name);
        }
        root.addView(new ShadowSectionCell(context));

        // Keywords
        LinearLayout keywordsSection = section(context);
        root.addView(keywordsSection, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        HeaderCell keywordsHeader = new HeaderCell(context);
        keywordsHeader.setText(KeywordFolderStrings.keywordsHeader());
        keywordsSection.addView(keywordsHeader);
        keywordsContainer = new LinearLayout(context);
        keywordsContainer.setOrientation(LinearLayout.VERTICAL);
        keywordsSection.addView(keywordsContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        addKeywordCell = new TextCell(context);
        addKeywordCell.setColors(Theme.key_windowBackgroundWhiteBlueIcon, Theme.key_windowBackgroundWhiteBlueText4);
        addKeywordCell.setTextAndIcon(KeywordFolderStrings.addKeyword(), R.drawable.msg_add, false);
        addKeywordCell.setBackground(Theme.getSelectorDrawable(false));
        addKeywordCell.setOnClickListener(v -> {
            if (keywordFields.size() >= KeywordFolderController.MAX_KEYWORDS) {
                BulletinFactory.of(this).createErrorBulletin(KeywordFolderStrings.keywordsLimit()).show();
                return;
            }
            EditTextBoldCursor field = addKeywordRow(context, "");
            field.requestFocus();
            AndroidUtilities.showKeyboard(field);
        });
        keywordsSection.addView(addKeywordCell);
        if (editing != null) {
            for (String k : editing.keywords) {
                addKeywordRow(context, k);
            }
        }
        if (keywordFields.isEmpty()) {
            addKeywordRow(context, "");
        }
        TextInfoPrivacyCell keywordsInfo = new TextInfoPrivacyCell(context);
        keywordsInfo.setText(KeywordFolderStrings.keywordsInfo());
        root.addView(keywordsInfo);

        // Scope
        LinearLayout scopeSection = section(context);
        root.addView(scopeSection, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        HeaderCell scopeHeader = new HeaderCell(context);
        scopeHeader.setText(KeywordFolderStrings.scopeHeader());
        scopeSection.addView(scopeHeader);
        allCell = scopeCell(context, KeywordFolderStrings.scopeAll(), true, v -> {
            scope = scope == KeywordFolder.SCOPE_ALL ? 0 : KeywordFolder.SCOPE_ALL;
            updateScopeCells();
        });
        privateCell = scopeCell(context, KeywordFolderStrings.scopePrivate(), true, v -> toggleScope(KeywordFolder.SCOPE_PRIVATE));
        groupsCell = scopeCell(context, KeywordFolderStrings.scopeGroups(), true, v -> toggleScope(KeywordFolder.SCOPE_GROUPS));
        channelsCell = scopeCell(context, KeywordFolderStrings.scopeChannels(), false, v -> toggleScope(KeywordFolder.SCOPE_CHANNELS));
        scopeSection.addView(allCell);
        scopeSection.addView(privateCell);
        scopeSection.addView(groupsCell);
        scopeSection.addView(channelsCell);
        updateScopeCells();
        TextInfoPrivacyCell info = new TextInfoPrivacyCell(context);
        info.setText(KeywordFolderStrings.settingsInfo());
        root.addView(info);

        if (editing != null) {
            LinearLayout deleteSection = section(context);
            root.addView(deleteSection, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            TextCell deleteCell = new TextCell(context);
            deleteCell.setColors(Theme.key_text_RedRegular, Theme.key_text_RedRegular);
            deleteCell.setTextAndIcon(KeywordFolderStrings.delete(), R.drawable.msg_delete, false);
            deleteCell.setBackground(Theme.getSelectorDrawable(false));
            deleteCell.setOnClickListener(v -> KeywordFolderUi.showDeleteAlert(this, editing, this::finishFragment));
            deleteSection.addView(deleteCell);
            TextInfoPrivacyCell bottom = new TextInfoPrivacyCell(context);
            bottom.setFixedSize(12);
            root.addView(bottom);
        }
        return fragmentView;
    }

    private LinearLayout section(Context context) {
        LinearLayout l = new LinearLayout(context);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        return l;
    }

    private EditTextBoldCursor createField(Context context, CharSequence hint, int maxLength) {
        EditTextBoldCursor field = new EditTextBoldCursor(context);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        field.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        field.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        field.setBackground(null);
        field.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField), Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated), Theme.getColor(Theme.key_text_RedRegular));
        field.setMaxLines(1);
        field.setLines(1);
        field.setSingleLine(true);
        field.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        field.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        field.setHint(hint);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLength)});
        field.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        field.setCursorSize(dp(20));
        field.setCursorWidth(1.5f);
        return field;
    }

    private EditTextBoldCursor addKeywordRow(Context context, String text) {
        FrameLayout row = new FrameLayout(context);
        EditTextBoldCursor field = createField(context, KeywordFolderStrings.keywordHint(), MAX_KEYWORD_LENGTH);
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        field.setText(text);
        row.addView(field, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 40, Gravity.LEFT | Gravity.TOP, LocaleController.isRTL ? 48 : 21, 4, LocaleController.isRTL ? 21 : 48, 4));
        ImageView remove = new ImageView(context);
        remove.setScaleType(ImageView.ScaleType.CENTER);
        remove.setImageResource(R.drawable.msg_close);
        remove.setColorFilter(new android.graphics.PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), android.graphics.PorterDuff.Mode.MULTIPLY));
        remove.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        remove.setContentDescription(LocaleController.getString(R.string.Delete));
        remove.setOnClickListener(v -> {
            keywordFields.remove(field);
            keywordsContainer.removeView(row);
            if (keywordFields.isEmpty()) {
                addKeywordRow(context, "");
            }
        });
        row.addView(remove, LayoutHelper.createFrame(40, 40, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL, 8, 0, 8, 0));
        keywordsContainer.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
        keywordFields.add(field);
        return field;
    }

    private TextCheckCell scopeCell(Context context, String text, boolean divider, View.OnClickListener listener) {
        TextCheckCell cell = new TextCheckCell(context);
        cell.setBackground(Theme.getSelectorDrawable(false));
        cell.setTextAndCheck(text, false, divider);
        cell.setOnClickListener(listener);
        return cell;
    }

    private void toggleScope(int flag) {
        scope ^= flag;
        updateScopeCells();
    }

    private void updateScopeCells() {
        allCell.setChecked(scope == KeywordFolder.SCOPE_ALL);
        privateCell.setChecked((scope & KeywordFolder.SCOPE_PRIVATE) != 0);
        groupsCell.setChecked((scope & KeywordFolder.SCOPE_GROUPS) != 0);
        channelsCell.setChecked((scope & KeywordFolder.SCOPE_CHANNELS) != 0);
    }

    private void save() {
        String name = nameField.getText() == null ? "" : nameField.getText().toString().trim();
        if (name.isEmpty()) {
            AndroidUtilities.shakeViewSpring(nameField);
            BulletinFactory.of(this).createErrorBulletin(KeywordFolderStrings.errorName()).show();
            return;
        }
        ArrayList<String> keywords = new ArrayList<>();
        for (EditTextBoldCursor field : keywordFields) {
            String k = field.getText() == null ? "" : field.getText().toString().trim();
            if (k.isEmpty()) {
                continue;
            }
            boolean duplicate = false;
            for (String existing : keywords) {
                if (existing.equalsIgnoreCase(k)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate && keywords.size() < KeywordFolderController.MAX_KEYWORDS) {
                keywords.add(k);
            }
        }
        if (keywords.isEmpty()) {
            if (!keywordFields.isEmpty()) {
                AndroidUtilities.shakeViewSpring(keywordFields.get(0));
            }
            BulletinFactory.of(this).createErrorBulletin(KeywordFolderStrings.errorKeywords()).show();
            return;
        }
        if ((scope & KeywordFolder.SCOPE_ALL) == 0) {
            AndroidUtilities.shakeViewSpring(allCell);
            BulletinFactory.of(this).createErrorBulletin(KeywordFolderStrings.errorScope()).show();
            return;
        }
        KeywordFolderController controller = KeywordFolderController.getInstance(currentAccount);
        KeywordFolder folder = editing != null ? editing : new KeywordFolder();
        if (editing == null && !controller.canCreate()) {
            BulletinFactory.of(this).createErrorBulletin(KeywordFolderStrings.limitReached()).show();
            return;
        }
        folder.name = name;
        folder.keywords = keywords;
        folder.scope = scope;
        controller.saveFolder(folder);
        AndroidUtilities.hideKeyboard(nameField);
        finishFragment();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (editing == null && nameField != null && nameField.length() == 0) {
            nameField.requestFocus();
            AndroidUtilities.showKeyboard(nameField);
        }
    }
}
