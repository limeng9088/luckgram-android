package org.telegram.luckgram.reply;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramAIError;
import org.telegram.luckgram.LuckgramFeatures;
import org.telegram.luckgram.LuckgramSettings;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.luckgram.writing.AiWritingRegistry;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * spec 20: "帮我回复" in the ✦ menu of the message field (registered through 21's extension point).
 * Sends the chat's last 20 messages and the draft with {@code task=reply}; the three candidates
 * appear one by one in a panel above the field; tapping one replaces the field's text (undoable).
 */
public final class AiReplyFeature {

    private AiReplyFeature() {
    }

    /** spec 20 需求 2 */
    static final int CONTEXT_MESSAGES = 20;
    /** One message in the context is cut to this many characters. */
    static final int MAX_MESSAGE_CHARS = 1000;

    public static boolean isEnabled() {
        return LuckgramSettings.isEnabled(LuckgramFeatures.AI_REPLY);
    }

    /** The registration line used by AiWritingFeatures. */
    public static AiWritingRegistry.MenuItem menuItem() {
        return new AiWritingRegistry.MenuItem("ai_reply", "帮我回复", "Help me reply", R.drawable.menu_reply, 0)
            .allowEmptyText()
            .visibleWhen(host -> isEnabled() && host.getChatActivity() != null)
            .onClick(host -> new Panel(host).show());
    }

    /** Recent messages, oldest first, as "name: text" lines. */
    static String transcript(ChatActivity chat) {
        ArrayList<String> lines = new ArrayList<>();
        ArrayList<MessageObject> messages = chat.messages;
        for (int i = 0; i < messages.size() && lines.size() < CONTEXT_MESSAGES; i++) {
            MessageObject m = messages.get(i);
            if (m == null || m.messageOwner == null || m.getId() == 0 || m.isDateObject || m.messageOwner instanceof TLRPC.TL_messageService) {
                continue;
            }
            String text = m.messageOwner.message;
            if (TextUtils.isEmpty(text)) {
                if (m.isPhoto()) text = LuckgramStrings.get("[图片]", "[Photo]");
                else if (m.isVoice()) text = LuckgramStrings.get("[语音]", "[Voice message]");
                else if (m.isVideo() || m.isRoundVideo()) text = LuckgramStrings.get("[视频]", "[Video]");
                else if (m.isSticker() || m.isAnimatedSticker()) text = LuckgramStrings.get("[贴纸]", "[Sticker]");
                else if (m.getDocument() != null) text = LuckgramStrings.get("[文件]", "[File]");
                else continue;
            }
            if (text.length() > MAX_MESSAGE_CHARS) text = text.substring(0, MAX_MESSAGE_CHARS) + "…";
            String name;
            if (m.isOutOwner()) {
                name = LuckgramStrings.get("我", "Me");
            } else {
                TLRPC.User u = chat.getMessagesController().getUser(m.getSenderId());
                if (u != null) {
                    name = UserObject.getUserName(u);
                } else {
                    TLRPC.Chat c = chat.getMessagesController().getChat(-m.getSenderId());
                    name = c != null ? c.title : "?";
                }
            }
            lines.add(0, name + ": " + text.replace('\n', ' '));
        }
        return TextUtils.join("\n", lines);
    }

    private static final class Panel {
        final AiWritingRegistry.Host host;
        final LinearLayout root;
        final LinearLayout list;
        final TextView status;
        final TextView again;
        final TextView undo;
        LuckgramAI.Request request;
        /** The user's own text when the panel opened; "换一批" always starts from it. */
        String draft = "";

        Panel(AiWritingRegistry.Host host) {
            this.host = host;
            Context ctx = host.getContext();
            root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(4), dp(4), dp(4), dp(6));

            LinearLayout header = new LinearLayout(ctx);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = label(ctx, 14, Theme.key_windowBackgroundWhiteGrayText2);
            title.setText(LuckgramStrings.get("帮我回复", "Help me reply"));
            title.setTypeface(AndroidUtilities.bold());
            title.setPadding(dp(10), 0, 0, 0);
            header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            undo = button(ctx, LuckgramStrings.get("撤销", "Undo"));
            undo.setVisibility(View.GONE);
            undo.setOnClickListener(v -> {
                host.undo();
                undo.setVisibility(View.GONE);
            });
            header.addView(undo);
            again = button(ctx, LuckgramStrings.get("换一批", "Try again"));
            again.setOnClickListener(v -> generate());
            header.addView(again);
            TextView close = button(ctx, "✕");
            close.setContentDescription(LuckgramStrings.get("关闭", "Close"));
            close.setOnClickListener(v -> {
                if (request != null) request.cancel();
                host.hidePanel(root);
            });
            header.addView(close);
            root.addView(header);

            status = label(ctx, 14, Theme.key_windowBackgroundWhiteGrayText);
            status.setPadding(dp(10), dp(4), dp(10), dp(4));
            root.addView(status);
            list = new LinearLayout(ctx);
            list.setOrientation(LinearLayout.VERTICAL);
            root.addView(list);
        }

        private TextView label(Context ctx, int size, int colorKey) {
            TextView t = new TextView(ctx);
            t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
            t.setTextColor(Theme.getColor(colorKey));
            return t;
        }

        private TextView button(Context ctx, String text) {
            TextView t = label(ctx, 14, Theme.key_windowBackgroundWhiteBlueText);
            t.setText(text);
            t.setTypeface(AndroidUtilities.bold());
            t.setPadding(dp(10), dp(6), dp(10), dp(6));
            t.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
            return t;
        }

        void show() {
            draft = host.getText() == null ? "" : host.getText().toString();
            host.showPanel(root);
            generate();
        }

        void generate() {
            if (request != null) request.cancel();
            ChatActivity chat = host.getChatActivity();
            if (chat == null) return;
            list.removeAllViews();
            status.setVisibility(View.VISIBLE);
            status.setText(LuckgramStrings.get("正在生成…", "Writing…"));
            again.setEnabled(false);
            String content = AiReplyText.prompt(transcript(chat), draft, LuckgramStrings.get("我", "Me"));
            request = LuckgramAI.getInstance().chat(chat.getParentActivity(), LuckgramAI.TASK_REPLY,
                Collections.singletonList(LuckgramAI.Message.user(content)), true, new LuckgramAI.ChatListener() {
                    @Override
                    public void onDelta(String delta, String text) {
                        showCandidates(AiReplyText.candidates(text));
                    }

                    @Override
                    public void onComplete(String text) {
                        request = null;
                        again.setEnabled(true);
                        List<String> c = AiReplyText.candidates(text);
                        showCandidates(c);
                        if (c.isEmpty()) {
                            status.setText(LuckgramStrings.get("没有生成回复，请换一批", "No replies. Please try again"));
                        }
                    }

                    @Override
                    public void onError(LuckgramAIError error) {
                        request = null;
                        again.setEnabled(true);
                        if (LuckgramAIError.CONSENT_DENIED.equals(error.code)) {
                            host.hidePanel(root);
                            return;
                        }
                        status.setVisibility(View.VISIBLE);
                        status.setText(error.getLocalizedMessage());
                    }
                });
        }

        void showCandidates(List<String> candidates) {
            if (!candidates.isEmpty()) status.setVisibility(View.GONE);
            Context ctx = host.getContext();
            for (int i = 0; i < candidates.size(); i++) {
                TextView row;
                if (i < list.getChildCount()) {
                    row = (TextView) list.getChildAt(i);
                } else {
                    row = label(ctx, 15, Theme.key_windowBackgroundWhiteBlackText);
                    row.setPadding(dp(12), dp(8), dp(12), dp(8));
                    row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
                    list.addView(row);
                }
                final String text = candidates.get(i);
                row.setText(text);
                row.setOnClickListener(v -> {
                    // spec 20 需求 3: replaces the field (undoable), never sends
                    boolean firstPick = undo.getVisibility() != View.VISIBLE;
                    if (firstPick) {
                        host.setText(text, true);
                    } else {
                        host.setText(text, false);
                    }
                    undo.setVisibility(View.VISIBLE);
                });
            }
        }
    }
}
