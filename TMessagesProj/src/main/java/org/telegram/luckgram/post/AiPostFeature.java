package org.telegram.luckgram.post;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
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
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.Collections;

/**
 * spec 24: "写帖子" in the ✦ menu (registered through 21's extension point), only in channels where
 * the user can post. A topic and the channel's last 20 text posts (as style examples, sent only with
 * this request) give a streamed draft in the panel above the field; the draft can be edited,
 * shortened, given more emoji, translated or rewritten, then posted (now or scheduled) through the
 * chat's own send path.
 */
public final class AiPostFeature {

    private AiPostFeature() {
    }

    public static boolean isEnabled() {
        return LuckgramSettings.isEnabled(LuckgramFeatures.AI_POST);
    }

    /** The registration line used by AiWritingFeatures. */
    public static AiWritingRegistry.MenuItem menuItem() {
        return new AiWritingRegistry.MenuItem("ai_post", "写帖子", "Write a post", R.drawable.msg_edit, 10)
            .allowEmptyText()
            .visibleWhen(host -> isEnabled() && host.canPostInChannel())
            .onClick(host -> new Panel(host).show());
    }

    /** The channel's recent text posts, newest first. */
    static ArrayList<String> recentPosts(ChatActivity chat) {
        ArrayList<String> out = new ArrayList<>();
        for (MessageObject m : chat.messages) {
            if (m == null || m.messageOwner == null || m.getId() <= 0 || m.messageOwner instanceof TLRPC.TL_messageService) continue;
            if (!TextUtils.isEmpty(m.messageOwner.message)) out.add(m.messageOwner.message);
            if (out.size() >= AiPostText.EXAMPLES) break;
        }
        return out;
    }

    private static final String[][] LANGUAGES = {
        {"English", "English"}, {"Chinese (Simplified)", "简体中文"}, {"Japanese", "日本語"}, {"Korean", "한국어"},
        {"Russian", "Русский"}, {"Spanish", "Español"}, {"French", "Français"}, {"German", "Deutsch"}
    };

    private static final class Panel {
        final AiWritingRegistry.Host host;
        final LinearLayout root;
        final EditTextBoldCursor topicField;
        final EditTextBoldCursor draftField;
        final TextView status;
        final LinearLayout editRow;
        final LinearLayout postRow;
        final TextView generateButton;
        LuckgramAI.Request request;
        String examples = "";

        Panel(AiWritingRegistry.Host host) {
            this.host = host;
            Context ctx = host.getContext();
            root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(12), dp(4), dp(4), dp(8));

            LinearLayout header = new LinearLayout(ctx);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = label(ctx, 14, Theme.key_windowBackgroundWhiteGrayText2);
            title.setTypeface(AndroidUtilities.bold());
            title.setText(LuckgramStrings.get("写帖子", "Write a post"));
            header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            TextView close = button(ctx, "✕");
            close.setContentDescription(LuckgramStrings.get("关闭", "Close"));
            close.setOnClickListener(v -> {
                if (request != null) request.cancel();
                host.hidePanel(root);
            });
            header.addView(close);
            root.addView(header);

            LinearLayout topicRow = new LinearLayout(ctx);
            topicRow.setOrientation(LinearLayout.HORIZONTAL);
            topicRow.setGravity(Gravity.CENTER_VERTICAL);
            topicField = field(ctx, LuckgramStrings.get("主题，例如：周末促销，玫瑰八折", "Topic, e.g. Weekend sale, roses 20% off"));
            topicField.setSingleLine(true);
            CharSequence current = host.getText();
            if (current != null && current.toString().trim().length() > 0) topicField.setText(current.toString().trim());
            topicRow.addView(topicField, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            generateButton = button(ctx, LuckgramStrings.get("生成", "Write"));
            generateButton.setOnClickListener(v -> generate());
            topicRow.addView(generateButton);
            root.addView(topicRow);

            status = label(ctx, 14, Theme.key_windowBackgroundWhiteGrayText);
            status.setVisibility(View.GONE);
            root.addView(status, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 8, 0));

            draftField = field(ctx, LuckgramStrings.get("草稿", "Draft"));
            draftField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            draftField.setMinLines(3);
            draftField.setMaxLines(8);
            draftField.setVerticalScrollBarEnabled(true);
            draftField.setGravity(Gravity.TOP | Gravity.LEFT);
            draftField.setVisibility(View.GONE);
            root.addView(draftField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 8, 0));

