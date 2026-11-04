package org.telegram.luckgram.ai;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramAIError;
import org.telegram.luckgram.LuckgramSettings;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ChatAvatarContainer;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SizeNotifierFrameLayout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * spec 18: the Luckgram AI assistant, a local chat page that looks like a Telegram chat. It is not
 * a Telegram bot or chat: nothing is sent to Telegram, requests go only to the AI gateway through
 * {@link LuckgramAI}. Extension points: {@link LuckgramAiRegistry}.
 */
public class LuckgramAiChatActivity extends BaseFragment implements LuckgramAiRegistry.ChatHost {

    private static final String ARG_CONVERSATION = "conversation";
    private static final String ARG_PREFILL = "prefill";
    private static final int MENU_NEW = 1, MENU_HISTORY = 2;

    private LuckgramAiConversation conversation;
    private String prefill;

    private RecyclerListView listView;
    private LinearLayoutManager layoutManager;
    private Adapter adapter;
    /** specs#101: welcome card with example questions while the conversation is empty. */
    private View emptyView;
    private EditTextBoldCursor input;
    private ImageView sendButton;
    private ChatAvatarContainer avatarContainer;

    private LuckgramAiConversation.Message streaming;
    private LuckgramAI.Request request;

    /** A new conversation, with {@code prefill} in the input field (may be null). */
    public static LuckgramAiChatActivity newConversation(String prefill) {
        Bundle args = new Bundle();
        if (prefill != null) args.putString(ARG_PREFILL, prefill);
        return new LuckgramAiChatActivity(args);
    }

    /** From the chat list: reopens the most recent conversation, or a new one. */
    public static void openLatest(BaseFragment from) {
        List<LuckgramAiConversation> list = LuckgramAiStore.getInstance(from.getCurrentAccount()).list();
        Bundle args = new Bundle();
        if (!list.isEmpty() && list.get(0).key == null) {
            args.putString(ARG_CONVERSATION, list.get(0).id);
        }
        from.presentFragment(new LuckgramAiChatActivity(args));
    }

    public static boolean isEnabled() {
        return LuckgramSettings.isEnabled(org.telegram.luckgram.LuckgramFeatures.AI_ASSISTANT);
    }

    public static boolean isRowEnabled() {
        return isEnabled() && LuckgramSettings.isEnabled(org.telegram.luckgram.LuckgramFeatures.AI_ASSISTANT_ROW);
    }

    /** Extension point: opens a stored conversation, e.g. one from {@link LuckgramAiStore#findOrCreate}. */
    public static void open(BaseFragment from, String conversationId) {
        Bundle args = new Bundle();
        args.putString(ARG_CONVERSATION, conversationId);
        from.presentFragment(new LuckgramAiChatActivity(args));
    }

    public LuckgramAiChatActivity(Bundle args) {
        super(args);
    }

    private LuckgramAiStore store() {
        return LuckgramAiStore.getInstance(currentAccount);
    }

