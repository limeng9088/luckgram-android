package org.telegram.luckgram.ai;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import org.json.JSONObject;
import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramSettings;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;

/**
 * Debug demos of the five extension points (spec 18 验收：用调试代码各演示一次). They are registered
 * once but only act when the debug tools are visible (LuckgramSettings.isDebugToolsVisible()):
 * <ol>
 *   <li>quick button "测试按钮" → a bulletin;</li>
 *   <li>input interceptor {@code /echo text} → replies "echo: text" without any request;</li>
 *   <li>header entry "测试对话" → opens a conversation with key {@code debug_demo};</li>
 *   <li>that conversation has a system instruction ("answer in one sentence") sent with every request;</li>
 *   <li>message type {@code debug_card}: {@code /card text} adds one; {@code /unknown} adds an
 *       unregistered type to show the placeholder.</li>
 * </ol>
 */
final class LuckgramAiDebugDemo {

    private LuckgramAiDebugDemo() {
    }

    static final String TYPE_CARD = "debug_card";

    static void register() {
        // The registry is static; checking visibility at use time lets the debug switch take effect without a restart.
        LuckgramAiRegistry.registerQuickAction(new LuckgramAiRegistry.QuickAction("debug_button", "测试按钮", "Test button", R.drawable.msg_bots, host -> {
            if (LuckgramSettings.isDebugToolsVisible()) {
                BulletinFactory.of(host.getFragment()).createSimpleBulletin(R.raw.info, "Quick action: " + host.getConversation().id).show();
            }
        }).visibleWhen(LuckgramSettings::isDebugToolsVisible));
        LuckgramAiRegistry.registerInputInterceptor(new String[]{"/echo", "/card", "/unknown"}, (host, prefix, rest) -> {
            if (!LuckgramSettings.isDebugToolsVisible()) {
                return false;
            }
            host.addMessage(LuckgramAiConversation.Message.create(LuckgramAiConversation.Message.ROLE_USER, LuckgramAiConversation.Message.TYPE_TEXT, prefix + " " + rest));
            LuckgramAiConversation.Message reply;
            if ("/echo".equals(prefix)) {
                reply = LuckgramAiConversation.Message.create(LuckgramAiConversation.Message.ROLE_ASSISTANT, LuckgramAiConversation.Message.TYPE_TEXT, "echo: " + rest);
            } else if ("/card".equals(prefix)) {
                reply = LuckgramAiConversation.Message.create(LuckgramAiConversation.Message.ROLE_ASSISTANT, TYPE_CARD, rest);
                try {
                    reply.data = new JSONObject().put("created", System.currentTimeMillis());
                } catch (Exception ignore) {
                }
            } else {
                reply = LuckgramAiConversation.Message.create(LuckgramAiConversation.Message.ROLE_ASSISTANT, "debug_not_registered", rest);
            }
            host.addMessage(reply);
            return true;
        });
        LuckgramAiRegistry.registerHeaderEntry(new LuckgramAiRegistry.HeaderEntry("debug_conversation", "测试对话", "Test chat", R.drawable.msg_discussion, page -> {
            LuckgramAiConversation c = LuckgramAiStore.getInstance(page.getCurrentAccount()).findOrCreate("debug_demo", LuckgramAI.TASK_CHAT,
                "Always answer in exactly one sentence.", "测试对话 / Test chat");
            LuckgramAiChatActivity.open(page, c.id);
        }).visibleWhen(LuckgramSettings::isDebugToolsVisible));
        LuckgramAiRegistry.registerMessageType(TYPE_CARD, new LuckgramAiRegistry.MessageRenderer() {
            @Override
            public View createView(Context context, Theme.ResourcesProvider resourcesProvider) {
                TextView tv = new TextView(context);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                tv.setPadding(dp(10), dp(8), dp(10), dp(8));
                return tv;
            }

            @Override
            public void bind(View view, LuckgramAiConversation.Message message, boolean outgoing) {
                TextView tv = (TextView) view;
                tv.setText("🧪 debug_card\n" + message.text);
                tv.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(dp(10));
                bg.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
                tv.setBackground(bg);
            }

            @Override
            public String contextText(LuckgramAiConversation.Message message) {
                return null; // not sent to the model
            }
        });
    }
}
