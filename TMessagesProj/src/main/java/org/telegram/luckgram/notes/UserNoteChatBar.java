/*
 * Luckgram — private notes (spec 05).
 * Licensed under GNU GPL v. 2 or later.
 */

package org.telegram.luckgram.notes;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Thin, collapsible bar shown in the chat top panel (below the pinned message bar) when the peer
 * has a private note. Collapsed: header + first line. Expanded: header + full note + Edit.
 */
@SuppressLint("ViewConstructor")
public class UserNoteChatBar extends FrameLayout {

    public interface Delegate {
        void onEditNote();

        void onExpandedChanged(boolean expanded);
    }

    private final Theme.ResourcesProvider resourcesProvider;
    private final Delegate delegate;
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final TextView titleView;
    private final TextView noteView;
    private final TextView editButton;
    private final ImageView arrowView;

    private String note = "";
    private boolean expanded;

    public UserNoteChatBar(Context context, Theme.ResourcesProvider resourcesProvider, Delegate delegate) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        this.delegate = delegate;
        setWillNotDraw(false);
        setBackground(Theme.getSelectorDrawable(false));

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 23, 7, 44, 7));

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        column.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        noteView = new TextView(context);
        noteView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        noteView.setEllipsize(TextUtils.TruncateAt.END);
        column.addView(noteView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));

        editButton = new TextView(context);
        editButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        editButton.setTypeface(AndroidUtilities.bold());
        editButton.setPadding(0, AndroidUtilities.dp(6), AndroidUtilities.dp(12), AndroidUtilities.dp(2));
        editButton.setOnClickListener(v -> delegate.onEditNote());
        column.addView(editButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        arrowView = new ImageView(context);
        arrowView.setScaleType(ImageView.ScaleType.CENTER);
        arrowView.setImageResource(R.drawable.arrow_more);
        arrowView.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_inappPlayerClose, resourcesProvider) & 0x19ffffff, 1, AndroidUtilities.dp(14)));
        arrowView.setOnClickListener(v -> setExpanded(!expanded, true));
        addView(arrowView, LayoutHelper.createFrame(36, 48, Gravity.RIGHT | Gravity.TOP, 0, 0, 2, 0));

        setOnClickListener(v -> {
            if (expanded) {
                delegate.onEditNote();
            } else {
                setExpanded(true, true);
            }
        });
        updateColors();
        applyState();
    }

    public void setNote(String note, boolean expanded) {
        this.note = note == null ? "" : note;
        this.expanded = expanded;
        applyState();
    }

    private void setExpanded(boolean value, boolean notify) {
        if (expanded == value) {
            return;
        }
        expanded = value;
        applyState();
        if (notify) {
            delegate.onExpandedChanged(expanded);
        }
    }

    private void applyState() {
        titleView.setText(UserNotesStrings.barTitle());
        editButton.setText(UserNotesStrings.edit());
        if (expanded) {
            noteView.setSingleLine(false);
            noteView.setMaxLines(12);
            noteView.setText(note);
            editButton.setVisibility(VISIBLE);
            arrowView.setRotation(180);
            arrowView.setContentDescription(UserNotesStrings.collapse());
        } else {
            noteView.setSingleLine(true);
            noteView.setMaxLines(1);
            noteView.setText(UserNotesStore.firstLine(note));
            editButton.setVisibility(GONE);
            arrowView.setRotation(0);
            arrowView.setContentDescription(UserNotesStrings.expand());
        }
        setContentDescription(UserNotesStrings.barTitle() + "\n" + (expanded ? note : UserNotesStore.firstLine(note)));
        requestLayout();
    }

    public void updateColors() {
        titleView.setTextColor(Theme.getColor(Theme.key_chat_topPanelTitle, resourcesProvider));
        noteView.setTextColor(Theme.getColor(Theme.key_chat_topPanelMessage, resourcesProvider));
        editButton.setTextColor(Theme.getColor(Theme.key_chat_topPanelTitle, resourcesProvider));
        arrowView.setColorFilter(new android.graphics.PorterDuffColorFilter(Theme.getColor(Theme.key_chat_topPanelClose, resourcesProvider), android.graphics.PorterDuff.Mode.MULTIPLY));
        linePaint.setColor(Theme.getColor(Theme.key_chat_topPanelLine, resourcesProvider));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // Accent line on the left, like the pinned message bar.
        float l = AndroidUtilities.dp(13);
        float t = AndroidUtilities.dp(8);
        float r = l + AndroidUtilities.dp(2);
        float b = getMeasuredHeight() - AndroidUtilities.dp(8);
        canvas.drawRoundRect(l, t, r, b, AndroidUtilities.dp(1), AndroidUtilities.dp(1), linePaint);
    }
}