    @Override
    public boolean onFragmentCreate() {
        Bundle args = getArguments();
        String id = args == null ? null : args.getString(ARG_CONVERSATION);
        prefill = args == null ? null : args.getString(ARG_PREFILL);
        conversation = id != null ? store().get(id) : null;
        if (conversation == null) {
            conversation = store().create(LuckgramAI.TASK_CHAT, null, null);
        }
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        stopStreaming();
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(false);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_NEW) {
                    switchTo(store().create(LuckgramAI.TASK_CHAT, null, null));
                } else if (id == MENU_HISTORY) {
                    presentFragment(new LuckgramAiHistoryActivity(LuckgramAiChatActivity.this::switchTo));
                }
            }
        });
        avatarContainer = new ChatAvatarContainer(context, this, false, getResourceProvider());
        AvatarDrawable avatar = new AvatarDrawable();
        avatar.setInfo(0x4c47414cL, "Luckgram", "AI", "AI");
        avatarContainer.getAvatarImageView().setImageDrawable(avatar);
        updateHeader();
        avatarContainer.setOnClickListener(null);
        actionBar.addView(avatarContainer, 0, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT, 56, 0, 96, 0));
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_NEW, R.drawable.msg_add).setContentDescription(LuckgramStrings.get("新对话", "New chat"));
        menu.addItem(MENU_HISTORY, R.drawable.msg_recent).setContentDescription(LuckgramStrings.get("历史对话", "Chat history"));

        // specs#96: this view starts below the action bar (no overlay), so the wallpaper must not
        // skip an action bar + status bar height at its top (that left a white block).
        SizeNotifierFrameLayout content = new SizeNotifierFrameLayout(context) {
            @Override
            protected boolean isActionBarVisible() {
                return false;
            }

            @Override
            protected boolean isStatusBarVisible() {
                return false;
            }
        };
        content.setBackgroundImage(Theme.getCachedWallpaper(), Theme.isWallpaperMotion());
        fragmentView = content;

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        content.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // extension point 3: entries at the top of the page, as Telegram pinned bars (specs#101)
        for (LuckgramAiRegistry.HeaderEntry entry : LuckgramAiRegistry.headerEntries()) {
            column.addView(pinnedBar(context, entry), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
        }

        FrameLayout listFrame = new FrameLayout(context);
        column.addView(listFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        listView = new RecyclerListView(context);
        layoutManager = new LinearLayoutManager(context);
        layoutManager.setStackFromEnd(true);
        listView.setLayoutManager(layoutManager);
        listView.setClipToPadding(false);
        listView.setPadding(0, dp(6), 0, dp(6));
        listView.setItemAnimator(null);
        adapter = new Adapter();
        listView.setAdapter(adapter);
        listFrame.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        android.widget.ScrollView emptyScroll = new android.widget.ScrollView(context);
        emptyScroll.setFillViewport(true);
        FrameLayout emptyFrame = new FrameLayout(context);
        emptyFrame.addView(welcomeCard(context), LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 24, 12, 24, 12));
        emptyScroll.addView(emptyFrame);
        emptyView = emptyScroll;
        listFrame.addView(emptyView, LayoutHelper.createFrame(Math.min(388, (int) (AndroidUtilities.displaySize.x / AndroidUtilities.density)), LayoutHelper.MATCH_PARENT, Gravity.CENTER_HORIZONTAL));

        // specs#96: the same top shadow as Telegram's message panel (ChatActivityEnterView)
        View composeShadow = new View(context);
        android.graphics.drawable.Drawable shadow = context.getResources().getDrawable(R.drawable.compose_panel_shadow).mutate();
        shadow.setColorFilter(new android.graphics.PorterDuffColorFilter(getThemedColor(Theme.key_chat_messagePanelShadow), android.graphics.PorterDuff.Mode.SRC_IN));
        composeShadow.setBackground(shadow);
        column.addView(composeShadow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 3));

        // spec 18 需求 3 + extension point 1: quick buttons, a scrollable row at the top of the
        // message panel (specs#101), on the panel's own background
        HorizontalScrollView quickScroll = new HorizontalScrollView(context);
        quickScroll.setHorizontalScrollBarEnabled(false);
        quickScroll.setBackgroundColor(getThemedColor(Theme.key_chat_messagePanelBackground));
        LinearLayout quickRow = new LinearLayout(context);
        quickRow.setOrientation(LinearLayout.HORIZONTAL);
        quickRow.setPadding(dp(7), dp(8), dp(7), dp(2));
        quickScroll.addView(quickRow);
        for (LuckgramAiRegistry.QuickAction action : LuckgramAiRegistry.quickActions()) {
            quickRow.addView(chip(context, action.title(), action.icon, v -> action.callback.run(this)));
        }
        quickScroll.setVisibility(quickRow.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        column.addView(quickScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        FrameLayout inputPanel = new FrameLayout(context);
        inputPanel.setBackgroundColor(getThemedColor(Theme.key_chat_messagePanelBackground));
        column.addView(inputPanel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        input = new EditTextBoldCursor(context);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        input.setTextColor(getThemedColor(Theme.key_chat_messagePanelText));
        input.setHintTextColor(getThemedColor(Theme.key_chat_messagePanelHint));
        input.setCursorColor(getThemedColor(Theme.key_chat_messagePanelCursor));
        input.setHint(LuckgramStrings.get("给 Luckgram AI 发消息", "Message Luckgram AI"));
        input.setBackground(null);
        input.setMaxLines(6);
        input.setMinHeight(dp(48));
        input.setPadding(dp(14), dp(11), dp(4), dp(11));
        input.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setInputType(input.getInputType() | EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES | EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE);
        inputPanel.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM, 0, 0, 52, 0));

        sendButton = new ImageView(context);
        sendButton.setScaleType(ImageView.ScaleType.CENTER);
        sendButton.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector)));
        sendButton.setOnClickListener(v -> {
            if (streaming != null) {
                stopStreaming();
            } else {
                send();
            }
        });
        inputPanel.addView(sendButton, LayoutHelper.createFrame(48, 48, Gravity.BOTTOM | Gravity.RIGHT, 0, 0, 2, 0));
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateSendButton();
            }
        });

        if (!TextUtils.isEmpty(prefill)) {
            setInput(prefill);
            prefill = null;
            AndroidUtilities.runOnUIThread(() -> {
                if (input != null) {
                    input.requestFocus();
                    AndroidUtilities.showKeyboard(input);
                }
            }, 300);
        }
        updateSendButton();
        updateEmpty();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        AndroidUtilities.requestAdjustResize(getParentActivity(), classGuid);
    }

    /** specs#101: quick buttons — accent text on a light accent tint with an accent outline. */
    private View chip(Context context, String title, int icon, View.OnClickListener click) {
        TextView chip = new TextView(context);
        chip.setText(title);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        chip.setTypeface(AndroidUtilities.bold());
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
        int accent = getThemedColor(Theme.key_chat_messagePanelSend);
        chip.setTextColor(accent);
        chip.setPadding(dp(12), 0, dp(12), 0);
        if (icon != 0) {
            android.graphics.drawable.Drawable d = context.getResources().getDrawable(icon).mutate();
            d.setColorFilter(new android.graphics.PorterDuffColorFilter(accent, android.graphics.PorterDuff.Mode.SRC_IN));
            d.setBounds(0, 0, dp(18), dp(18));
            chip.setCompoundDrawables(d, null, null, null);
            chip.setCompoundDrawablePadding(dp(4));
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(15));
        bg.setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 0x1A));
        bg.setStroke(Math.max(1, dp(1)), androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 0x80));
        chip.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 0x30)), bg, null));
        chip.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30));
        lp.setMargins(dp(3), 0, dp(3), 0);
        chip.setLayoutParams(lp);
        return chip;
    }

    /** specs#101: a header entry as Telegram's pinned message bar — accent line, icon, title, hint ›. */
    private View pinnedBar(Context context, LuckgramAiRegistry.HeaderEntry entry) {
        int accent = getThemedColor(Theme.key_chat_topPanelLine);
        FrameLayout bar = new FrameLayout(context) {
            @Override
            protected void dispatchDraw(android.graphics.Canvas canvas) {
                super.dispatchDraw(canvas);
                canvas.drawLine(0, getMeasuredHeight() - 1, getMeasuredWidth(), getMeasuredHeight() - 1, Theme.dividerPaint);
            }
        };
        bar.setWillNotDraw(false);
        bar.setBackground(Theme.createSelectorWithBackgroundDrawable(getThemedColor(Theme.key_chat_topPanelBackground), getThemedColor(Theme.key_listSelector)));
        bar.setContentDescription(entry.title());
        bar.setOnClickListener(v -> entry.callback.run(this));
        View line = new View(context);
        GradientDrawable lineBg = new GradientDrawable();
        lineBg.setCornerRadius(dp(1));
        lineBg.setColor(accent);
        line.setBackground(lineBg);
        bar.addView(line, LayoutHelper.createFrame(2, 32, Gravity.LEFT | Gravity.CENTER_VERTICAL, 10, 0, 0, 0));
        if (entry.icon != 0) {
            ImageView icon = new ImageView(context);
            icon.setScaleType(ImageView.ScaleType.CENTER);
            icon.setImageResource(entry.icon);
            icon.setColorFilter(new android.graphics.PorterDuffColorFilter(accent, android.graphics.PorterDuff.Mode.SRC_IN));
            bar.addView(icon, LayoutHelper.createFrame(28, 28, Gravity.LEFT | Gravity.CENTER_VERTICAL, 20, 0, 0, 0));
        }
        TextView title = new TextView(context);
        title.setText(entry.title());
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(getThemedColor(Theme.key_chat_topPanelTitle));
        title.setSingleLine(true);
        bar.addView(title, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, entry.icon != 0 ? 56 : 22, 0, 120, 0));
        TextView hint = new TextView(context);
        String sub = entry.subtitle();
        hint.setText((sub == null ? "" : sub + " ") + "›");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        hint.setTextColor(accent);
        hint.setSingleLine(true);
        bar.addView(hint, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 14, 0));
        return bar;
    }

    /** specs#101: example questions on the welcome card (same on iOS); a tap fills the input field. */
    static final String[][] WELCOME_EXAMPLES = {
        {"把这段话改得更正式一点", "Make this paragraph more formal"},
        {"用三句话解释什么是区块链", "Explain blockchain in three sentences"},
        {"周末两天怎么安排一次短途旅行？", "Plan a two-day weekend trip for me"},
        {"帮我写一条给同事的生日祝福", "Write a birthday message for a coworker"},
    };

    /** specs#101: centered card — accent icon, title, one line, 4 tappable examples; readable in both themes. */
    private View welcomeCard(Context context) {
        int accent = getThemedColor(Theme.key_chat_messagePanelSend);
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(16), dp(20), dp(16), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        bg.setColor(getThemedColor(Theme.key_chat_inBubble));
        card.setBackground(bg);
        card.setElevation(dp(2));

        ImageView badge = new ImageView(context);
        badge.setScaleType(ImageView.ScaleType.CENTER);
        badge.setImageResource(R.drawable.msg_bots);
        badge.setColorFilter(new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setShape(GradientDrawable.OVAL);
        badgeBg.setColor(accent);
        badge.setBackground(badgeBg);
        card.addView(badge, LayoutHelper.createLinear(48, 48));

        TextView title = new TextView(context);
        title.setText(LuckgramStrings.get("有什么可以帮你？", "How can I help?"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(getThemedColor(Theme.key_chat_messageTextIn));
        title.setGravity(Gravity.CENTER);
        card.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));

        TextView sub = new TextView(context);
        sub.setText(LuckgramStrings.get("可以提问、总结或改写长文、讲解、解题。", "Ask anything, summarize or rewrite text, explain, solve problems."));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        sub.setTextColor(getThemedColor(Theme.key_chat_inTimeText));
        sub.setGravity(Gravity.CENTER);
        card.addView(sub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 14));

        for (String[] example : WELCOME_EXAMPLES) {
            String text = LuckgramStrings.get(example[0], example[1]);
            TextView row = new TextView(context);
            row.setText(text);
            row.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            row.setTextColor(accent);
            row.setPadding(dp(12), dp(9), dp(12), dp(9));
            GradientDrawable rowBg = new GradientDrawable();
            rowBg.setCornerRadius(dp(12));
            rowBg.setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 0x1A));
            row.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 0x30)), rowBg, null));
            row.setOnClickListener(v -> {
                setInput(text);
                if (input != null) {
                    input.requestFocus();
                    AndroidUtilities.showKeyboard(input);
                }
            });
            card.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
        }
        return card;
    }

    private void updateSendButton() {
        if (sendButton == null) return;
        boolean stop = streaming != null;
        sendButton.setImageResource(stop ? R.drawable.msg_cancel : R.drawable.ic_send);
        boolean enabled = stop || input.length() > 0;
        int color = getThemedColor(enabled ? Theme.key_chat_messagePanelSend : Theme.key_chat_messagePanelIcons);
        sendButton.setColorFilter(new android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN));
        sendButton.setContentDescription(stop ? LuckgramStrings.get("停止", "Stop") : LuckgramStrings.get("发送", "Send"));
    }

    private void updateEmpty() {
        if (emptyView != null) {
            emptyView.setVisibility(conversation.messages.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * "Luckgram AI / 助手"; a conversation opened by a feature with its own key (e.g. an agent,
     * spec 22) shows its title instead, with "Luckgram AI" below.
     */
    private void updateHeader() {
        if (avatarContainer == null) return;
        if (conversation.key != null && !TextUtils.isEmpty(conversation.title)) {
            avatarContainer.setTitle(conversation.title);
            avatarContainer.setSubtitle("Luckgram AI");
        } else {
            avatarContainer.setTitle("Luckgram AI");
            avatarContainer.setSubtitle(LuckgramStrings.get("助手", "Assistant"));
        }
    }

    private void switchTo(LuckgramAiConversation c) {
        if (c == null || c == conversation) return;
        stopStreaming();
        conversation = c;
        updateHeader();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
            scrollToBottom();
        }
        updateEmpty();
    }

    private void scrollToBottom() {
        if (listView != null && adapter.getItemCount() > 0) {
            layoutManager.scrollToPositionWithOffset(adapter.getItemCount() - 1, -100000);
        }
    }

    private boolean isAtBottom() {
        return listView == null || !listView.canScrollVertically(1);
    }

    // ---------------------------------------------------------------- sending

    private void send() {
        String text = input.getText().toString().trim();
        if (text.isEmpty() || streaming != null) {
            return;
        }
        input.setText("");
        if (LuckgramAiRegistry.intercept(this, text)) {
            return;
        }
        addMessage(LuckgramAiConversation.Message.create(LuckgramAiConversation.Message.ROLE_USER, LuckgramAiConversation.Message.TYPE_TEXT, text));
        requestReply();
    }

    private void requestReply() {
        final LuckgramAiConversation conv = conversation;
        final LuckgramAiConversation.Message reply = LuckgramAiConversation.Message.create(LuckgramAiConversation.Message.ROLE_ASSISTANT, LuckgramAiConversation.Message.TYPE_TEXT, "");
        reply.pending = true;
        int maxChars = (int) Math.min(Integer.MAX_VALUE, LuckgramSettings.remoteLong("ai.max_input_chars", 60000));
        List<LuckgramAI.Message> messages = conv.requestMessages(maxChars);
        conv.messages.add(reply);
        notifyInserted();
        streaming = reply;
        updateSendButton();
        final String tier = conv.modelTier; // spec 29: premium model, sent as model_tier
        org.json.JSONObject params = conv.requestParams;
        if (tier != null) {
            try {
                params = new org.json.JSONObject(params != null ? params.toString() : "{}").put("model_tier", tier);
            } catch (Exception ignore) {
            }
        }
        request = LuckgramAI.getInstance().chat(getParentActivity(), conv.task, null, params, messages, true, new LuckgramAI.ChatListener() {
            @Override
            public void onDelta(String delta, String text) {
                if (streaming != reply) return;
                boolean bottom = isAtBottom();
                reply.text = text;
                notifyChanged(reply);
                if (bottom) scrollToBottom();
            }

            @Override
            public void onComplete(String text) {
                if (streaming != reply) return;
                reply.text = text;
                finishReply(conv, reply, null);
                if (tier != null) org.telegram.luckgram.pro.ProModelChoice.onPremiumReply();
            }

            @Override
            public void onError(LuckgramAIError error) {
                if (streaming != reply) return;
                finishReply(conv, reply, error);
                org.telegram.luckgram.pro.ProModelChoice.onError(LuckgramAiChatActivity.this, conv, error); // spec 29
            }
        });
    }

    private void finishReply(LuckgramAiConversation conv, LuckgramAiConversation.Message reply, LuckgramAIError error) {
        streaming = null;
        request = null;
        reply.pending = false;
        if (error != null && !LuckgramAIError.CANCELLED.equals(error.code)) {
            reply.errorCode = error.code;
        } else if (TextUtils.isEmpty(reply.text)) {
            conv.messages.remove(reply);
            if (conv == conversation && adapter != null) adapter.notifyDataSetChanged();
        }
        if (conv == conversation) {
            notifyChanged(reply);
            updateSendButton();
            if (isAtBottom()) scrollToBottom();
        }
        store().save(conv);
    }

    /** Stops the reply being streamed, keeping what has arrived. */
    private void stopStreaming() {
        LuckgramAiConversation.Message reply = streaming;
        if (reply == null) return;
        if (request != null) request.cancel();
        finishReply(conversation, reply, null);
    }

    private void retry(LuckgramAiConversation.Message failed) {
        if (streaming != null) return;
        conversation.messages.remove(failed);
        adapter.notifyDataSetChanged();
        requestReply();
    }

    // ---------------------------------------------------------------- ChatHost

    @Override
    public Context getContext() {
        return getParentActivity();
    }

    @Override
    public BaseFragment getFragment() {
        return this;
    }

    @Override
    public LuckgramAiConversation getConversation() {
        return conversation;
    }

    @Override
    public String getInput() {
        return input == null ? "" : input.getText().toString();
    }

    @Override
    public void setInput(CharSequence text) {
        if (input == null) return;
        input.setText(text);
        input.setSelection(input.length());
        input.requestFocus();
    }

    @Override
    public void addMessage(LuckgramAiConversation.Message message) {
        if (conversation.title == null && message.isUser() && message.text != null) {
            String t = message.text.replace('\n', ' ').trim();
            conversation.title = t.length() > 40 ? t.substring(0, 40) + "…" : t;
        }
        conversation.messages.add(message);
        notifyInserted();
        store().save(conversation);
    }

    @Override
    public void updateMessage(LuckgramAiConversation.Message message) {
        notifyChanged(message);
        store().save(conversation);
    }

    private void notifyInserted() {
        if (adapter != null) {
            adapter.notifyItemInserted(conversation.messages.size() - 1);
            scrollToBottom();
        }
        updateEmpty();
    }

    private void notifyChanged(LuckgramAiConversation.Message m) {
        if (adapter == null) return;
        int i = conversation.messages.indexOf(m);
        if (i >= 0) adapter.notifyItemChanged(i);
    }

    // ---------------------------------------------------------------- list

    private static final int TYPE_TEXT = 0, TYPE_UNKNOWN = 1, TYPE_REGISTERED = 100;

    private class Adapter extends RecyclerListView.SelectionAdapter {
        private final HashMap<String, Integer> typeIds = new HashMap<>();
        private final ArrayList<String> typeNames = new ArrayList<>();

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return false;
        }

        @Override
        public int getItemCount() {
            return conversation.messages.size();
        }

        @Override
        public int getItemViewType(int position) {
            LuckgramAiConversation.Message m = conversation.messages.get(position);
            if (m.type == null || LuckgramAiConversation.Message.TYPE_TEXT.equals(m.type)) {
                return TYPE_TEXT;
            }
            if (LuckgramAiRegistry.renderer(m.type) == null) {
                return TYPE_UNKNOWN;
            }
            Integer id = typeIds.get(m.type);
            if (id == null) {
                id = TYPE_REGISTERED + typeNames.size();
                typeIds.put(m.type, id);
                typeNames.add(m.type);
            }
            return id;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View content;
            LuckgramAiRegistry.MessageRenderer renderer = null;
            if (viewType >= TYPE_REGISTERED) {
                renderer = LuckgramAiRegistry.renderer(typeNames.get(viewType - TYPE_REGISTERED));
                content = renderer.createView(parent.getContext(), getResourceProvider());
            } else {
                TextView tv = new TextView(parent.getContext());
                tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, org.telegram.messenger.SharedConfig.fontSize);
                tv.setLineSpacing(dp(2), 1f);
                tv.setTextIsSelectable(false);
                tv.setMovementMethod(LinkMovementMethod.getInstance());
                content = tv;
            }
            return new RecyclerListView.Holder(new BubbleCell(parent.getContext(), content, renderer));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ((BubbleCell) holder.itemView).bind(conversation.messages.get(position), holder.getItemViewType());
        }
    }

    private class BubbleCell extends FrameLayout {
        private final LinearLayout bubble;
        private final View content;
        private final LuckgramAiRegistry.MessageRenderer renderer;
        private final TextView errorView;
        private final GradientDrawable background = new GradientDrawable();
        private LuckgramAiConversation.Message message;

        BubbleCell(Context context, View content, LuckgramAiRegistry.MessageRenderer renderer) {
            super(context);
            this.content = content;
            this.renderer = renderer;
            bubble = new LinearLayout(context);
            bubble.setOrientation(LinearLayout.VERTICAL);
            bubble.setPadding(dp(11), dp(7), dp(11), dp(8));
            background.setCornerRadius(dp(16));
            bubble.setBackground(background);
            bubble.addView(content, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            errorView = new TextView(context);
            errorView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            errorView.setTextColor(getThemedColor(Theme.key_text_RedRegular));
            errorView.setVisibility(GONE);
            bubble.addView(errorView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
            addView(bubble, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            bubble.setOnLongClickListener(v -> {
                showMenu();
                return true;
            });
            if (content instanceof TextView) {
                content.setOnLongClickListener(v -> {
                    showMenu();
                    return true;
                });
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            ((LayoutParams) bubble.getLayoutParams()).width = LayoutHelper.WRAP_CONTENT;
            bubble.setMinimumWidth(dp(48));
            int max = Math.min(width - dp(56), dp(560));
            bubble.measure(MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            setMeasuredDimension(width, bubble.getMeasuredHeight() + dp(6));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int w = bubble.getMeasuredWidth();
            boolean out = message != null && message.isUser();
            int x = out ? (r - l) - w - dp(8) : dp(8);
            bubble.layout(x, dp(3), x + w, dp(3) + bubble.getMeasuredHeight());
        }

        void bind(LuckgramAiConversation.Message m, int viewType) {
            message = m;
            boolean out = m.isUser();
            background.setColor(getThemedColor(out ? Theme.key_chat_outBubble : Theme.key_chat_inBubble));
            int textColor = getThemedColor(out ? Theme.key_chat_messageTextOut : Theme.key_chat_messageTextIn);
            if (renderer != null) {
                renderer.bind(content, m, out);
            } else if (content instanceof TextView) {
                TextView tv = (TextView) content;
                tv.setTextColor(textColor);
                tv.setLinkTextColor(getThemedColor(out ? Theme.key_chat_messageLinkOut : Theme.key_chat_messageLinkIn));
                if (viewType == TYPE_UNKNOWN) {
                    tv.setText(LuckgramStrings.get("此消息类型暂不支持显示", "This message can't be shown"));
                    tv.setTextColor(ColorUtils.setAlphaComponent(textColor, 0x99));
                } else if (out) {
                    tv.setText(m.text);
                } else if (m.pending && TextUtils.isEmpty(m.text)) {
                    tv.setText("…");
                } else {
                    tv.setText(render(m.text, textColor));
                }
            }
            if (m.errorCode != null) {
                errorView.setText(LuckgramStrings.errorMessage(m.errorCode));
                errorView.setVisibility(VISIBLE);
                content.setVisibility(TextUtils.isEmpty(m.text) && renderer == null ? GONE : VISIBLE);
            } else {
                errorView.setVisibility(GONE);
                content.setVisibility(VISIBLE);
            }
            requestLayout();
        }

        private void showMenu() {
            if (message == null || message.pending) return;
            final LuckgramAiConversation.Message m = message;
            String copy = renderer != null ? renderer.copyText(m) : m.text;
            ItemOptions options = ItemOptions.makeOptions(LuckgramAiChatActivity.this, bubble);
            if (!TextUtils.isEmpty(copy)) {
                options.add(R.drawable.msg_copy, LuckgramStrings.get("复制", "Copy"), () -> {
                    AndroidUtilities.addToClipboard(copy);
                    BulletinFactory.of(LuckgramAiChatActivity.this).createCopyBulletin(LuckgramStrings.get("已复制", "Copied")).show();
                });
            }
            if (m.errorCode != null && !m.isUser()) {
                options.add(R.drawable.msg_retry, LuckgramStrings.get("重试", "Retry"), () -> retry(m));
            }
            options.add(R.drawable.msg_delete, LuckgramStrings.get("删除", "Delete"), true, () -> {
                conversation.messages.remove(m);
                adapter.notifyDataSetChanged();
                updateEmpty();
                store().save(conversation);
            });
            options.show();
        }
    }

    /** Markdown → spans (spec 18 需求 4). */
    private CharSequence render(String md, int textColor) {
        LuckgramAiMarkdown.Result r = LuckgramAiMarkdown.parse(md);
        SpannableStringBuilder sb = new SpannableStringBuilder(r.text);
        int codeBg = ColorUtils.setAlphaComponent(textColor, 0x1f);
        for (LuckgramAiMarkdown.Span s : r.spans) {
            if (s.end <= s.start) continue;
            switch (s.type) {
                case LuckgramAiMarkdown.BOLD:
                    sb.setSpan(new StyleSpan(Typeface.BOLD), s.start, s.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                case LuckgramAiMarkdown.ITALIC:
                    sb.setSpan(new StyleSpan(Typeface.ITALIC), s.start, s.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                case LuckgramAiMarkdown.STRIKE:
                    sb.setSpan(new StrikethroughSpan(), s.start, s.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                case LuckgramAiMarkdown.CODE:
                case LuckgramAiMarkdown.CODE_BLOCK:
                    sb.setSpan(new TypefaceSpan("monospace"), s.start, s.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new BackgroundColorSpan(codeBg), s.start, s.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    if (s.type == LuckgramAiMarkdown.CODE_BLOCK) {
                        sb.setSpan(new RelativeSizeSpan(0.9f), s.start, s.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    break;
                case LuckgramAiMarkdown.HEADING:
                    sb.setSpan(new StyleSpan(Typeface.BOLD), s.start, s.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new RelativeSizeSpan("1".equals(s.value) ? 1.25f : "2".equals(s.value) ? 1.15f : 1.05f), s.start, s.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                case LuckgramAiMarkdown.LINK:
                    sb.setSpan(new URLSpan(s.value), s.start, s.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
            }
        }
        return sb;
    }
}
