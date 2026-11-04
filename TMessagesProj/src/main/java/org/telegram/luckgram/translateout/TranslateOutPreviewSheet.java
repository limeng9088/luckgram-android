package org.telegram.luckgram.translateout;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

/**
 * specs#104 §3: preview before sending: the original (small) and the translation, with
 * 「发送译文」「发送原文」「编辑」.
 */
public class TranslateOutPreviewSheet extends BottomSheet {

    public interface Actions {
        void sendTranslation();

        void sendOriginal();

        void edit();
    }

    public TranslateOutPreviewSheet(Context context, CharSequence original, CharSequence translation, String languageName,
                                    Theme.ResourcesProvider resourcesProvider, Actions actions) {
        super(context, false, resourcesProvider);
        fixNavigationBar();
        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);

        final TextView title = new TextView(context);
        title.setText(TranslateOutStrings.previewTitle(languageName));
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        layout.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 4, 22, 12));

        final TextView originalView = new TextView(context);
        originalView.setText(original);
        originalView.setTextColor(Theme.getColor(Theme.key_dialogTextGray3, resourcesProvider));
        originalView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        originalView.setMaxLines(4);
        originalView.setEllipsize(TextUtils.TruncateAt.END);
        layout.addView(originalView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 0, 22, 8));

        final TextView translationView = new TextView(context);
        translationView.setText(translation);
        translationView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        translationView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        translationView.setMaxLines(12);
        translationView.setEllipsize(TextUtils.TruncateAt.END);
        translationView.setTextIsSelectable(true);
        layout.addView(translationView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 0, 22, 16));

        final ButtonWithCounterView send = new ButtonWithCounterView(context, true, resourcesProvider);
        send.setText(TranslateOutStrings.sendTranslation(), false);
        send.setOnClickListener(v -> {
            dismiss();
            actions.sendTranslation();
        });
        layout.addView(send, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 0, 16, 8));

        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final ButtonWithCounterView sendOriginal = new ButtonWithCounterView(context, false, resourcesProvider);
        sendOriginal.setText(TranslateOutStrings.sendOriginal(), false);
        sendOriginal.setOnClickListener(v -> {
            dismiss();
            actions.sendOriginal();
        });
        row.addView(sendOriginal, LayoutHelper.createLinear(0, 48, 1f, 0, 0, 4, 0));
        final ButtonWithCounterView edit = new ButtonWithCounterView(context, false, resourcesProvider);
        edit.setText(TranslateOutStrings.edit(), false);
        edit.setOnClickListener(v -> {
            dismiss();
            actions.edit();
        });
        row.addView(edit, LayoutHelper.createLinear(0, 48, 1f, 4, 0, 0, 0));
        layout.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 0, 16, 8));

        setCustomView(layout);
    }
}
