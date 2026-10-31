package org.telegram.luckgram.writing;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramAIError;
import org.telegram.luckgram.LuckgramFeatures;
import org.telegram.luckgram.LuckgramSettings;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextCaption;
import org.telegram.ui.Components.ItemOptions;

import java.util.Collections;
import java.util.List;

/**
 * spec 21: the ✦ button of the message field — the menu, the five writing effects streamed back
 * into the field, undo, and the {@link AiWritingRegistry.Host} given to other features.
 */
public final class AiWritingController implements AiWritingRegistry.Host {

    /** spec 21 需求 4 */
    public static final int MAX_CHARS = 4000;

    public static boolean isEnabled() {
        return LuckgramSettings.isEnabled(LuckgramFeatures.AI_WRITING);
    }

    private final ChatActivity chatActivity;
    private final EditTextCaption field;
    private final int account;
    private final long dialogId;

    private CharSequence undoText;
    private LuckgramAI.Request request;
    private View shownPanel;
    private View undoHint;
    private Runnable hideUndoHint;

    public AiWritingController(ChatActivity chatActivity, EditTextCaption field, int account, long dialogId) {
        this.chatActivity = chatActivity;
        this.field = field;
        this.account = account;
        this.dialogId = dialogId;
    }

    /** Whether the ✦ button is shown for the current field content. */
    public boolean shouldShowButton() {
        if (!isEnabled() || chatActivity == null) return false;
        return field.length() > 0 || AiWritingRegistry.hasItemsForEmptyText(this);
    }

    public void showMenu(View anchor) {
        List<AiWritingRegistry.MenuItem> items = AiWritingRegistry.visibleItems(this, field.length() > 0);
        if (items.isEmpty() || chatActivity == null) return;
        ItemOptions options = ItemOptions.makeOptions(chatActivity, anchor);
        for (AiWritingRegistry.MenuItem item : items) {
            options.add(item.icon, item.title(), () -> {
                if (item.action != null) item.action.run(this);
            });
        }
        options.setGravity(Gravity.RIGHT).show();
    }

    public void destroy() {
        if (request != null) request.cancel();
        request = null;
        if (hideUndoHint != null) AndroidUtilities.cancelRunOnUIThread(hideUndoHint);
    }

    // ---------------------------------------------------------------- effects

    void runEffect(String effect, String doneZh, String doneEn) {
        final String original = field.getText().toString();
        if (original.trim().isEmpty()) return;
        if (original.length() > MAX_CHARS) {
            BulletinFactory.of(chatActivity).createErrorBulletin(LuckgramStrings.get(
                "文字太长（超过 " + MAX_CHARS + " 字），请缩短后再试", "The text is too long (over " + MAX_CHARS + " characters). Please shorten it.")).show();
            return;
        }
        if (request != null) request.cancel();
        final CharSequence before = new SpannableStringBuilder(field.getText());
        final int normalColor = field.getCurrentTextColor();
        // spec 21 需求 2: the original turns gray while the result is generated
        field.setTextColor(Theme.getColor(Theme.key_chat_messagePanelHint, chatActivity.getResourceProvider()));
        final boolean[] started = {false};
        request = LuckgramAI.getInstance().chat(chatActivity.getParentActivity(), LuckgramAI.TASK_WRITING, effect,
            Collections.singletonList(LuckgramAI.Message.user(original)), true, new LuckgramAI.ChatListener() {
                @Override
                public void onDelta(String delta, String text) {
                    if (!started[0]) {
                        started[0] = true;
                        field.setTextColor(normalColor);
                    }
                    replaceText(text);
                }

                @Override
                public void onComplete(String text) {
                    request = null;
                    field.setTextColor(normalColor);
                    String result = text == null ? "" : text.trim();
                    if (result.isEmpty()) {
                        replaceText(before);
                        return;
                    }
                    replaceText(result);
                    undoText = before;
                    showUndoHint(LuckgramStrings.get(doneZh, doneEn));
                }

                @Override
                public void onError(LuckgramAIError error) {
                    request = null;
                    field.setTextColor(normalColor);
                    replaceText(before);
                    if (!LuckgramAIError.CONSENT_DENIED.equals(error.code)) {
                        LuckgramAI.showError(chatActivity, error);
                    }
                }
            });
    }

