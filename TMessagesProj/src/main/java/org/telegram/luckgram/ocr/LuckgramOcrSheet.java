package org.telegram.luckgram.ocr;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Spannable;
import android.text.Selection;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramAIError;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.luckgram.translatein.LuckgramTranslateIn;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.PhotoViewer;

import java.io.File;

/**
 * specs#106 需求 3: the recognized text. The image on top with the text areas highlighted (tap one
 * to select just that block), the text below (keeps line breaks, can be selected), and Copy all /
 * Translate (#105's reading language) / Ask AI (into the assistant's input) / Share.
 */
public final class LuckgramOcrSheet extends BottomSheet {

    /** Recognizes {@code image} and opens the sheet; shows a short message if nothing is found. */
    public static void open(BaseFragment host, File image) {
        if (host == null || host.getParentActivity() == null) return;
        Context context = host.getParentActivity();
        LuckgramOcr.recognize(context, image, new LuckgramOcr.Callback<OcrResult>() {
            @Override
            public void onResult(OcrResult result) {
                if (!result.hasText()) {
                    toast(host, LuckgramStrings.get("图片里没有识别到文字", "No text found in this image"));
                    return;
                }
                new LuckgramOcrSheet(host, image, result).show();
            }

            @Override
            public void onError(String code) {
                toast(host, LuckgramOcr.ERROR_NO_IMAGE.equals(code)
                    ? LuckgramStrings.get("图片还没有下载完", "The image hasn't finished downloading")
                    : LuckgramStrings.get("识别失败，请重试", "Couldn't recognize the text. Please try again."));
            }
        });
    }

    private static void toast(BaseFragment host, String text) {
        if (PhotoViewer.hasInstance() && PhotoViewer.getInstance().isVisible()) {
            android.widget.Toast.makeText(host.getParentActivity(), text, android.widget.Toast.LENGTH_SHORT).show();
        } else {
            BulletinFactory.of(host).createSimpleBulletin(R.raw.info, text).show();
        }
    }

    private final BaseFragment host;
    private final OcrResult result;
    private final TextView textView;
    private final TextView translationView;
    private boolean translating;