            HorizontalScrollView editScroll = new HorizontalScrollView(ctx);
            editScroll.setHorizontalScrollBarEnabled(false);
            editRow = new LinearLayout(ctx);
            editRow.setOrientation(LinearLayout.HORIZONTAL);
            editScroll.addView(editRow);
            editRow.addView(action(ctx, LuckgramStrings.get("更简短", "Shorter"), () -> edit(AiPostText.SHORTER)));
            editRow.addView(action(ctx, LuckgramStrings.get("多点表情", "More emoji"), () -> edit(AiPostText.EMOJI)));
            editRow.addView(action(ctx, LuckgramStrings.get("翻译", "Translate"), this::pickLanguage));
            editRow.addView(action(ctx, LuckgramStrings.get("重写", "Rewrite"), () -> edit(AiPostText.REWRITE)));
            editRow.setVisibility(View.GONE);
            root.addView(editScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

            postRow = new LinearLayout(ctx);
            postRow.setOrientation(LinearLayout.HORIZONTAL);
            postRow.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            TextView schedule = action(ctx, LuckgramStrings.get("定时发布", "Schedule"), () -> publish(true));
            TextView publish = action(ctx, LuckgramStrings.get("发布", "Post"), () -> publish(false));
            publish.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
            publish.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(14), Theme.getColor(Theme.key_featuredStickers_addButton), Theme.getColor(Theme.key_featuredStickers_addButtonPressed)));
            postRow.addView(schedule);
            postRow.addView(publish, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 8, 0, 8, 0));
            postRow.setVisibility(View.GONE);
            root.addView(postRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
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

        private TextView action(Context ctx, String text, Runnable run) {
            TextView t = button(ctx, text);
            t.setOnClickListener(v -> run.run());
            return t;
        }

        private EditTextBoldCursor field(Context ctx, String hint) {
            EditTextBoldCursor f = new EditTextBoldCursor(ctx);
            f.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            f.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            f.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
            f.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            f.setHint(hint);
            f.setBackground(Theme.createRoundRectDrawable(dp(10), Theme.getColor(Theme.key_windowBackgroundGray)));
            f.setPadding(dp(10), dp(8), dp(10), dp(8));
            return f;
        }

        void show() {
            host.showPanel(root);
            topicField.requestFocus();
        }

        private int maxChars() {
            return (int) Math.min(Integer.MAX_VALUE, LuckgramSettings.remoteLong("ai.max_input_chars", 60000));
        }

        private void generate() {
            String topic = topicField.getText().toString().trim();
            if (topic.isEmpty()) {
                AndroidUtilities.shakeViewSpring(topicField, 4);
                return;
            }
            ChatActivity chat = host.getChatActivity();
            if (chat == null) return;
            // spec 24 需求 5: the example posts go only into this request
            examples = AiPostText.examples(recentPosts(chat), maxChars() / 2);
            run(AiPostText.generate(examples, topic));
        }

        private void edit(String instruction) {
            String draft = draftField.getText().toString();
            if (draft.trim().isEmpty()) return;
            run(AiPostText.edit(examples, draft, instruction));
        }

        private void pickLanguage() {
            CharSequence[] names = new CharSequence[LANGUAGES.length];
            for (int i = 0; i < LANGUAGES.length; i++) names[i] = LANGUAGES[i][1];
            ChatActivity chat = host.getChatActivity();
            if (chat == null) return;
            AlertDialog.Builder b = new AlertDialog.Builder(host.getContext(), chat.getResourceProvider());
            b.setTitle(LuckgramStrings.get("翻译成", "Translate into"));
            b.setItems(names, (d, which) -> edit(AiPostText.translate(LANGUAGES[which][0])));
            chat.showDialog(b.create());
        }

        private void run(String content) {
            ChatActivity chat = host.getChatActivity();
            if (chat == null) return;
            if (request != null) request.cancel();
            status.setVisibility(View.VISIBLE);
            status.setText(LuckgramStrings.get("正在写…", "Writing…"));
            generateButton.setEnabled(false);
            setEditingEnabled(false);
            request = LuckgramAI.getInstance().chat(chat.getParentActivity(), LuckgramAI.TASK_POST,
                Collections.singletonList(LuckgramAI.Message.user(content)), true, new LuckgramAI.ChatListener() {
                    @Override
                    public void onDelta(String delta, String text) {
                        showDraft(text);
                    }

                    @Override
                    public void onComplete(String text) {
                        request = null;
                        generateButton.setEnabled(true);
                        setEditingEnabled(true);
                        status.setVisibility(View.GONE);
                        if (text != null && !text.trim().isEmpty()) showDraft(text.trim());
                    }

                    @Override
                    public void onError(LuckgramAIError error) {
                        request = null;
                        generateButton.setEnabled(true);
                        setEditingEnabled(true);
                        if (LuckgramAIError.CONSENT_DENIED.equals(error.code)) {
                            status.setVisibility(View.GONE);
                            return;
                        }
                        status.setVisibility(View.VISIBLE);
                        status.setText(error.getLocalizedMessage());
                    }
                });
        }

        private void showDraft(String text) {
            draftField.setVisibility(View.VISIBLE);
            editRow.setVisibility(View.VISIBLE);
            postRow.setVisibility(View.VISIBLE);
            draftField.setText(text);
            draftField.setSelection(draftField.length());
        }

        private void setEditingEnabled(boolean enabled) {
            for (int i = 0; i < editRow.getChildCount(); i++) editRow.getChildAt(i).setEnabled(enabled);
            for (int i = 0; i < postRow.getChildCount(); i++) postRow.getChildAt(i).setEnabled(enabled);
            draftField.setEnabled(enabled);
        }

        /** spec 24 需求 4: posted through the chat's own send path, like the admin's Send button. */
        private void publish(boolean schedule) {
            String draft = draftField.getText().toString().trim();
            if (draft.isEmpty()) return;
            if (request != null) return;
            host.send(draft, schedule);
            if (!schedule) host.hidePanel(root);
        }
    }
}
