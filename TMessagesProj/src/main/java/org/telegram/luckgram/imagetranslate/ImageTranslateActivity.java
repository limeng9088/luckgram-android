package org.telegram.luckgram.imagetranslate;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.telegram.luckgram.LuckgramAIError;
import org.telegram.luckgram.translateout.TranslateOutLanguageSheet;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * specs#108: the translated image. The translations are drawn over the original at each text block
 * (needs 3); the header 「日本語 → 中文 ▾」 changes the language (needs 2); bottom: original /
 * translation, copy, save, share, and — from the gallery preview — send the translated image (needs 4).
 * Long-press a block for its original and translation. Same layout and texts as iOS.
 */
public class ImageTranslateActivity extends BaseFragment {

    /** Gallery preview (needs 4): receives the translated image file to send instead of the original. */
    public interface SendTranslated {
        void send(File translatedImage);
    }

    private final Bitmap original;
    private final List<ImageTranslateRenderer.Block> blocks;
    private final String imageLanguage;
    private final long dialogId;
    private final SendTranslated sender;

    private Bitmap translated;
    private boolean showingTranslation = true;
    private String toLang;
    private String fromLang;
    private int generation;

    private ImageTranslateView imageView;
    private TextView toggleButton;
    private TextView statusView;
    private final ArrayList<File> tempFiles = new ArrayList<>();

    /**
     * @param imageLanguage the image's language from OCR (BCP-47) or null.
     * @param sender non-null in the gallery preview.
     */
    public ImageTranslateActivity(Bitmap original, List<ImageTranslateRenderer.Block> blocks, String imageLanguage, long dialogId, SendTranslated sender) {
        this.original = original;
        this.blocks = blocks;
        this.imageLanguage = imageLanguage;
        this.dialogId = dialogId;
        this.sender = sender;
    }

    @Override
    public boolean isLightStatusBar() {
        return false;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackgroundColor(0xff000000);
        actionBar.setItemsColor(0xffffffff, false);
        actionBar.setItemsBackgroundColor(0x22ffffff, false);
        actionBar.setTitleColor(0xffffffff);
        actionBar.setBackButtonImage(org.telegram.messenger.R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(false);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });
        actionBar.setOnClickListener(v -> changeLanguage());

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(0xff000000);
        fragmentView = root;

