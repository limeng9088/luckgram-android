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
    private TextView emptyView;
    private LinearLayout headerStrip;
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
        avatarContainer.setTitle(LuckgramStrings.get("Luckgram AI", "Luckgram AI"));
        avatarContainer.setSubtitle(LuckgramStrings.get("助手", "Assistant"));
        avatarContainer.setOnClickListener(null);
        actionBar.addView(avatarContainer, 0, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT, 56, 0, 96, 0));
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_NEW, R.drawable.msg_add).setContentDescription(LuckgramStrings.get("新对话", "New chat"));
        menu.addItem(MENU_HISTORY, R.drawable.msg_recent).setContentDescription(LuckgramStrings.get("历史对话", "Chat history"));

        SizeNotifierFrameLayout content = new SizeNotifierFrameLayout(context) {
            @Override
            protected boolean isActionBarVisible() {
                return true;
            }
        };
        content.setBackgroundImage(Theme.getCachedWallpaper(), Theme.isWallpaperMotion());
        fragmentView = content;

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        content.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // extension point 3: entries at the top of the page
        HorizontalScrollView headerScroll = new HorizontalScrollView(context);
        headerScroll.setHorizontalScrollBarEnabled(false);
        headerScroll.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        headerStrip = new LinearLayout(context);
        headerStrip.setOrientation(LinearLayout.HORIZONTAL);
        headerStrip.setPadding(dp(8), dp(6), dp(8), dp(6));
        headerScroll.addView(headerStrip);
        column.addView(headerScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        for (LuckgramAiRegistry.HeaderEntry entry : LuckgramAiRegistry.headerEntries()) {
            headerStrip.addView(chip(context, entry.title(), entry.icon, v -> entry.callback.run(this)));
        }
        headerScroll.setVisibility(headerStrip.getChildCount() > 0 ? View.VISIBLE : View.GONE);

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

        emptyView = new TextView(context);
        emptyView.setText(LuckgramStrings.get("有什么可以帮你？\n可以提问、总结或改写长文、讲解、解题。", "How can I help?\nAsk anything, summarize or rewrite text, explain, solve problems."));
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        emptyView.setTextColor(getThemedColor(Theme.key_chat_serviceText));
        emptyView.setPadding(dp(16), dp(10), dp(16), dp(10));
        GradientDrawable emptyBg = new GradientDrawable();
        emptyBg.setCornerRadius(dp(14));
        emptyBg.setColor(getThemedColor(Theme.key_chat_serviceBackground));
        emptyView.setBackground(emptyBg);
        listFrame.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 32, 0, 32, 0));

        // spec 18 需求 3 + extension point 1: quick buttons above the input field
        HorizontalScrollView quickScroll = new HorizontalScrollView(context);
        quickScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout quickRow = new LinearLayout(context);
        quickRow.setOrientation(LinearLayout.HORIZONTAL);
        quickRow.setPadding(dp(8), dp(4), dp(8), dp(4));
        quickScroll.addView(quickRow);
        for (LuckgramAiRegistry.QuickAction action : LuckgramAiRegistry.quickActions()) {
            quickRow.addView(chip(context, action.title(), action.icon, v -> action.callback.run(this)));
        }
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

    private View chip(Context context, String title, int icon, View.OnClickListener click) {
        TextView chip = new TextView(context);
        chip.setText(title);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        chip.setTypeface(AndroidUtilities.bold());
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
        int color = getThemedColor(Theme.key_chat_serviceText);
        chip.setTextColor(color);
        chip.setPadding(dp(12), 0, dp(12), 0);
        if (icon != 0) {
            android.graphics.drawable.Drawable d = context.getResources().getDrawable(icon).mutate();
            d.setColorFilter(new android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN));
            d.setBounds(0, 0, dp(18), dp(18));
            chip.setCompoundDrawables(d, null, null, null);
            chip.setCompoundDrawablePadding(dp(4));
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(15));
        bg.setColor(getThemedColor(Theme.key_chat_serviceBackground));
        chip.setBackground(bg);
        chip.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30));
        lp.setMargins(dp(3), 0, dp(3), 0);
        chip.setLayoutParams(lp);
        return chip;
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

    private void switchTo(LuckgramAiConversation c) {
        if (c == null || c == conversation) return;
        stopStreaming();
        conversation = c;
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
        request = LuckgramAI.getInstance().chat(getParentActivity(), conv.task, messages, true, new LuckgramAI.ChatListener() {
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
            }

            @Override
            public void onError(LuckgramAIError error) {
                if (streaming != reply) return;
                finishReply(conv, reply, error);
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
