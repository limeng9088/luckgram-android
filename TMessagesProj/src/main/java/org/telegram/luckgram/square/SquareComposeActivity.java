package org.telegram.luckgram.square;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramAIError;
import org.telegram.luckgram.LuckgramSettings;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.List;

/**
 * New post (spec 26 item 4): text up to 1000 characters and up to 9 photos. Photos are prepared
 * on a worker thread ({@link SquareImages}: ≤ 2048 px long edge, EXIF removed), uploaded through
 * presigned URLs ({@code POST /v1/square/uploads}), then the post is created and shown as
 * "under review" until the server approves it.
 */
public class SquareComposeActivity extends UniversalFragment {

    private static final int MENU_SEND = 1;
    private static final int ID_TEXT = 1;
    private static final int ID_IMAGES = 2;
    private static final int ID_ADD = 3;
    private static final int ID_CLEAR = 4;
    private static final int REQUEST_PICK = 0x5173;

    private final SquareLogic.Texts tx = SquareUi.tx();
    private final ArrayList<SquareImages.Prepared> images = new ArrayList<>();
    private EditTextBoldCursor editText;
    private LinearLayout imagesRow;
    private String draft = "";
    private boolean preparing;
    private boolean publishing;
    private ActionBarMenuItem sendItem;
    private LuckgramAI.Request inFlight;

    @Override
    protected CharSequence getTitle() {
        return tx.composeTitle();
    }