        imageView = new ImageTranslateView(context);
        imageView.setBitmap(original);
        imageView.setOnBlockLongPress(this::showBlock);
        root.addView(imageView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0, 0, 0, 64));

        statusView = new TextView(context);
        statusView.setTextColor(0xffffffff);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        statusView.setBackground(Theme.createRoundRectDrawable(dp(16), 0xaa000000));
        statusView.setPadding(dp(14), dp(7), dp(14), dp(7));
        root.addView(statusView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 16, 0, 0));

        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xff000000);
        toggleButton = barButton(context, ImageTranslateSettings.originalText(), v -> toggle());
        bar.addView(toggleButton, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f));
        bar.addView(barButton(context, ImageTranslateSettings.copyText(), v -> copy()), LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f));
        if (sender != null) {
            bar.addView(barButton(context, ImageTranslateSettings.sendTranslatedText(), v -> send()), LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1.4f));
        } else {
            bar.addView(barButton(context, ImageTranslateSettings.saveText(), v -> save()), LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f));
            bar.addView(barButton(context, ImageTranslateSettings.shareText(), v -> share()), LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f));
        }
        root.addView(bar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 64, Gravity.BOTTOM));

        toLang = ImageTranslateSettings.targetLanguage(getCurrentAccount(), dialogId);
        updateHeader();
        if (!ImageTranslateSettings.hasChosen(dialogId)) {
            // needs 2: first use asks; the default stays the reading translation language until then
            AndroidUtilities.runOnUIThread(this::changeLanguage, 300);
        }
        translate();
        return fragmentView;
    }

    private TextView barButton(Context context, String text, View.OnClickListener l) {
        TextView b = new TextView(context);
        b.setText(text);
        b.setTextColor(0xffffffff);
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        b.setPadding(dp(4), 0, dp(4), 0);
        b.setBackground(Theme.createSelectorDrawable(0x33ffffff, Theme.RIPPLE_MASK_ALL));
        b.setOnClickListener(l);
        return b;
    }

    private void updateHeader() {
        String from = fromLang != null ? fromLang : imageLanguage;
        actionBar.setTitle(ImageTranslateLogic.header(from == null ? null : ImageTranslateSettings.languageName(from), ImageTranslateSettings.languageName(toLang)));
    }

    private void status(String text) {
        if (statusView == null) return;
        statusView.setVisibility(text == null ? View.GONE : View.VISIBLE);
        statusView.setText(text);
    }

    // ---------------------------------------------------------------- translate

    private void translate() {
        final int gen = ++generation;
        ArrayList<String> texts = new ArrayList<>();
        boolean any = false;
        for (ImageTranslateRenderer.Block b : blocks) {
            b.translation = null;
            boolean go = ImageTranslateLogic.shouldTranslate(b.original, null, imageLanguage, toLang);
            texts.add(go ? b.original : null);
            any |= go;
        }
        if (!any) {
            status(ImageTranslateSettings.noTextText());
            showTranslated(original);
            return;
        }
        status(ImageTranslateSettings.translatingText());
        ImageTranslator.translate(getCurrentAccount(), texts, toLang, (translations, from, error) -> {
            if (gen != generation || isFinished) return;
            if (error != null) {
                status(null);
                toast(ImageTranslator.failureMessage(error));
                return;
            }
            for (int i = 0; i < blocks.size(); i++) {
                blocks.get(i).translation = translations.get(i);
            }
            if (from != null) fromLang = from;
            updateHeader();
            Utilities.globalQueue.postRunnable(() -> {
                Bitmap rendered = ImageTranslateRenderer.render(original, blocks);
                AndroidUtilities.runOnUIThread(() -> {
                    if (gen != generation || isFinished) {
                        rendered.recycle();
                        return;
                    }
                    status(null);
                    showTranslated(rendered);
                });
            });
        });
    }

    private void showTranslated(Bitmap rendered) {
        Bitmap old = translated;
        translated = rendered;
        if (old != null && old != original && old != rendered) old.recycle();
        showingTranslation = true;
        imageView.setBitmap(translated);
        toggleButton.setText(ImageTranslateSettings.originalText());
    }

    private void toggle() {
        if (translated == null) return;
        showingTranslation = !showingTranslation;
        imageView.setBitmap(showingTranslation ? translated : original);
        toggleButton.setText(showingTranslation ? ImageTranslateSettings.originalText() : ImageTranslateSettings.translatedText());
    }

    /** needs 2: pick a language → (in a chat) only this chat / as default → translate again. */
    private void changeLanguage() {
        if (getParentActivity() == null) return;
        new TranslateOutLanguageSheet(getParentActivity(), getCurrentAccount(), ImageTranslateLogic.base(toLang), getResourceProvider(), code -> {
            if (dialogId == 0) {
                apply(code, false);
                return;
            }
            // #105's 「仅本聊天 / 设为默认」 prompt (it only asks; the image language is stored here)
            org.telegram.luckgram.translatein.LuckgramTranslateIn.askScope(this, ImageTranslateSettings.languageName(code), chatOnly -> apply(code, chatOnly));
        }).show();
    }

    private void apply(String code, boolean onlyThisChat) {
        ImageTranslateSettings.setLanguage(dialogId, code, onlyThisChat);
        toLang = code;
        fromLang = null;
        updateHeader();
        translate();
    }

    // ---------------------------------------------------------------- long press

    private void showBlock(float nx, float ny) {
        int i = ImageTranslateRenderer.hitTest(blocks, nx, ny);
        if (i < 0 || getParentActivity() == null) return;
        ImageTranslateRenderer.Block b = blocks.get(i);
        StringBuilder sb = new StringBuilder();
        sb.append(ImageTranslateSettings.originalLabel()).append('\n').append(b.original);
        if (!TextUtils.isEmpty(b.translation)) {
            sb.append("\n\n").append(ImageTranslateSettings.translationLabel()).append('\n').append(b.translation);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setMessage(sb.toString());
        builder.setPositiveButton(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.Copy), (d, w) -> {
            AndroidUtilities.addToClipboard(TextUtils.isEmpty(b.translation) ? b.original : b.translation);
            toast(ImageTranslateSettings.copiedText());
        });
        builder.setNegativeButton(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.Close), null);
        showDialog(builder.create());
    }

    // ---------------------------------------------------------------- actions

    private void copy() {
        ArrayList<String> originals = new ArrayList<>();
        ArrayList<String> translations = new ArrayList<>();
        for (ImageTranslateRenderer.Block b : blocks) {
            originals.add(b.original);
            translations.add(b.translation);
        }
        AndroidUtilities.addToClipboard(ImageTranslateLogic.copyText(originals, translations));
        toast(ImageTranslateSettings.copiedText());
    }

    /** The translated image as a JPEG in the app's private cache (shared through the FileProvider). */
    private File writeTranslated() {
        if (translated == null) return null;
        try {
            File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), "cache/luckgram-image-translate");
            if (!dir.exists() && !dir.mkdirs()) return null;
            File f = new File(dir, "translated-" + System.currentTimeMillis() + ".jpg");
            try (FileOutputStream out = new FileOutputStream(f)) {
                translated.compress(Bitmap.CompressFormat.JPEG, 92, out);
            }
            tempFiles.add(f);
            return f;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private void save() {
        File f = writeTranslated();
        if (f == null || getParentActivity() == null) return;
        MediaController.saveFile(f.getAbsolutePath(), getParentActivity(), 0, null, "image/jpeg", uri -> toast(ImageTranslateSettings.savedText()));
    }

    private void share() {
        File f = writeTranslated();
        if (f == null || getParentActivity() == null) return;
        try {
            Uri uri = FileProvider.getUriForFile(getParentActivity(), ApplicationLoader.getApplicationId() + ".provider", f);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("image/jpeg");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getParentActivity().startActivity(Intent.createChooser(intent, ImageTranslateSettings.shareText()));
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void send() {
        File f = writeTranslated();
        if (f == null) return;
        tempFiles.remove(f); // the sender owns it now
        sender.send(f);
        finishFragment();
    }

    private void toast(String text) {
        if (getParentActivity() != null && text != null) {
            Toast.makeText(getParentActivity(), text, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        generation++;
        if (translated != null && translated != original) {
            translated.recycle();
            translated = null;
        }
        // Saved / shared copies are made by the gallery and the share target; ours go now.
        AndroidUtilities.runOnUIThread(() -> {
            for (File f : tempFiles) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }, 60_000);
    }
}
