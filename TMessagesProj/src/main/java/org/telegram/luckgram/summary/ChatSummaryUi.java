package org.telegram.luckgram.summary;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.telegram.luckgram.LuckgramAIError;
import org.telegram.luckgram.LuckgramFeatures;
import org.telegram.luckgram.LuckgramSettings;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.AnimatedLinearLayout;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * spec 19 UI inside a chat: the thin "N 条未读消息 · 总结" bar, the range picker of the "…" menu, and
 * the collapsible summary card at the top of the chat with clickable references and a question box.
 */
public final class ChatSummaryUi {

    /** spec 19 需求 1: the bar shows from this many unread messages. */
    public static final int BAR_MIN_UNREAD = 30;
    /** Largest range offered (spec 19 需求 1); also caps "unread". */
    static final int MAX_RANGE = 500;

    public static boolean isEnabled() {
        return LuckgramSettings.isEnabled(LuckgramFeatures.AI_SUMMARY);
    }

    private final ChatActivity fragment;
    private final AnimatedLinearLayout panel;
    private final long dialogId;
    private final long threadId;
    private ChatSummaryController controller;

    private TextView bar;
    private int unread;

    private LinearLayout card;
    private TextView titleView;
    private ImageView collapseButton;
    private ScrollView bodyScroll;
    private TextView statusView;
    private TextView summaryView;
    private TextView qaView;
    private EditTextBoldCursor questionField;
    private ImageView askButton;
    private boolean collapsed;
    private boolean busy;
    private Runnable retry;
    private CharSequence summaryText = "";
    private final SpannableStringBuilder qaText = new SpannableStringBuilder();

    public ChatSummaryUi(ChatActivity fragment, AnimatedLinearLayout panel, long dialogId, long threadId) {
        this.fragment = fragment;
        this.panel = panel;
        this.dialogId = dialogId;
        this.threadId = threadId;
    }

    private Context context() {
        return fragment.getContext();
    }

    private int color(int key) {
        return Theme.getColor(key, fragment.getResourceProvider());
    }

    public void destroy() {
        if (controller != null) controller.cancel();
    }

    // ---------------------------------------------------------------- bar

