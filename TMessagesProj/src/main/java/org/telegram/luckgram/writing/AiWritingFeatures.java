package org.telegram.luckgram.writing;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.luckgram.LuckgramSettings;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

/**
 * Registration of everything in the ✦ menu. Features that extend AI writing (20, 24) add one line
 * here and keep their code in their own files (spec 20 / 24 平台入口提示).
 */
public final class AiWritingFeatures {

    private AiWritingFeatures() {
    }

    private static boolean registered;

    static void registerAll() {
        if (registered) return;
        registered = true;

        // spec 21 需求 1: the five effects (server prompts/writing-<effect>.md)
        effect("polish", "润色", "Polish", R.drawable.msg_photo_settings, 100, "已润色", "Polished");
        effect("formal", "更正式", "More formal", R.drawable.msg_work, 110, "已改得更正式", "Made more formal");
        effect("friendly", "更友好", "Friendlier", R.drawable.msg_emoji_smiles, 120, "已改得更友好", "Made friendlier");
        effect("shorter", "更简短", "Shorter", R.drawable.msg_text_outlined, 130, "已缩短", "Shortened");
        effect("grammar", "修正语法", "Fix grammar", R.drawable.msg_edit, 140, "已修正语法", "Grammar fixed");

        registerDebugDemo();
        // ---- other features register here (20 AI reply, 24 AI post) ----
        AiWritingRegistry.register(org.telegram.luckgram.reply.AiReplyFeature.menuItem());
        AiWritingRegistry.register(org.telegram.luckgram.post.AiPostFeature.menuItem());
    }

    private static void effect(String effect, String zh, String en, int icon, int order, String doneZh, String doneEn) {
        AiWritingRegistry.register(new AiWritingRegistry.MenuItem(effect, zh, en, icon, order)
            .onClick(host -> {
                if (host instanceof AiWritingController) {
                    ((AiWritingController) host).runEffect(effect, doneZh, doneEn);
                }
            }));
    }

    /**
     * spec 21 验收：a test item placed first and shown only in channels, opening a test panel that
     * writes into the field with undo. Only with the debug tools on.
     */
    private static void registerDebugDemo() {
        AiWritingRegistry.register(new AiWritingRegistry.MenuItem("debug_panel", "测试面板", "Test panel", R.drawable.msg_bots, -1)
            .allowEmptyText()
            .visibleWhen(host -> LuckgramSettings.isDebugToolsVisible() && host.getChatActivity() != null
                && host.getChatActivity().getCurrentChat() != null && org.telegram.messenger.ChatObject.isChannelAndNotMegaGroup(host.getChatActivity().getCurrentChat()))
            .onClick(host -> {
                Context ctx = host.getContext();
                LinearLayout panel = new LinearLayout(ctx);
                panel.setOrientation(LinearLayout.HORIZONTAL);
                panel.setGravity(Gravity.CENTER_VERTICAL);
                panel.setPadding(dp(12), dp(8), dp(8), dp(8));
                TextView label = new TextView(ctx);
                label.setText("Debug panel");
                label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                panel.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                TextView write = new TextView(ctx);
                write.setText("写入 / Write");
                write.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                write.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
                write.setPadding(dp(10), dp(6), dp(10), dp(6));
                write.setOnClickListener(v -> host.setText("Text written by the debug panel ✍️", true));
                panel.addView(write);
                TextView close = new TextView(ctx);
                close.setText("✕");
                close.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
                close.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
                close.setPadding(dp(10), dp(6), dp(6), dp(6));
                close.setOnClickListener(v -> host.hidePanel(panel));
                panel.addView(close);
                host.showPanel(panel);
            }));
    }
}