    @Override
    public View createView(Context context) {
        View v = super.createView(context);
        ActionBarMenu menu = actionBar.createMenu();
        sendItem = menu.addItem(MENU_SEND, R.drawable.ic_ab_done);
        sendItem.setContentDescription(tx.publish());
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_SEND) {
                    publish();
                }
            }
        });
        return v;
    }

    @Override
    public void onFragmentDestroy() {
        if (inFlight != null) inFlight.cancel();
        super.onFragmentDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (editText != null) {
            editText.requestFocus();
            AndroidUtilities.showKeyboard(editText);
        }
    }

    private void update() {
        if (listView != null) listView.adapter.update(true);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        Context ctx = getContext();
        if (editText == null) {
            editText = new EditTextBoldCursor(ctx);
            editText.setHint(tx.textHint());
            editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            editText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            editText.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
            editText.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            editText.setBackgroundDrawable(null);
            editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            editText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(SquareLogic.TEXT_MAX * 2)});
            editText.setMinLines(5);
            editText.setGravity(Gravity.TOP | Gravity.START);
            editText.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(14), AndroidUtilities.dp(20), AndroidUtilities.dp(14));
            editText.setText(draft);
        }
        FrameLayout textBox = new FrameLayout(ctx);
        textBox.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        if (editText.getParent() instanceof FrameLayout) {
            ((FrameLayout) editText.getParent()).removeView(editText);
        }
        textBox.addView(editText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        items.add(UItem.asCustom(ID_TEXT, textBox));
        items.add(UItem.asShadow(null));

        if (!images.isEmpty() || preparing) {
            items.add(UItem.asCustom(ID_IMAGES, imagesView(ctx)));
        }
        if (images.size() < SquareLogic.IMAGES_MAX) {
            UItem add = UItem.asButton(ID_ADD, R.drawable.msg_photos, tx.addImages(images.size())).accent();
            if (preparing || publishing) add.setEnabled(false);
            items.add(add);
        }
        if (!images.isEmpty()) {
            items.add(UItem.asButton(ID_CLEAR, R.drawable.msg_delete, tx.removeImages()).red());
        }
        items.add(UItem.asShadow(tx.imagesInfo() + "\n\n" + tx.dailyLimitInfo(maxPostsPerDay())));
    }

    private static long maxPostsPerDay() {
        return LuckgramSettings.remoteLong("square.max_posts_per_day", SquareLogic.DEFAULT_MAX_POSTS_PER_DAY);
    }

    private View imagesView(Context ctx) {
        HorizontalScrollView scroll = new HorizontalScrollView(ctx);
        scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        scroll.setHorizontalScrollBarEnabled(false);
        imagesRow = new LinearLayout(ctx);
        imagesRow.setOrientation(LinearLayout.HORIZONTAL);
        imagesRow.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(10), AndroidUtilities.dp(16), AndroidUtilities.dp(10));
        for (int i = 0; i < images.size(); i++) {
            final int index = i;
            FrameLayout cell = new FrameLayout(ctx);
            ImageView img = new ImageView(ctx);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Bitmap thumb = images.get(i).thumbnail;
            if (thumb != null && !thumb.isRecycled()) img.setImageBitmap(thumb);
            img.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(6), Theme.getColor(Theme.key_windowBackgroundGray)));
            cell.addView(img, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            TextView remove = new TextView(ctx);
            remove.setText("✕");
            remove.setTextColor(0xFFFFFFFF);
            remove.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            remove.setGravity(Gravity.CENTER);
            remove.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(20), 0x99000000));
            remove.setContentDescription(tx.delete());
            cell.addView(remove, LayoutHelper.createFrame(20, 20, Gravity.TOP | Gravity.END, 0, 2, 2, 0));
            cell.setOnClickListener(vv -> {
                if (publishing) return;
                if (index < images.size()) {
                    SquareImages.Prepared p = images.remove(index);
                    if (p.thumbnail != null) p.thumbnail.recycle();
                    update();
                }
            });
            imagesRow.addView(cell, LayoutHelper.createLinear(84, 84, 0, 0, 8, 0));
        }
        if (preparing) {
            TextView t = new TextView(ctx);
            t.setText(tx.preparingImages());
            t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            t.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            t.setGravity(Gravity.CENTER_VERTICAL);
            imagesRow.addView(t, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 84));
        }
        scroll.addView(imagesRow, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        return scroll;
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_ADD && !preparing && !publishing) {
            pickImages();
        } else if (item.id == ID_CLEAR && !publishing) {
            for (SquareImages.Prepared p : images) {
                if (p.thumbnail != null) p.thumbnail.recycle();
            }
            images.clear();
            update();
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    // ---------------------------------------------------------------- pick & prepare

    private void pickImages() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(intent, REQUEST_PICK);
        } catch (Exception e) {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(intent, REQUEST_PICK);
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_PICK || resultCode != Activity.RESULT_OK || data == null) {
            return;
        }
        ArrayList<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null) uris.add(u);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        int room = SquareLogic.IMAGES_MAX - images.size();
        if (uris.isEmpty() || room <= 0) {
            return;
        }
        while (uris.size() > room) uris.remove(uris.size() - 1);
        preparing = true;
        update();
        LuckgramAI.getInstance().runInBackground(() -> {
            ArrayList<SquareImages.Prepared> prepared = new ArrayList<>();
            boolean failed = false;
            for (Uri u : uris) {
                try {
                    SquareImages.Prepared p = SquareImages.prepare(u);
                    if (p == null) failed = true;
                    else prepared.add(p);
                } catch (Throwable t) {
                    FileLog.e(t);
                    failed = true;
                }
            }
            final boolean anyFailed = failed;
            AndroidUtilities.runOnUIThread(() -> {
                preparing = false;
                for (SquareImages.Prepared p : prepared) {
                    if (images.size() < SquareLogic.IMAGES_MAX) images.add(p);
                    else if (p.thumbnail != null) p.thumbnail.recycle();
                }
                update();
                if (anyFailed) {
                    BulletinFactory.of(SquareComposeActivity.this).createErrorBulletin(tx.imageFailed()).show();
                }
            });
        });
    }

    // ---------------------------------------------------------------- publish

    private void publish() {
        if (publishing || preparing) return;
        String text = editText != null ? editText.getText().toString() : draft;
        draft = text;
        if (!SquareLogic.isValidPost(text, images.size())) {
            BulletinFactory.of(this).createErrorBulletin(SquareLogic.length(text.trim()) > SquareLogic.TEXT_MAX ? tx.tooLong() : tx.needContent()).show();
            return;
        }
        SquareUi.requireProfile(this, () -> {
            publishing = true;
            if (sendItem != null) sendItem.setEnabled(false);
            update();
            if (images.isEmpty()) {
                createPost(text, new ArrayList<>());
            } else {
                requestUploads(text);
            }
        });
    }

    private void requestUploads(String text) {
        try {
            JSONObject body = new JSONObject().put("count", images.size()).put("content_type", SquareImages.CONTENT_TYPE);
            inFlight = LuckgramAI.getInstance().requestJson("POST", "/v1/square/uploads", body, new LuckgramAI.Callback<JSONObject>() {
                @Override
                public void onResult(JSONObject result) {
                    List<SquareLogic.Upload> uploads = SquareLogic.parseUploads(result);
                    if (uploads.size() < images.size()) {
                        fail(new LuckgramAIError(LuckgramAIError.UPSTREAM_UNAVAILABLE, 0));
                        return;
                    }
                    uploadAll(text, uploads);
                }

                @Override
                public void onError(LuckgramAIError error) {
                    fail(error);
                }
            });
        } catch (Exception e) {
            FileLog.e(e);
            fail(new LuckgramAIError(LuckgramAIError.INVALID_REQUEST, 0));
        }
    }

    private void uploadAll(String text, List<SquareLogic.Upload> uploads) {
        final ArrayList<byte[]> payloads = new ArrayList<>();
        for (SquareImages.Prepared p : images) payloads.add(p.jpeg);
        final LuckgramAI.Request req = new LuckgramAI.Request();
        inFlight = req;
        LuckgramAI.getInstance().runInBackground(() -> {
            ArrayList<String> keys = new ArrayList<>();
            LuckgramAIError error = null;
            for (int i = 0; i < payloads.size() && error == null; i++) {
                SquareLogic.Upload u = uploads.get(i);
                try {
                    int status = LuckgramAI.getInstance().putPresignedBlocking(req, u.url, SquareImages.CONTENT_TYPE, payloads.get(i));
                    if (status >= 200 && status < 300) {
                        keys.add(u.key);
                    } else {
                        error = new LuckgramAIError(LuckgramAIError.UPSTREAM_UNAVAILABLE, status);
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                    error = new LuckgramAIError(LuckgramAIError.NETWORK_ERROR, 0);
                }
            }
            final LuckgramAIError err = error;
            AndroidUtilities.runOnUIThread(() -> {
                if (req.isCancelled()) return;
                if (err != null) fail(err);
                else createPost(text, keys);
            });
        });
    }

    private void createPost(String text, List<String> imageKeys) {
        try {
            inFlight = LuckgramAI.getInstance().requestJson("POST", "/v1/square/posts", SquareLogic.postBody(text, imageKeys), new LuckgramAI.Callback<JSONObject>() {
                @Override
                public void onResult(JSONObject result) {
                    publishing = false;
                    SquareActivity.dirty = true;
                    SquareLogic.Post p = SquareLogic.parsePost(result);
                    if (!p.author.id.isEmpty()) SquareUi.myAuthorId = p.author.id;
                    if (editText != null) AndroidUtilities.hideKeyboard(editText);
                    finishFragment();
                    AndroidUtilities.runOnUIThread(() -> {
                        if (getParentLayout() != null && getParentLayout().getLastFragment() != null) {
                            BulletinFactory.of(getParentLayout().getLastFragment()).createSimpleBulletin(R.raw.done, tx.published()).show();
                        }
                    }, 300);
                }

                @Override
                public void onError(LuckgramAIError error) {
                    fail(error);
                }
            });
        } catch (Exception e) {
            FileLog.e(e);
            fail(new LuckgramAIError(LuckgramAIError.INVALID_REQUEST, 0));
        }
    }

    private void fail(LuckgramAIError error) {
        publishing = false;
        if (sendItem != null) sendItem.setEnabled(true);
        update();
        SquareUi.showError(this, error);
    }

    static JSONArray keysArray(List<String> keys) {
        JSONArray a = new JSONArray();
        for (String k : keys) a.put(k);
        return a;
    }
}
