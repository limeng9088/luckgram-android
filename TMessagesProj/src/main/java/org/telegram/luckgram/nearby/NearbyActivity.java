package org.telegram.luckgram.nearby;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;
import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramAIError;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.luckgram.account.LuckgramAccountActivity;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChangeUsernameActivity;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * People Nearby (spec 25, luckgram-specs#63). Flow: S6 link + username check → intro (first time)
 * → location permission → browse; "Visible nearby" uploads the card with a coarsened location.
 * Location is read only when the page opens, on refresh and on renew.
 */
public class NearbyActivity extends UniversalFragment {

    private static final int ID_GO_LINK = 1;
    private static final int ID_GO_USERNAME = 2;
    private static final int ID_INTRO_OK = 3;
    private static final int ID_OPEN_SETTINGS = 4;
    private static final int ID_VISIBLE = 5;
    private static final int ID_RENEW = 6;
    private static final int ID_BIO = 7;
    private static final int ID_PHOTO = 8;
    private static final int ID_REFRESH = 9;
    private static final int ID_PERSON = 1000;
    private static final int MENU_REFRESH = 1;
    private static final int PERMISSION_REQUEST = 0x4e42;

    private final NearbyLogic.Texts tx = new NearbyLogic.Texts(LuckgramStrings.isChinese());

    private Boolean linked;
    private boolean permissionDenied;
    private boolean locating;
    private boolean loadingList;
    private boolean busy;
    private String locationError;
    private List<NearbyLogic.Person> people;
    private final ArrayList<NearbyLogic.Person> shown = new ArrayList<>();
    private double lat = Double.NaN, lon = Double.NaN;
    private LuckgramAI.Request inFlight;
    private Runnable cancelLocation;

    @Override
    protected CharSequence getTitle() {
        return tx.title();
    }

    @Override
    public View createView(Context context) {
        View v = super.createView(context);
        actionBar.createMenu().addItem(MENU_REFRESH, R.drawable.msg_retry);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_REFRESH) {
                    refresh();
                }
            }
        });
        return v;
    }

    @Override
    public boolean onFragmentCreate() {
        loadLink();
        return super.onFragmentCreate();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Back from the account / username pages or system settings.
        if (linked == null || precondition() != NearbyLogic.READY) {
            loadLink();
        } else if (permissionDenied && getParentActivity() != null && NearbyLocation.hasPermission(getParentActivity())) {
            permissionDenied = false;
            refresh();
        }
        update();
    }

    @Override
    public void onFragmentDestroy() {
        if (inFlight != null) inFlight.cancel();
        if (cancelLocation != null) cancelLocation.run();
        super.onFragmentDestroy();
    }

    private void update() {
        if (listView != null) listView.adapter.update(true);
    }

    private String myUsername() {
        return UserObject.getPublicUsername(getUserConfig().getCurrentUser());
    }

    private int precondition() {
        return NearbyLogic.precondition(Boolean.TRUE.equals(linked), myUsername());
    }

    // ---------------------------------------------------------------- loading

    private void loadLink() {
        if (!NearbyController.isFeatureEnabled()) return;
        if (inFlight != null) inFlight.cancel();
        inFlight = LuckgramAI.getInstance().requestJson("GET", "/v1/link", null, new LuckgramAI.Callback<JSONObject>() {
            @Override
            public void onResult(JSONObject result) {
                linked = result.optBoolean("linked", false);
                if (!linked) NearbyController.onUnlinked();
                update();
                if (precondition() == NearbyLogic.READY && NearbyController.introDone()) {
                    syncMe();
                    refresh();
                }
            }

            @Override
            public void onError(LuckgramAIError error) {
                showError(error);
                update();
            }
        });
    }

    private void syncMe() {
        LuckgramAI.getInstance().requestJson("GET", "/v1/nearby/me", null, new LuckgramAI.Callback<JSONObject>() {
            @Override
            public void onResult(JSONObject result) {
                NearbyController.applyState(-1, NearbyLogic.parseMe(result));
                update();
            }

            @Override
            public void onError(LuckgramAIError error) {
                NearbyController.onError(error);
                update();
            }
        });
    }

    /** Page opened / refresh: one location fix, then the list. */
    private void refresh() {
        if (precondition() != NearbyLogic.READY || !NearbyController.introDone() || locating) return;
        if (getParentActivity() == null) return;
        if (!NearbyLocation.hasPermission(getParentActivity())) {
            requestPermission();
            return;
        }
        locate(this::loadList);
    }

    private void locate(Runnable then) {
        if (!NearbyLocation.isServiceOn(getParentActivity())) {
            locationError = tx.locationOff();
            update();
            return;
        }
        locating = true;
        locationError = null;
        update();
        cancelLocation = NearbyLocation.request(getParentActivity(), location -> {
            locating = false;
            cancelLocation = null;
            if (location == null || !NearbyLogic.isValidLocation(location.getLatitude(), location.getLongitude())) {
                locationError = tx.locationFailed();
                update();
                return;
            }
            // Keep only the coarsened value in memory, too.
            lat = NearbyLogic.coarsen(location.getLatitude());
            lon = NearbyLogic.coarsen(location.getLongitude());
            then.run();
        });
    }

    private void loadList() {
        if (Double.isNaN(lat)) return;
        loadingList = true;
        update();
        if (inFlight != null) inFlight.cancel();
        inFlight = LuckgramAI.getInstance().requestJson("GET", NearbyLogic.queryPath(lat, lon), null, new LuckgramAI.Callback<JSONObject>() {
            @Override
            public void onResult(JSONObject result) {
                loadingList = false;
                people = NearbyLogic.parseList(result, myUsername());
                update();
            }

            @Override
            public void onError(LuckgramAIError error) {
                loadingList = false;
                NearbyController.onError(error);
                if ("not_linked".equals(error.code)) linked = false;
                showError(error);
                update();
            }
        });
    }

    private void requestPermission() {
        if (Build.VERSION.SDK_INT < 23 || getParentActivity() == null) return;
        // Coarse is enough for a ~1 km grid; "while using the app" only (no background permission).
        getParentActivity().requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSION_REQUEST);
    }

    @Override
    public void onRequestPermissionsResultFragment(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != PERMISSION_REQUEST) return;
        permissionDenied = getParentActivity() == null || !NearbyLocation.hasPermission(getParentActivity());
        update();
        if (!permissionDenied) refresh();
    }

    // ---------------------------------------------------------------- visible

    private void setVisible(boolean on) {
        if (busy) return;
        if (!on) {
            busy = true;
            NearbyController.turnOff(new LuckgramAI.Callback<JSONObject>() {
                @Override
                public void onResult(JSONObject result) {
                    busy = false;
                    update();
                }

                @Override
                public void onError(LuckgramAIError error) {
                    busy = false;
                    showError(error);
                    update();
                }
            });
            update();
            return;
        }
        if (getParentActivity() == null) return;
        if (!NearbyLocation.hasPermission(getParentActivity())) {
            requestPermission();
            return;
        }
        // Fresh fix for every upload (open / renew).
        locate(this::uploadCard);
    }

    private void uploadCard() {
        TLRPC.User me = getUserConfig().getCurrentUser();
        String username = myUsername();
        if (me == null || TextUtils.isEmpty(username) || Double.isNaN(lat)) return;
        final JSONObject body;
        try {
            body = NearbyLogic.cardBody(UserObject.getUserName(me), NearbyController.bio(), username, lat, lon,
                NearbyController.showPhoto() ? avatarBase64(me) : "");
        } catch (Exception e) {
            FileLog.e(e);
            return;
        }
        final boolean wasVisible = NearbyController.isVisible();
        busy = true;
        update();
        LuckgramAI.getInstance().requestJson("PUT", "/v1/nearby/me", body, new LuckgramAI.Callback<JSONObject>() {
            @Override
            public void onResult(JSONObject result) {
                busy = false;
                NearbyController.applyState(currentAccount, NearbyLogic.parseMe(result.has("visible") ? result : safePut(result)));
                if (wasVisible) {
                    BulletinFactory.of(NearbyActivity.this).createSimpleBulletin(R.raw.done, tx.renewed()).show();
                }
                update();
                loadList();
            }

            @Override
            public void onError(LuckgramAIError error) {
                busy = false;
                NearbyController.onError(error);
                if ("not_linked".equals(error.code)) linked = false;
                showError(error);
                update();
            }
        });
    }

    private static JSONObject safePut(JSONObject o) {
        try {
            return o.put("visible", true);
        } catch (Exception e) {
            return o;
        }
    }

    /** The account's small profile photo (already downloaded, ~160 px JPEG), or null. */
    private String avatarBase64(TLRPC.User me) {
        try {
            if (me.photo == null || me.photo.photo_small == null) return null;
            File f = FileLoader.getInstance(currentAccount).getPathToAttach(me.photo.photo_small, true);
            if (f == null || !f.exists() || f.length() == 0 || f.length() > NearbyLogic.AVATAR_MAX_BYTES * 3 / 4) return null;
            byte[] data = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int off = 0, n;
                while (off < data.length && (n = in.read(data, off, data.length - off)) > 0) off += n;
            }
            return android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private void editBio() {
        Context ctx = getParentActivity();
        if (ctx == null) return;
        EditText edit = new EditText(ctx);
        edit.setText(NearbyController.bio());
        edit.setHint(tx.bioHint());
        edit.setSingleLine(false);
        edit.setMaxLines(3);
        edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(NearbyLogic.BIO_MAX)});
        edit.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        edit.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        FrameLayout box = new FrameLayout(ctx);
        box.addView(edit, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 22, 8, 22, 0));
        new AlertDialog.Builder(ctx)
            .setTitle(tx.bio())
            .setView(box)
            .setPositiveButton(tx.save(), (d, w) -> {
                NearbyController.setBio(edit.getText().toString());
                update();
                if (NearbyController.isVisible()) uploadCard();
            })
            .setNegativeButton(tx.cancel(), null)
            .show();
    }

    // ---------------------------------------------------------------- items

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        shown.clear();
        if (linked == null) {
            items.add(UItem.asButtonLoadingValue(0, tx.title()).setEnabled(false));
            return;
        }
        int pre = precondition();
        if (pre == NearbyLogic.NEED_LINK) {
            items.add(UItem.asHeader(tx.needLinkTitle()));
            items.add(UItem.asButton(ID_GO_LINK, tx.goLink()).accent());
            items.add(UItem.asShadow(tx.needLinkText()));
            return;
        }
        if (pre == NearbyLogic.NEED_USERNAME) {
            items.add(UItem.asHeader(tx.needUsernameTitle()));
            items.add(UItem.asButton(ID_GO_USERNAME, tx.goUsername()).accent());
            items.add(UItem.asShadow(tx.needUsernameText()));
            return;
        }
        if (!NearbyController.introDone()) {
            items.add(UItem.asHeader(tx.introTitle()));
            items.add(UItem.asButton(ID_INTRO_OK, tx.introContinue()).accent());
            items.add(UItem.asShadow(tx.introText()));
            return;
        }

        boolean visible = NearbyController.isVisible();
        items.add(UItem.asHeader(tx.myCard()));
        items.add(UItem.asCheck(ID_VISIBLE, tx.visibleSwitch()).setChecked(visible).setEnabled(!busy && !locating));
        if (visible) {
            items.add(UItem.asButton(ID_RENEW, tx.renew()).accent().setEnabled(!busy && !locating));
        }
        String bio = NearbyController.bio();
        items.add(UItem.asButton(ID_BIO, tx.bio(), bio.isEmpty() ? tx.bioEmpty() : bio));
        items.add(UItem.asCheck(ID_PHOTO, tx.showPhoto()).setChecked(NearbyController.showPhoto()));
        items.add(UItem.asShadow(visible
            ? tx.visibleUntil(org.telegram.messenger.LocaleController.formatDateTime(NearbyController.expiresAt() / 1000, true))
            : tx.visibleOffInfo()));

        if (permissionDenied) {
            items.add(UItem.asButton(ID_OPEN_SETTINGS, tx.openSettings()).accent());
            items.add(UItem.asShadow(tx.noPermission()));
            return;
        }
        if (locationError != null) {
            items.add(UItem.asButton(ID_REFRESH, tx.refresh()).accent());
            items.add(UItem.asShadow(locationError));
            return;
        }
        if (people == null || locating || loadingList) {
            items.add(UItem.asButtonLoadingValue(0, tx.loading()).setEnabled(false));
            return;
        }
        if (people.isEmpty()) {
            items.add(UItem.asShadow(tx.empty()));
            return;
        }
        Map<String, List<NearbyLogic.Person>> groups = NearbyLogic.group(people);
        for (Map.Entry<String, List<NearbyLogic.Person>> g : groups.entrySet()) {
            items.add(UItem.asHeader(tx.bucket(g.getKey())));
            for (NearbyLogic.Person p : g.getValue()) {
                items.add(UItem.asCustom(ID_PERSON + shown.size(), personView(p)));
                shown.add(p);
            }
        }
        items.add(UItem.asShadow(null));
    }

    private View personView(NearbyLogic.Person p) {
        Context ctx = getContext();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(8), AndroidUtilities.dp(16), AndroidUtilities.dp(8));
        // Custom rows aren't clickable through UniversalAdapter, so the row handles the tap itself.
        row.setBackground(Theme.getSelectorDrawable(true));
        row.setOnClickListener(v -> showPerson(p));
        BackupImageView avatar = new BackupImageView(ctx);
        avatar.setRoundRadius(AndroidUtilities.dp(23));
        AvatarDrawable placeholder = new AvatarDrawable();
        placeholder.setInfo(p.id.hashCode(), p.displayName, null);
        if (p.avatarUrl != null) {
            avatar.setImage(p.avatarUrl, "46_46", placeholder);
        } else {
            avatar.setImageDrawable(placeholder);
        }
        row.addView(avatar, LayoutHelper.createLinear(46, 46));
        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(ctx);
        name.setText(p.displayName);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        name.setTypeface(AndroidUtilities.bold());
        name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(name);
        TextView sub = new TextView(ctx);
        sub.setText(p.bio.isEmpty() ? tx.bucket(p.bucket) : p.bio);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        sub.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        sub.setMaxLines(2);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(sub);
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 14, 0, 0, 0));
        return row;
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id >= ID_PERSON && item.id - ID_PERSON < shown.size()) {
            showPerson(shown.get(item.id - ID_PERSON));
            return;
        }
        switch (item.id) {
            case ID_GO_LINK:
                presentFragment(new LuckgramAccountActivity());
                break;
            case ID_GO_USERNAME:
                presentFragment(new ChangeUsernameActivity());
                break;
            case ID_INTRO_OK:
                NearbyController.setIntroDone();
                update();
                syncMe();
                refresh();
                break;
            case ID_OPEN_SETTINGS:
                try {
                    Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    i.setData(Uri.parse("package:" + getParentActivity().getPackageName()));
                    getParentActivity().startActivity(i);
                } catch (Exception e) {
                    FileLog.e(e);
                }
                break;
            case ID_VISIBLE:
                setVisible(!NearbyController.isVisible());
                break;
            case ID_RENEW:
                setVisible(true);
                break;
            case ID_BIO:
                editBio();
                break;
            case ID_PHOTO:
                NearbyController.setShowPhoto(!NearbyController.showPhoto());
                update();
                if (NearbyController.isVisible()) uploadCard();
                break;
            case ID_REFRESH:
                refresh();
                break;
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    // ---------------------------------------------------------------- card actions

    private void showPerson(NearbyLogic.Person p) {
        if (getParentActivity() == null) return;
        String msg = (p.bio.isEmpty() ? "" : p.bio + "\n\n") + "@" + p.username + " · " + tx.bucket(p.bucket);
        new AlertDialog.Builder(getParentActivity())
            .setTitle(p.displayName)
            .setMessage(msg)
            .setPositiveButton(tx.sendMessage(), (d, w) -> getMessagesController().openByUserName(p.username, this, 1))
            .setNeutralButton(tx.report(), (d, w) -> showReport(p))
            .setNegativeButton(tx.hide(), (d, w) -> confirmHide(p))
            .show();
    }

    private void showReport(NearbyLogic.Person p) {
        if (getParentActivity() == null) return;
        CharSequence[] labels = new CharSequence[NearbyLogic.REPORT_REASONS.length];
        for (int i = 0; i < labels.length; i++) labels[i] = tx.reportReason(NearbyLogic.REPORT_REASONS[i]);
        new AlertDialog.Builder(getParentActivity())
            .setTitle(tx.reportTitle())
            .setItems(labels, (d, which) -> {
                try {
                    LuckgramAI.getInstance().requestJson("POST", "/v1/nearby/report", NearbyLogic.reportBody(p.id, NearbyLogic.REPORT_REASONS[which]), new LuckgramAI.Callback<JSONObject>() {
                        @Override
                        public void onResult(JSONObject result) {
                            BulletinFactory.of(NearbyActivity.this).createSimpleBulletin(R.raw.done, tx.reported()).show();
                        }

                        @Override
                        public void onError(LuckgramAIError error) {
                            showError(error);
                        }
                    });
                } catch (Exception e) {
                    FileLog.e(e);
                }
            })
            .setNegativeButton(tx.cancel(), null)
            .show();
    }

    private void confirmHide(NearbyLogic.Person p) {
        if (getParentActivity() == null) return;
        new AlertDialog.Builder(getParentActivity())
            .setTitle(tx.hide())
            .setMessage(tx.hideConfirm(p.displayName))
            .setPositiveButton(tx.hide(), (d, w) -> {
                try {
                    LuckgramAI.getInstance().requestJson("POST", "/v1/blocks", new JSONObject().put("user", p.id), new LuckgramAI.Callback<JSONObject>() {
                        @Override
                        public void onResult(JSONObject result) {
                            if (people != null) {
                                ArrayList<NearbyLogic.Person> next = new ArrayList<>(people);
                                next.remove(p);
                                people = next;
                            }
                            update();
                            BulletinFactory.of(NearbyActivity.this).createSimpleBulletin(R.raw.done, tx.hidden()).show();
                        }

                        @Override
                        public void onError(LuckgramAIError error) {
                            showError(error);
                        }
                    });
                } catch (Exception e) {
                    FileLog.e(e);
                }
            })
            .setNegativeButton(tx.cancel(), null)
            .show();
    }

    private void showError(LuckgramAIError error) {
        if (error == null || LuckgramAIError.CANCELLED.equals(error.code) || getParentActivity() == null) return;
        String text;
        if (LuckgramAIError.RATE_LIMITED.equals(error.code)) {
            text = tx.rateLimited(error.retryAfterSeconds);
        } else if ("banned".equals(error.code)) {
            text = tx.banned();
        } else if ("not_linked".equals(error.code)) {
            text = tx.notLinked();
        } else {
            text = error.getLocalizedMessage();
        }
        BulletinFactory.of(this).createErrorBulletin(text).show();
    }
}
