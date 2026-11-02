package org.telegram.luckgram.square;

import android.content.Context;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;

import org.json.JSONObject;
import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramAIError;
import org.telegram.luckgram.LuckgramSettings;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/**
 * The Square profile page (spec 26): name, bio, "use my Telegram photo", "show my @username".
 * Opened when a write (post, like, comment, report) finds no profile yet (GET /v1/square/profile
 * 404 / profile_required), and from the profile button. After a successful save the page closes
 * and {@code then} continues the original action (e.g. opens the compose page).
 *
 * It replaced an AlertDialog (acceptance 2026-11-02, build 9210086: a new user's ✎ did nothing
 * after the 404 — no dialog, no message — so new users could never post).
 */
public class SquareProfileActivity extends UniversalFragment {

    static final String KEY_USE_AVATAR = "square.use_avatar";
    private static final int MENU_SAVE = 1;
    private static final int ID_INFO = 1;
    private static final int ID_FORM = 2;
    private static final int ID_AVATAR = 3;
    private static final int ID_USERNAME = 4;

    private final SquareLogic.Texts tx = SquareUi.tx();
    private final JSONObject current;
    private final Runnable then;

    private EditText nameField;
    private EditText bioField;
    private LinearLayout form;
    private String avatar;
    private String myUsername;
    private boolean useAvatar;
    private boolean showUsername;
    private boolean saving;
    private ActionBarMenuItem saveItem;

    /** {@code current}: the existing profile, or null for the first fill-in; {@code then}: runs after saving. */
    public SquareProfileActivity(JSONObject current, Runnable then) {
        this.current = current;
        this.then = then;
    }

    @Override
    public boolean onFragmentCreate() {
        TLRPC.User me = UserConfig.getInstance(currentAccount).getCurrentUser();
        myUsername = UserObject.getPublicUsername(me);
        avatar = SquareUi.avatarBase64(currentAccount, me);
        useAvatar = avatar != null && LuckgramSettings.getBoolForAccount(currentAccount, KEY_USE_AVATAR, true);
        showUsername = current != null && !current.optString("username", "").isEmpty();
        return super.onFragmentCreate();
    }

    @Override
    protected CharSequence getTitle() {
        return current == null ? tx.profileTitle() : tx.editProfile();
    }

    @Override
    public View createView(Context context) {
        View v = super.createView(context);
        actionBar.setAllowOverlayTitle(false);
        ActionBarMenu menu = actionBar.createMenu();
        saveItem = menu.addItem(MENU_SAVE, R.drawable.ic_ab_done);
        saveItem.setContentDescription(tx.save());
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_SAVE) {
                    save();
                }
            }
        });
        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (nameField != null && nameField.getText().length() == 0) {
            nameField.requestFocus();
            AndroidUtilities.showKeyboard(nameField);
        }
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        Context ctx = getContext();
        if (form == null) {
            TLRPC.User me = UserConfig.getInstance(currentAccount).getCurrentUser();
            form = new LinearLayout(ctx);
            form.setOrientation(LinearLayout.VERTICAL);
            form.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            form.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(6), AndroidUtilities.dp(21), AndroidUtilities.dp(10));
            nameField = field(ctx, tx.displayName(), 64, false);
            nameField.setText(current != null ? current.optString("display_name", "") : (me != null ? UserObject.getUserName(me) : ""));
            form.addView(nameField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            bioField = field(ctx, tx.bio(), SquareLogic.PROFILE_BIO_MAX, true);
            bioField.setText(current != null ? current.optString("bio", "") : "");
            form.addView(bioField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
        }
        if (current == null) {
            items.add(UItem.asShadow(ID_INFO, tx.profileNeeded()));
        }
        items.add(UItem.asCustom(ID_FORM, form));
        if (avatar != null) {
            items.add(UItem.asCheck(ID_AVATAR, tx.useTelegramAvatar()).setChecked(useAvatar));
        }
        if (!TextUtils.isEmpty(myUsername)) {
            items.add(UItem.asCheck(ID_USERNAME, tx.useMyUsername(myUsername)).setChecked(showUsername));
        }
        items.add(UItem.asShadow(null));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_AVATAR) {
            useAvatar = !useAvatar;
            listView.adapter.update(true);
        } else if (item.id == ID_USERNAME) {
            showUsername = !showUsername;
            listView.adapter.update(true);
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private void save() {
        if (saving || nameField == null) return;
        String name = nameField.getText().toString().trim();
        if (name.isEmpty()) {
            AndroidUtilities.shakeViewSpring(nameField, -6);
            nameField.requestFocus();
            return;
        }
        try {
            JSONObject body = new JSONObject()
                .put("display_name", name)
                .put("bio", bioField.getText().toString().trim())
                .put("username", showUsername && myUsername != null ? myUsername : "");
            // S11: avatar is base64 JPEG; omitted = keep, "" = remove.
            if (avatar != null) {
                body.put("avatar", useAvatar ? avatar : "");
                LuckgramSettings.setBoolForAccount(currentAccount, KEY_USE_AVATAR, useAvatar);
            }
            saving = true;
            if (saveItem != null) saveItem.setEnabled(false);
            LuckgramAI.getInstance().requestJson("PUT", "/v1/square/profile", body, new LuckgramAI.Callback<JSONObject>() {
                @Override
                public void onResult(JSONObject result) {
                    saving = false;
                    SquareUi.onProfileSaved(result.optString("display_name", "").isEmpty() ? body : result);
                    AndroidUtilities.hideKeyboard(nameField);
                    finishFragment();
                    AndroidUtilities.runOnUIThread(() -> {
                        if (getParentLayout() != null && getParentLayout().getLastFragment() != null) {
                            SquareUi.bulletin(getParentLayout().getLastFragment()).createSimpleBulletin(R.raw.done, tx.saved()).show();
                        }
                        if (then != null) then.run();
                    }, 300);
                }

                @Override
                public void onError(LuckgramAIError error) {
                    saving = false;
                    if (saveItem != null) saveItem.setEnabled(true);
                    SquareUi.showError(SquareProfileActivity.this, error);
                }
            });
        } catch (Exception e) {
            FileLog.e(e);
            saving = false;
            if (saveItem != null) saveItem.setEnabled(true);
        }
    }

    private static EditText field(Context ctx, String hint, int max, boolean multiline) {
        EditText e = new EditText(ctx);
        e.setHint(hint);
        e.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        e.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        e.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});
        if (multiline) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            e.setMaxLines(4);
        } else {
            e.setSingleLine(true);
        }
        return e;
    }
}