    public void setUnread(int unreadCount) {
        unread = unreadCount;
        if (!isEnabled() || unread < BAR_MIN_UNREAD || (card != null && card.getVisibility() == View.VISIBLE)) {
            if (bar != null) panel.setViewVisible(bar, false, true);
            return;
        }
        if (bar == null) {
            bar = new TextView(context());
            bar.setGravity(Gravity.CENTER);
            bar.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            bar.setTypeface(AndroidUtilities.bold());
            bar.setTextColor(color(Theme.key_chat_topPanelTitle));
            bar.setBackground(Theme.getSelectorDrawable(false));
            bar.setOnClickListener(v -> start(Math.min(unread, MAX_RANGE), rangeLabelUnread()));
            panel.addView(bar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36));
        }
        bar.setText(LuckgramStrings.get(unread + " 条未读消息 · 总结", unread + " unread messages · Summarize"));
        panel.setViewVisible(bar, true, true);
    }

    private String rangeLabelUnread() {
        return LuckgramStrings.get("未读", "Unread");
    }

    // ---------------------------------------------------------------- menu

    /** "总结聊天" in the chat's "…" menu: pick the range. */
    public void showRangePicker() {
        ArrayList<CharSequence> items = new ArrayList<>();
        ArrayList<Integer> counts = new ArrayList<>();
        ArrayList<String> labels = new ArrayList<>();
        if (unread > 0) {
            items.add(LuckgramStrings.get("未读（" + Math.min(unread, MAX_RANGE) + " 条）", "Unread (" + Math.min(unread, MAX_RANGE) + ")"));
            counts.add(Math.min(unread, MAX_RANGE));
            labels.add(rangeLabelUnread());
        }
        for (int n : new int[]{100, 300, 500}) {
            items.add(LuckgramStrings.get("最近 " + n + " 条", "Last " + n));
            counts.add(n);
            labels.add(LuckgramStrings.get("最近 " + n + " 条", "Last " + n));
        }
        AlertDialog.Builder b = new AlertDialog.Builder(context(), fragment.getResourceProvider());
        b.setTitle(LuckgramStrings.get("总结聊天", "Summarize chat"));
        b.setItems(items.toArray(new CharSequence[0]), (d, which) -> start(counts.get(which), labels.get(which)));
        fragment.showDialog(b.create());
    }

    // ---------------------------------------------------------------- card

    private void ensureCard() {
        if (card != null) return;
        Context ctx = context();
        card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(color(Theme.key_chat_topPanelBackground));

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        titleView = new TextView(ctx);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(color(Theme.key_chat_topPanelTitle));
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(titleView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 14, 0, 0, 0));
        collapseButton = iconButton(ctx, R.drawable.arrow_more, v -> setCollapsed(!collapsed));
        header.addView(collapseButton, LayoutHelper.createLinear(40, 40));
        ImageView close = iconButton(ctx, R.drawable.miniplayer_close, v -> close());
        close.setContentDescription(LuckgramStrings.get("关闭", "Close"));
        header.addView(close, LayoutHelper.createLinear(40, 40, 0, 0, 4, 0));
        header.setOnClickListener(v -> setCollapsed(!collapsed));
        card.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40));

        bodyScroll = new ScrollView(ctx) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int max = (int) (AndroidUtilities.displaySize.y * 0.4f);
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
            }
        };
        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), 0, dp(14), dp(6));
        bodyScroll.addView(body);

        statusView = text(ctx, 14, Theme.key_chat_topPanelMessage);
        statusView.setOnClickListener(v -> {
            if (retry != null) retry.run();
        });
        body.addView(statusView);
        summaryView = text(ctx, 15, Theme.key_chat_topPanelTitle);
        summaryView.setMovementMethod(LinkMovementMethod.getInstance());
        summaryView.setLineSpacing(dp(2), 1f);
        body.addView(summaryView);
        TextView disclaimer = text(ctx, 12, Theme.key_chat_topPanelMessage);
        disclaimer.setText(LuckgramStrings.get("AI 生成，可能有误", "AI-generated, may be inaccurate"));
        body.addView(disclaimer, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
        qaView = text(ctx, 15, Theme.key_chat_topPanelTitle);
        qaView.setMovementMethod(LinkMovementMethod.getInstance());
        qaView.setLineSpacing(dp(2), 1f);
        body.addView(qaView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
        card.addView(bodyScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        FrameLayout askRow = new FrameLayout(ctx);
        questionField = new EditTextBoldCursor(ctx);
        questionField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        questionField.setTextColor(color(Theme.key_chat_topPanelTitle));
        questionField.setHintTextColor(color(Theme.key_chat_messagePanelHint));
        questionField.setCursorColor(color(Theme.key_chat_topPanelTitle));
        questionField.setHint(LuckgramStrings.get("问这些消息…", "Ask about these messages…"));
        questionField.setSingleLine(true);
        questionField.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        questionField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                ask();
                return true;
            }
            return false;
        });
        questionField.setBackground(Theme.createRoundRectDrawable(dp(16), color(Theme.key_chat_messagePanelBackground)));
        questionField.setPadding(dp(12), dp(6), dp(12), dp(6));
        askRow.addView(questionField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 34, Gravity.CENTER_VERTICAL, 14, 0, 48, 0));
        askButton = iconButton(ctx, R.drawable.ic_send, v -> ask());
        askButton.setColorFilter(new PorterDuffColorFilter(color(Theme.key_chat_messagePanelSend), PorterDuff.Mode.SRC_IN));
        askButton.setContentDescription(LuckgramStrings.get("发送", "Send"));
        askRow.addView(askButton, LayoutHelper.createFrame(40, 40, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 6, 0));
        card.addView(askRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 46));

        View divider = new View(ctx);
        divider.setBackgroundColor(color(Theme.key_chat_topPanelLine));
        card.addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1));

        panel.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    private TextView text(Context ctx, int sizeDp, int colorKey) {
        TextView t = new TextView(ctx);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp);
        t.setTextColor(color(colorKey));
        t.setLinkTextColor(color(Theme.key_windowBackgroundWhiteBlueText));
        return t;
    }

    private ImageView iconButton(Context ctx, int icon, View.OnClickListener click) {
        ImageView b = new ImageView(ctx);
        b.setImageResource(icon);
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setColorFilter(new PorterDuffColorFilter(color(Theme.key_chat_topPanelClose), PorterDuff.Mode.SRC_IN));
        b.setBackground(Theme.createSelectorDrawable(color(Theme.key_listSelector)));
        b.setOnClickListener(click);
        return b;
    }

    private void setCollapsed(boolean value) {
        collapsed = value;
        bodyScroll.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        ((View) questionField.getParent()).setVisibility(collapsed ? View.GONE : View.VISIBLE);
        collapseButton.setRotation(collapsed ? 0 : 180);
        collapseButton.setContentDescription(collapsed ? LuckgramStrings.get("展开", "Expand") : LuckgramStrings.get("收起", "Collapse"));
    }

    private void close() {
        if (controller != null) controller.cancel();
        controller = null;
        busy = false;
        AndroidUtilities.hideKeyboard(questionField);
        panel.setViewVisible(card, false, true);
        card.setVisibility(View.GONE);
    }

    private void setStatus(CharSequence text, Runnable retryAction) {
        retry = retryAction;
        statusView.setText(text);
        statusView.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
    }

    private void start(int count, String label) {
        if (bar != null) panel.setViewVisible(bar, false, true);
        ensureCard();
        card.setVisibility(View.VISIBLE);
        panel.setViewVisible(card, true, true);
        setCollapsed(false);
        titleView.setText(LuckgramStrings.get("AI 总结 · ", "AI summary · ") + label);
        summaryText = "";
        summaryView.setText("");
        qaText.clear();
        qaView.setText("");
        if (controller != null) controller.cancel();
        controller = new ChatSummaryController(fragment.getCurrentAccount(), dialogId, threadId);
        final ChatSummaryController c = controller;
        busy = true;
        setStatus(LuckgramStrings.get("正在读取消息…", "Reading messages…"), null);
        c.load(count, new ChatSummaryController.Callback<Integer>() {
            @Override
            public void onResult(Integer found) {
                if (c != controller) return;
                if (found == 0) {
                    busy = false;
                    setStatus(LuckgramStrings.get("没有可总结的文字消息", "No text messages to summarize"), null);
                    return;
                }
                setStatus(LuckgramStrings.get("正在总结 " + found + " 条消息…", "Summarizing " + found + " messages…"), null);
                c.summarize(fragment.getParentActivity(), new ChatSummaryController.Callback<String>() {
                    @Override
                    public void onResult(String text) {
                        if (c != controller) return;
                        busy = false;
                        setStatus(null, null);
                        summaryText = render(ChatSummaryLogic.bullets(text), c);
                        summaryView.setText(summaryText);
                    }

                    @Override
                    public void onError(LuckgramAIError error) {
                        if (c != controller) return;
                        failed(error, () -> start(count, label));
                    }
                });
            }

            @Override
            public void onError(LuckgramAIError error) {
                if (c != controller) return;
                failed(error, () -> start(count, label));
            }
        });
    }

    private void failed(LuckgramAIError error, Runnable retryAction) {
        busy = false;
        if (LuckgramAIError.CONSENT_DENIED.equals(error.code)) {
            close();
            return;
        }
        setStatus(error.getLocalizedMessage() + LuckgramStrings.get(" 点此重试", " Tap to retry"), retryAction);
    }

    private void ask() {
        String q = questionField.getText().toString().trim();
        final ChatSummaryController c = controller;
        if (q.isEmpty() || c == null || busy) return;
        questionField.setText("");
        busy = true;
        if (qaText.length() > 0) qaText.append("\n\n");
        int start = qaText.length();
        qaText.append(LuckgramStrings.get("问：", "Q: ")).append(q);
        qaText.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, qaText.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        qaText.append('\n');
        final int answerStart = qaText.length();
        qaText.append("…");
        qaView.setText(qaText);
        scrollToBottom();
        c.ask(fragment.getParentActivity(), q, new ChatSummaryController.Callback<String>() {
            @Override
            public void onResult(String text) {
                if (c != controller) return;
                busy = false;
                qaText.replace(answerStart, qaText.length(), render(ChatSummaryLogic.bullets(text), c));
                qaView.setText(qaText);
                scrollToBottom();
            }

            @Override
            public void onError(LuckgramAIError error) {
                if (c != controller) return;
                busy = false;
                qaText.replace(answerStart, qaText.length(), error.getLocalizedMessage());
                qaView.setText(qaText);
            }
        });
    }

    private void scrollToBottom() {
        bodyScroll.post(() -> bodyScroll.fullScroll(View.FOCUS_DOWN));
    }

    /** Points with numbered references; tapping one jumps to the message (only IDs from the range). */
    private CharSequence render(List<String> points, ChatSummaryController c) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int ref = 0;
        for (int i = 0; i < points.size(); i++) {
            if (sb.length() > 0) sb.append('\n');
            if (points.size() > 1) sb.append("• ");
            for (ChatSummaryLogic.Segment s : ChatSummaryLogic.parse(points.get(i), c.messageIds())) {
                if (s.messageId == 0) {
                    sb.append(s.text);
                    continue;
                }
                ref++;
                final int messageId = s.messageId;
                sb.append(' ');
                int st = sb.length();
                sb.append('[').append(String.valueOf(ref)).append(']');
                sb.setSpan(new ClickableSpan() {
                    @Override
                    public void onClick(@NonNull View widget) {
                        fragment.scrollToMessageId(messageId, 0, true, 0, true, 0);
                    }

                    @Override
                    public void updateDrawState(@NonNull TextPaint ds) {
                        ds.setColor(color(Theme.key_windowBackgroundWhiteBlueText));
                        ds.setUnderlineText(false);
                        ds.setFakeBoldText(true);
                    }
                }, st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return sb;
    }
}