    @SuppressLint("ClickableViewAccessibility")
    private LuckgramOcrSheet(BaseFragment host, File image, OcrResult result) {
        super(host.getParentActivity(), false, host.getResourceProvider());
        this.host = host;
        this.result = result;
        fixNavigationBar();
        Context c = getContext();
        Theme.ResourcesProvider rp = host.getResourceProvider();
        int textColor = Theme.getColor(Theme.key_dialogTextBlack, rp);
        int grayColor = Theme.getColor(Theme.key_dialogTextGray3, rp);
        int accent = Theme.getColor(Theme.key_dialogTextBlue2, rp);

        LinearLayout layout = new LinearLayout(c);
        layout.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(c);
        title.setText(LuckgramStrings.get("识别出的文字", "Recognized Text"));
        title.setTextColor(textColor);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        layout.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 12, 22, 8));

        ScrollView scroll = new ScrollView(c);
        LinearLayout column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(column);

        Bitmap bitmap = LuckgramOcr.decode(image);
        if (bitmap != null) {
            int maxH = (int) (AndroidUtilities.displaySize.y * 0.32f);
            int maxW = AndroidUtilities.displaySize.x - dp(44);
            float scale = Math.min(maxW / (float) bitmap.getWidth(), maxH / (float) bitmap.getHeight());
            int w = Math.max(1, (int) (bitmap.getWidth() * scale)), h = Math.max(1, (int) (bitmap.getHeight() * scale));
            FrameLayout frame = new FrameLayout(c);
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            iv.setImageBitmap(bitmap);
            frame.addView(iv, new FrameLayout.LayoutParams(w, h));
            Highlights hl = new Highlights(c, result, accent);
            hl.setOnTouchListener((v, e) -> {
                if (e.getAction() == MotionEvent.ACTION_UP) {
                    OcrResult.Block b = result.blockAt(e.getX() / v.getWidth(), e.getY() / v.getHeight());
                    hl.selected = b;
                    hl.invalidate();
                    selectBlock(b);
                }
                return true;
            });
            frame.addView(hl, new FrameLayout.LayoutParams(w, h));
            column.addView(frame, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 22, 0, 22, 10));
        }

        textView = new TextView(c);
        textView.setText(result.text(), TextView.BufferType.SPANNABLE);
        textView.setTextColor(textColor);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        textView.setLineSpacing(dp(2), 1f);
        textView.setTextIsSelectable(true);
        textView.setHighlightColor(Theme.multAlpha(accent, 0.3f));
        column.addView(textView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 0, 22, 8));

        translationView = new TextView(c);
        translationView.setTextColor(grayColor);
        translationView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        translationView.setLineSpacing(dp(2), 1f);
        translationView.setTextIsSelectable(true);
        translationView.setVisibility(View.GONE);
        column.addView(translationView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 4, 22, 8));

        layout.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        LinearLayout buttons = new LinearLayout(c);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(button(c, R.drawable.msg_copy, LuckgramStrings.get("复制全部", "Copy All"), accent, v -> copyAll()), LayoutHelper.createLinear(0, 64, 1f));
        buttons.addView(button(c, R.drawable.msg_translate, LuckgramStrings.get("翻译", "Translate"), accent, v -> translate(false)), LayoutHelper.createLinear(0, 64, 1f));
        buttons.addView(button(c, R.drawable.luckgram_ai, LuckgramStrings.get("问 AI", "Ask AI"), accent, v -> askAi()), LayoutHelper.createLinear(0, 64, 1f));
        buttons.addView(button(c, R.drawable.msg_shareout, LuckgramStrings.get("分享", "Share"), accent, v -> share()), LayoutHelper.createLinear(0, 64, 1f));
        layout.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 64, 8, 4, 8, 8));

        int height = (int) (AndroidUtilities.displaySize.y * 0.8f);
        FrameLayout wrap = new FrameLayout(c);
        wrap.addView(layout, new FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, height));
        setCustomView(wrap);
    }

    private static View button(Context c, int icon, String text, int color, View.OnClickListener l) {
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Theme.createSelectorDrawable(Theme.multAlpha(color, 0.12f), Theme.RIPPLE_MASK_ALL));
        ImageView iv = new ImageView(c);
        iv.setImageResource(icon);
        iv.setColorFilter(new android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN));
        b.addView(iv, LayoutHelper.createLinear(24, 24));
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        t.setSingleLine(true);
        b.addView(t, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
        b.setOnClickListener(l);
        b.setContentDescription(text);
        return b;
    }

    /** The selected text, or everything when nothing is selected. */
    private String currentText() {
        CharSequence s = textView.getText();
        int a = Selection.getSelectionStart(s), b = Selection.getSelectionEnd(s);
        if (a >= 0 && b > a) return s.subSequence(a, b).toString();
        return result.text();
    }

    private void selectBlock(OcrResult.Block b) {
        CharSequence s = textView.getText();
        if (b == null || !(s instanceof Spannable)) return;
        int at = s.toString().indexOf(b.text);
        if (at < 0) return;
        Selection.setSelection((Spannable) s, at, at + b.text.length());
        textView.requestFocus();
    }

    private void copyAll() {
        AndroidUtilities.addToClipboard(result.text());
        BulletinFactory.of(getContainer(), resourcesProvider).createCopyBulletin(LuckgramStrings.get("已复制", "Copied")).show();
    }

    private void translate(boolean picked) {
        if (translating) return;
        String to = LuckgramTranslateIn.targetLanguage();
        if (to == null && !picked) {
            LuckgramTranslateIn.pickTargetLanguage(host, lang -> translate(true));
            return;
        }
        if (to == null) return;
        translating = true;
        translationView.setVisibility(View.VISIBLE);
        translationView.setText(LuckgramStrings.get("正在翻译…", "Translating…"));
        LuckgramAI.getInstance().translate(getContext(), currentText(), to, result.language, new LuckgramAI.Callback<LuckgramAI.Translation>() {
            @Override
            public void onResult(LuckgramAI.Translation t) {
                translating = false;
                translationView.setText(t.text);
            }

            @Override
            public void onError(LuckgramAIError error) {
                translating = false;
                translationView.setText(error.getLocalizedMessage());
            }
        });
    }

    private void askAi() {
        String text = currentText();
        dismiss();
        if (PhotoViewer.hasInstance() && PhotoViewer.getInstance().isVisible()) {
            PhotoViewer.getInstance().closePhoto(false, false);
        }
        host.presentFragment(org.telegram.luckgram.ai.LuckgramAiChatActivity.newConversation(
            LuckgramStrings.get("图片里的文字：\n", "Text from an image:\n") + text + "\n\n"));
    }

    private void share() {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, currentText());
        try {
            getContext().startActivity(Intent.createChooser(i, LuckgramStrings.get("分享文字", "Share text")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable ignore) {
        }
    }

    /** Translucent boxes over the recognized text areas; the tapped one is drawn stronger. */
    private static final class Highlights extends View {
        private final OcrResult result;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        OcrResult.Block selected;

        Highlights(Context c, OcrResult result, int accent) {
            super(c);
            this.result = result;
            fill.setColor(Theme.multAlpha(accent, 0.22f));
            stroke.setColor(accent);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dp(1.5f));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            for (OcrResult.Block b : result.blocks) {
                if (b.box == null) continue;
                r.set(b.box.x * w, b.box.y * h, (b.box.x + b.box.width) * w, (b.box.y + b.box.height) * h);
                fill.setAlpha(b == selected ? 110 : 56);
                canvas.drawRoundRect(r, dp(3), dp(3), fill);
                if (b == selected) canvas.drawRoundRect(r, dp(3), dp(3), stroke);
            }
        }
    }
}
