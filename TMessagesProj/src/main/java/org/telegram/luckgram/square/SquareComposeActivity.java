package org.telegram.luckgram.square;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.MediaStore;
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

import org.json.JSONObject;
import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramAIError;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * New post (spec 26 item 4): text up to 1000 characters plus either up to 9 photos or one short
 * video, picked from the gallery or taken right now. Photos are prepared by {@link SquareImages}
 * (≤ 2048 px, EXIF removed) and videos by {@link SquareVideos} (H.264/AAC MP4 ≤ 1280 px, metadata
 * removed, trimmed with the user's consent); everything goes up through presigned URLs, then the
 * post is created and is public at once.
 */
public class SquareComposeActivity extends UniversalFragment {

    private static final int MENU_SEND = 1;
    private static final int ID_TEXT = 1;
    private static final int ID_MEDIA = 2;
    private static final int ID_ADD = 3;
    private static final int ID_CLEAR = 4;
    private static final int ID_PROGRESS = 5;
    private static final int REQUEST_PICK_IMAGES = 0x5173;
    private static final int REQUEST_PICK_VIDEO = 0x5174;
    private static final int REQUEST_CAPTURE_PHOTO = 0x5175;
    private static final int REQUEST_CAPTURE_VIDEO = 0x5176;

    private final SquareLogic.Texts tx = SquareUi.tx();
    private final ArrayList<SquareImages.Prepared> images = new ArrayList<>();
    private SquareVideos.Prepared video;
    private EditTextBoldCursor editText;
    private String draft = "";
    private boolean preparing;
    private float prepareProgress = -1f;
    private Runnable cancelPrepare;
    private boolean publishing;
    private String progressText;
    private ActionBarMenuItem sendItem;
    private LuckgramAI.Request inFlight;
    private File captureFile;

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
        if (cancelPrepare != null) cancelPrepare.run();
        if (video != null && !publishing) video.delete();
        super.onFragmentDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (editText != null && !preparing) {
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

        if (!images.isEmpty() || video != null) {
            items.add(UItem.asCustom(ID_MEDIA, mediaView(ctx)));
        }
        if (preparing) {
            String label = video == null && images.isEmpty() && prepareProgress >= 0 ? tx.preparingVideo() + " " + Math.round(prepareProgress * 100) + "%" : (prepareProgress >= 0 ? tx.preparingVideo() : tx.preparingImages());
            items.add(UItem.asButtonLoadingValue(ID_PROGRESS, label).setEnabled(false));
        } else if (publishing) {
            items.add(UItem.asButtonLoadingValue(ID_PROGRESS, progressText != null ? progressText : tx.publishing()).setEnabled(false));
        } else {
            if (video == null && images.size() < SquareLogic.IMAGES_MAX) {
                items.add(UItem.asButton(ID_ADD, R.drawable.msg_photos, images.isEmpty() ? tx.addAttachment() : tx.addImages(images.size())).accent());
            }
            if (!images.isEmpty()) {
                items.add(UItem.asButton(ID_CLEAR, R.drawable.msg_delete, tx.removeImages()).red());
            } else if (video != null) {
                items.add(UItem.asButton(ID_CLEAR, R.drawable.msg_delete, tx.removeVideo()).red());
            }
        }
        items.add(UItem.asShadow(tx.attachmentsInfo(SquareUi.maxVideoSeconds()) + "\n\n" + tx.dailyLimitInfo(SquareUi.maxPostsPerDay())));
    }

    private View mediaView(Context ctx) {
        HorizontalScrollView scroll = new HorizontalScrollView(ctx);
        scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(10), AndroidUtilities.dp(16), AndroidUtilities.dp(10));
        if (video != null) {
            row.addView(thumbCell(ctx, video.thumbnail, tx.videoLabel(video.durationSeconds), () -> {
                if (publishing || preparing) return;
                video.delete();
                video = null;
                update();
            }), LayoutHelper.createLinear(140, 84, 0, 0, 8, 0));
        }
        for (int i = 0; i < images.size(); i++) {
            final int index = i;
            row.addView(thumbCell(ctx, images.get(i).thumbnail, null, () -> {
                if (publishing || preparing) return;
                if (index < images.size()) {
                    SquareImages.Prepared p = images.remove(index);
                    if (p.thumbnail != null) p.thumbnail.recycle();
                    update();
                }
            }), LayoutHelper.createLinear(84, 84, 0, 0, 8, 0));
        }
        scroll.addView(row, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        return scroll;
    }

    private View thumbCell(Context ctx, Bitmap thumb, String label, Runnable onRemove) {
        FrameLayout cell = new FrameLayout(ctx);
        ImageView img = new ImageView(ctx);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (thumb != null && !thumb.isRecycled()) img.setImageBitmap(thumb);
        img.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(6), Theme.getColor(Theme.key_windowBackgroundGray)));
        cell.addView(img, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        if (label != null) {
            TextView t = new TextView(ctx);
            t.setText(label);
            t.setTextColor(0xFFFFFFFF);
            t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            t.setPadding(AndroidUtilities.dp(6), AndroidUtilities.dp(2), AndroidUtilities.dp(6), AndroidUtilities.dp(2));
            t.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(8), 0x99000000));
            cell.addView(t, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START, 4, 0, 0, 4));
        }
        TextView remove = new TextView(ctx);
        remove.setText("✕");
        remove.setTextColor(0xFFFFFFFF);
        remove.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        remove.setGravity(Gravity.CENTER);
        remove.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(20), 0x99000000));
        remove.setContentDescription(tx.delete());
        cell.addView(remove, LayoutHelper.createFrame(20, 20, Gravity.TOP | Gravity.END, 0, 2, 2, 0));
        cell.setOnClickListener(v -> onRemove.run());
        return cell;
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_ADD && !preparing && !publishing) {
            chooseAttachment();
        } else if (item.id == ID_CLEAR && !publishing && !preparing) {
            for (SquareImages.Prepared p : images) {
                if (p.thumbnail != null) p.thumbnail.recycle();
            }
            images.clear();
            if (video != null) {
                video.delete();
                video = null;
            }
            update();
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    // ---------------------------------------------------------------- pick & capture

    private void chooseAttachment() {
        if (getParentActivity() == null) return;
        ArrayList<CharSequence> labels = new ArrayList<>();
        ArrayList<Runnable> acts = new ArrayList<>();
        labels.add(tx.chooseImages());
        acts.add(this::pickImages);
        labels.add(tx.takePhoto());
        acts.add(this::capturePhoto);
        if (images.isEmpty()) {
            labels.add(tx.chooseVideo());
            acts.add(this::pickVideo);
            labels.add(tx.recordVideo());
            acts.add(this::captureVideo);
        }
        new AlertDialog.Builder(getParentActivity())
            .setTitle(tx.addAttachment())
            .setItems(labels.toArray(new CharSequence[0]), (d, which) -> AndroidUtilities.runOnUIThread(acts.get(which)))
            .show();
    }

    private void pickImages() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(intent, REQUEST_PICK_IMAGES);
        } catch (Exception e) {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(intent, REQUEST_PICK_IMAGES);
        }
    }

    private void pickVideo() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("video/*");
            startActivityForResult(intent, REQUEST_PICK_VIDEO);
        } catch (Exception e) {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("video/*");
            startActivityForResult(intent, REQUEST_PICK_VIDEO);
        }
    }

    private Uri newCaptureUri(String suffix) {
        captureFile = new File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE), "luckgram_square_capture_" + System.currentTimeMillis() + "_" + Utilities.random.nextInt(Integer.MAX_VALUE) + suffix);
        return androidx.core.content.FileProvider.getUriForFile(ApplicationLoader.applicationContext, ApplicationLoader.getApplicationId() + ".provider", captureFile);
    }

    private void capturePhoto() {
        try {
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            Uri out = newCaptureUri(".jpg");
            intent.putExtra(MediaStore.EXTRA_OUTPUT, out);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(intent, REQUEST_CAPTURE_PHOTO);
        } catch (Exception e) {
            FileLog.e(e);
            BulletinFactory.of(this).createErrorBulletin(tx.imageFailed()).show();
        }
    }

    private void captureVideo() {
        try {
            Intent intent = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
            Uri out = newCaptureUri(".mp4");
            intent.putExtra(MediaStore.EXTRA_OUTPUT, out);
            intent.putExtra(MediaStore.EXTRA_DURATION_LIMIT, (int) SquareUi.maxVideoSeconds());
            intent.putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(intent, REQUEST_CAPTURE_VIDEO);
        } catch (Exception e) {
            FileLog.e(e);
            BulletinFactory.of(this).createErrorBulletin(tx.videoFailed()).show();
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK) {
            return;
        }
        if (requestCode == REQUEST_PICK_IMAGES && data != null) {
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
            addImages(uris);
        } else if (requestCode == REQUEST_CAPTURE_PHOTO && captureFile != null) {
            ArrayList<Uri> uris = new ArrayList<>();
            uris.add(Uri.fromFile(captureFile));
            addImages(uris);
        } else if (requestCode == REQUEST_PICK_VIDEO && data != null && data.getData() != null) {
            addVideo(data.getData());
        } else if (requestCode == REQUEST_CAPTURE_VIDEO) {
            Uri u = data != null && data.getData() != null ? data.getData() : (captureFile != null ? Uri.fromFile(captureFile) : null);
            if (u != null) addVideo(u);
        }
    }

    private void addImages(List<Uri> uris) {
        int room = SquareLogic.IMAGES_MAX - images.size();
        if (uris.isEmpty() || room <= 0) {
            return;
        }
        if (video != null) {
            BulletinFactory.of(this).createErrorBulletin(tx.onlyOneAttachmentType()).show();
            return;
        }
        while (uris.size() > room) uris.remove(uris.size() - 1);
        preparing = true;
        prepareProgress = -1f;
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
            deleteCapture();
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

    private void addVideo(Uri uri) {
        if (!images.isEmpty()) {
            BulletinFactory.of(this).createErrorBulletin(tx.onlyOneAttachmentType()).show();
            return;
        }
        preparing = true;
        prepareProgress = -1f;
        update();
        LuckgramAI.getInstance().runInBackground(() -> {
            SquareVideos.Source source = SquareVideos.open(uri);
            deleteCapture();
            AndroidUtilities.runOnUIThread(() -> {
                if (source == null) {
                    preparing = false;
                    update();
                    BulletinFactory.of(SquareComposeActivity.this).createErrorBulletin(tx.videoFailed()).show();
                    return;
                }
                long max = SquareUi.maxVideoSeconds();
                if (source.durationSeconds() > max) {
                    // Spec 26 item 3: too long → let the user trim (keep the first N seconds) or cancel.
                    if (getParentActivity() == null) {
                        preparing = false;
                        update();
                        return;
                    }
                    new AlertDialog.Builder(getParentActivity())
                        .setTitle(tx.videoTooLongTitle())
                        .setMessage(tx.videoTooLong(max, source.durationSeconds()))
                        .setPositiveButton(tx.trimAndContinue(max), (d, w) -> transcode(source, (int) max))
                        .setNegativeButton(tx.cancel(), (d, w) -> {
                            source.file.delete();
                            preparing = false;
                            update();
                        })
                        .setOnDismissListener(d -> {
                            if (preparing && cancelPrepare == null) {
                                source.file.delete();
                                preparing = false;
                                update();
                            }
                        })
                        .show();
                } else {
                    transcode(source, (int) max);
                }
            });
        });
    }

    private void transcode(SquareVideos.Source source, int keepSeconds) {
        prepareProgress = 0f;
        update();
        cancelPrepare = SquareVideos.prepare(source, keepSeconds, new SquareVideos.Listener() {
            @Override
            public void onProgress(float progress) {
                prepareProgress = progress;
                update();
            }

            @Override
            public void onDone(SquareVideos.Prepared prepared) {
                cancelPrepare = null;
                preparing = false;
                prepareProgress = -1f;
                if (video != null) video.delete();
                video = prepared;
                update();
            }

            @Override
            public void onError(boolean tooBig) {
                cancelPrepare = null;
                preparing = false;
                prepareProgress = -1f;
                update();
                BulletinFactory.of(SquareComposeActivity.this).createErrorBulletin(tooBig ? tx.videoTooBig(SquareVideos.maxBytes() / (1024 * 1024)) : tx.videoFailed()).show();
            }
        });
    }

    private void deleteCapture() {
        try {
            if (captureFile != null && captureFile.exists()) captureFile.delete();
        } catch (Throwable ignore) {
        }
        captureFile = null;
    }

    // ---------------------------------------------------------------- publish

    private void publish() {
        if (publishing || preparing) return;
        String text = editText != null ? editText.getText().toString() : draft;
        draft = text;
        if (!SquareLogic.isValidPost(text, images.size(), video != null)) {
            BulletinFactory.of(this).createErrorBulletin(SquareLogic.length(text.trim()) > SquareLogic.TEXT_MAX ? tx.tooLong() : tx.needContent()).show();
            return;
        }
        SquareUi.requireProfile(this, () -> {
            publishing = true;
            progressText = null;
            if (sendItem != null) sendItem.setEnabled(false);
            update();
            if (video != null) {
                requestVideoUploads(text);
            } else if (images.isEmpty()) {
                createPost(text, new ArrayList<>(), null);
            } else {
                requestImageUploads(text);
            }
        });
    }

    private void requestImageUploads(String text) {
        try {
            inFlight = LuckgramAI.getInstance().requestJson("POST", "/v1/square/uploads", SquareLogic.uploadsBody(images.size(), SquareLogic.IMAGE_CONTENT_TYPE), new LuckgramAI.Callback<JSONObject>() {
                @Override
                public void onResult(JSONObject result) {
                    List<SquareLogic.Upload> uploads = SquareLogic.parseUploads(result);
                    if (uploads.size() < images.size()) {
                        fail(new LuckgramAIError(LuckgramAIError.UPSTREAM_UNAVAILABLE, 0));
                        return;
                    }
                    ArrayList<byte[]> payloads = new ArrayList<>();
                    for (SquareImages.Prepared p : images) payloads.add(p.jpeg);
                    ArrayList<String> types = new ArrayList<>();
                    for (int i = 0; i < payloads.size(); i++) types.add(SquareLogic.IMAGE_CONTENT_TYPE);
                    uploadAll(uploads, payloads, types, keys -> createPost(text, keys, null));
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

    private void requestVideoUploads(String text) {
        progressText = tx.uploadingVideo();
        update();
        try {
            // One slot for the MP4, one for its cover; the server only accepts a video post with both.
            inFlight = LuckgramAI.getInstance().requestJson("POST", "/v1/square/uploads", SquareLogic.uploadsBody(1, SquareLogic.VIDEO_CONTENT_TYPE), new LuckgramAI.Callback<JSONObject>() {
                @Override
                public void onResult(JSONObject videoResult) {
                    List<SquareLogic.Upload> videoUploads = SquareLogic.parseUploads(videoResult);
                    if (videoUploads.isEmpty()) {
                        fail(new LuckgramAIError(LuckgramAIError.UPSTREAM_UNAVAILABLE, 0));
                        return;
                    }
                    try {
                        inFlight = LuckgramAI.getInstance().requestJson("POST", "/v1/square/uploads", SquareLogic.uploadsBody(1, SquareLogic.IMAGE_CONTENT_TYPE), new LuckgramAI.Callback<JSONObject>() {
                            @Override
                            public void onResult(JSONObject coverResult) {
                                List<SquareLogic.Upload> coverUploads = SquareLogic.parseUploads(coverResult);
                                if (coverUploads.isEmpty()) {
                                    fail(new LuckgramAIError(LuckgramAIError.UPSTREAM_UNAVAILABLE, 0));
                                    return;
                                }
                                byte[] mp4;
                                try {
                                    mp4 = SquareVideos.readAll(video.mp4);
                                } catch (Exception e) {
                                    FileLog.e(e);
                                    fail(new LuckgramAIError(LuckgramAIError.INVALID_REQUEST, 0));
                                    return;
                                }
                                ArrayList<SquareLogic.Upload> uploads = new ArrayList<>();
                                uploads.add(videoUploads.get(0));
                                uploads.add(coverUploads.get(0));
                                ArrayList<byte[]> payloads = new ArrayList<>();
                                payloads.add(mp4);
                                payloads.add(video.coverJpeg);
                                ArrayList<String> types = new ArrayList<>();
                                types.add(SquareLogic.VIDEO_CONTENT_TYPE);
                                types.add(SquareLogic.IMAGE_CONTENT_TYPE);
                                uploadAll(uploads, payloads, types, keys -> createPost(text, null, keys));
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

    private interface KeysCallback {
        void done(List<String> keys);
    }

    private void uploadAll(List<SquareLogic.Upload> uploads, List<byte[]> payloads, List<String> contentTypes, KeysCallback then) {
        final LuckgramAI.Request req = new LuckgramAI.Request();
        inFlight = req;
        LuckgramAI.getInstance().runInBackground(() -> {
            ArrayList<String> keys = new ArrayList<>();
            LuckgramAIError error = null;
            for (int i = 0; i < payloads.size() && error == null; i++) {
                SquareLogic.Upload u = uploads.get(i);
                try {
                    int status = LuckgramAI.getInstance().putPresignedBlocking(req, u.url, contentTypes.get(i), payloads.get(i));
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
                else then.done(keys);
            });
        });
    }

    /** {@code videoKeys}: [mp4 key, cover key] for a video post, otherwise null. */
    private void createPost(String text, List<String> imageKeys, List<String> videoKeys) {
        try {
            JSONObject body = videoKeys != null
                ? SquareLogic.postBodyWithVideo(text, videoKeys.get(0), videoKeys.get(1))
                : SquareLogic.postBody(text, imageKeys);
            inFlight = LuckgramAI.getInstance().requestJson("POST", "/v1/square/posts", body, new LuckgramAI.Callback<JSONObject>() {
                @Override
                public void onResult(JSONObject result) {
                    publishing = false;
                    SquareActivity.dirty = true;
                    SquareLogic.Post p = SquareLogic.parsePost(result);
                    if (!p.author.id.isEmpty()) SquareUi.myAuthorId = p.author.id;
                    if (video != null) {
                        video.delete();
                        video = null;
                    }
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
        progressText = null;
        if (sendItem != null) sendItem.setEnabled(true);
        update();
        SquareUi.showError(this, error);
    }
}