    private void replaceText(CharSequence text) {
        Editable e = field.getText();
        e.replace(0, e.length(), text);
        field.setSelection(field.length());
    }

    private void showUndoHint(String done) {
        Context ctx = getContext();
        LinearLayout hint = new LinearLayout(ctx);
        hint.setOrientation(LinearLayout.HORIZONTAL);
        hint.setGravity(Gravity.CENTER_VERTICAL);
        hint.setPadding(dp(14), dp(8), dp(6), dp(8));
        TextView label = new TextView(ctx);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, chatActivity.getResourceProvider()));
        label.setText(done);
        hint.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView undo = new TextView(ctx);
        undo.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        undo.setTypeface(AndroidUtilities.bold());
        undo.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, chatActivity.getResourceProvider()));
        undo.setText(LuckgramStrings.get("撤销", "Undo"));
        undo.setPadding(dp(10), dp(4), dp(10), dp(4));
        undo.setOnClickListener(v -> {
            undo();
            hidePanel(hint);
        });
        hint.addView(undo);
        undoHint = hint;
        showPanel(hint);
        if (hideUndoHint != null) AndroidUtilities.cancelRunOnUIThread(hideUndoHint);
        AndroidUtilities.runOnUIThread(hideUndoHint = () -> hidePanel(hint), 8000);
    }

    // ---------------------------------------------------------------- Host

    @Override
    public int getCurrentAccount() {
        return account;
    }

    @Override
    public long getDialogId() {
        return dialogId;
    }

    @Override
    public ChatActivity getChatActivity() {
        return chatActivity;
    }

    @Override
    public Context getContext() {
        return field.getContext();
    }

    @Override
    public CharSequence getText() {
        return field.getText();
    }

    @Override
    public void setText(CharSequence text, boolean undoable) {
        if (undoable) {
            undoText = new SpannableStringBuilder(field.getText());
        }
        replaceText(text == null ? "" : text);
        field.requestFocus();
    }

    @Override
    public boolean canUndo() {
        return undoText != null;
    }

    @Override
    public void undo() {
        if (undoText == null) return;
        CharSequence previous = undoText;
        undoText = null;
        replaceText(previous);
    }

    @Override
    public void showPanel(View view) {
        FrameLayout container = chatActivity.getLuckgramInputPanelContainer();
        if (container == null) return;
        if (shownPanel != null && shownPanel != view) {
            container.removeView(shownPanel);
        }
        if (view.getParent() != container) {
            AndroidUtilities.removeFromParent(view);
            container.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        }
        shownPanel = view;
        container.setVisibility(View.VISIBLE);
    }

    @Override
    public void hidePanel(View view) {
        FrameLayout container = chatActivity.getLuckgramInputPanelContainer();
        if (container == null || view == null || view.getParent() != container) return;
        container.removeView(view);
        if (shownPanel == view) shownPanel = null;
        if (container.getChildCount() == 0) container.setVisibility(View.GONE);
    }

    @Override
    public boolean canPostInChannel() {
        TLRPC.Chat chat = chatActivity.getCurrentChat();
        return chat != null && ChatObject.isChannelAndNotMegaGroup(chat) && ChatObject.canPost(chat);
    }

    @Override
    public void send(CharSequence text, boolean schedule) {
        if (isEmpty(text) || chatActivity == null) return;
        final String message = text.toString().trim();
        if (schedule) {
            AlertsCreator.createScheduleDatePickerDialog(chatActivity.getParentActivity(), dialogId,
                (notify, scheduleDate, scheduleRepeatPeriod) -> sendNow(message, notify, scheduleDate, scheduleRepeatPeriod),
                chatActivity.getResourceProvider());
        } else {
            sendNow(message, true, 0, 0);
        }
    }

    private void sendNow(String message, boolean notify, int scheduleDate, int scheduleRepeatPeriod) {
        SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(message, dialogId, null, null, null, true,
            null, null, null, notify, scheduleDate, scheduleRepeatPeriod, null, false);
        params.sendMessageChatArguments = chatActivity.getMessageChatSendParams();
        SendMessagesHelper.getInstance(account).sendMessage(params);
        undoText = null;
        replaceText("");
    }

    static boolean isEmpty(CharSequence s) {
        return TextUtils.isEmpty(s) || s.toString().trim().isEmpty();
    }
}
