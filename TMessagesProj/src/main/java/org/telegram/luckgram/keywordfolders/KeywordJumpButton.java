/*
 * Luckgram — keyword folders.
 * Licensed under GNU GPL v. 2 or later, like the rest of this Telegram-based project.
 */

package org.telegram.luckgram.keywordfolders;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

/**
 * "Next 'invoice'" pill shown above the input field when a chat is opened from a keyword folder.
 * Each tap scrolls to the next older cached message containing one of the folder's keywords.
 */
public class KeywordJumpButton extends FrameLayout {

    private final ChatActivity fragment;
    private final KeywordFolder folder;
    private final TextView textView;
    private final ArrayList<KeywordFolderController.Hit> hits = new ArrayList<>();
    private int lastJumpedId;
    private boolean dismissed;
    private boolean loading;

    /** Adds the button to {@code container} when {@code chat} was opened from a keyword folder; returns null otherwise. */
    public static KeywordJumpButton attach(ChatActivity chat, FrameLayout container, int dialogFilterId) {
        if (chat == null || container == null || !KeywordFolderController.isEnabled() || !KeywordFolderController.isLocalFilterId(dialogFilterId) || chat.getChatMode() != 0) {
            return null;
        }
        MessagesController.DialogFilter filter = chat.getMessagesController().dialogFiltersById.get(dialogFilterId);
        if (filter == null || filter.keywordFolder == null || filter.keywordFolder.keywords.isEmpty()) {
            return null;
        }
        KeywordJumpButton button = new KeywordJumpButton(chat.getParentActivity() != null ? chat.getParentActivity() : container.getContext(), chat, filter.keywordFolder);
        container.addView(button, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 36, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, 0));
        button.reload(false);
        return button;
    }

    private KeywordJumpButton(Context context, ChatActivity fragment, KeywordFolder folder) {
        super(context);
        this.fragment = fragment;
        this.folder = folder;
        setVisibility(GONE);

        final Theme.ResourcesProvider rp = fragment.getResourceProvider();
        LinearLayout pill = new LinearLayout(context);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(Theme.createRoundRectDrawable(dp(18), Theme.getColor(Theme.key_chat_messagePanelBackground, rp)));
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            pill.setElevation(dp(2));
        }
        addView(pill, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 36, Gravity.CENTER, 4, 0, 4, 0));

        ImageView icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setImageResource(R.drawable.msg_go_down);
        icon.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_chat_messagePanelSend, rp), PorterDuff.Mode.MULTIPLY));
        icon.setRotation(180);
        pill.addView(icon, LayoutHelper.createLinear(28, 28, Gravity.CENTER_VERTICAL, 8, 0, 0, 0));

        textView = new TextView(context);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        textView.setTypeface(AndroidUtilities.bold());
        textView.setTextColor(Theme.getColor(Theme.key_chat_messagePanelSend, rp));
        textView.setSingleLine(true);
        textView.setEllipsize(TextUtils.TruncateAt.END);
        textView.setMaxWidth(dp(180));
        pill.addView(textView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 4, 0, 4, 0));

        ImageView close = new ImageView(context);
        close.setScaleType(ImageView.ScaleType.CENTER);
        close.setImageResource(R.drawable.msg_close);
        close.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_chat_messagePanelIcons, rp), PorterDuff.Mode.MULTIPLY));
        close.setContentDescription(KeywordFolderStrings.close());
        close.setOnClickListener(v -> {
            dismissed = true;
            setVisibility(GONE);
        });
        pill.addView(close, LayoutHelper.createLinear(32, 32, Gravity.CENTER_VERTICAL, 0, 0, 4, 0));

        pill.setOnClickListener(v -> reload(true));
    }

    private int indexOfLast() {
        if (lastJumpedId == 0) {
            return -1;
        }
        for (int i = 0; i < hits.size(); i++) {
            if (hits.get(i).messageId == lastJumpedId) {
                return i;
            }
        }
        return -1;
    }

    private void updateLabel() {
        if (hits.isEmpty()) {
            setVisibility(GONE);
            return;
        }
        int next = indexOfLast() + 1;
        if (next >= hits.size()) {
            next = 0;
        }
        String label = KeywordFolderStrings.jumpNext(hits.get(next).keyword);
        textView.setText(label);
        setContentDescription(label);
        if (!dismissed) {
            setVisibility(VISIBLE);
        }
    }

    /** Reloads hits from the local cache; when {@code jump} is set, scrolls to the next one. */
    private void reload(boolean jump) {
        if (loading) {
            return;
        }
        loading = true;
        KeywordFolderController.getInstance(fragment.getCurrentAccount()).loadHits(fragment.getDialogId(), folder.keywords, result -> {
            loading = false;
            hits.clear();
            hits.addAll(result);
            if (jump) {
                if (hits.isEmpty()) {
                    BulletinFactory.of(fragment).createErrorBulletin(KeywordFolderStrings.jumpNone()).show();
                } else {
                    int last = indexOfLast();
                    int next = last + 1;
                    if (lastJumpedId != 0 && (last < 0 || next >= hits.size())) {
                        if (next >= hits.size()) {
                            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, KeywordFolderStrings.jumpNoMore()).show();
                        }
                        next = 0;
                    }
                    if (next >= hits.size()) {
                        next = 0;
                    }
                    lastJumpedId = hits.get(next).messageId;
                    fragment.scrollToMessageId(lastJumpedId, 0, true, 0, true, 0);
                }
            }
            updateLabel();
        });
    }
}
