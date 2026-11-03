package org.telegram.luckgram.onboarding;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import org.telegram.luckgram.LuckgramStrings;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Luckgram 97: on the login code page, when the code was sent to the Telegram app (not SMS),
 * a card explains where to find it, plus a "How can I receive the code?" button with a sheet.
 * Same texts and layout as iOS {@code LuckgramCodeHelp}.
 */
public final class CodeHelpCard extends LinearLayout {

    // ---------------------------------------------------------------- strings

    public static String titleText() {
        return LuckgramStrings.get("验证码已发到你的 Telegram", "Your code was sent to Telegram");
    }

    public static String[] stepTexts() {
        return new String[]{
                LuckgramStrings.get("在另一台已登录的设备上打开 Telegram（或 Luckgram）。", "Open Telegram (or Luckgram) on another device where you're logged in."),
                LuckgramStrings.get("在聊天列表里找到官方账号「Telegram」发来的消息（带蓝色认证标）。", "In the chat list, find the message from the official \"Telegram\" account (with the blue check)."),
                LuckgramStrings.get("把消息里的 5–6 位验证码填到上面。", "Enter the 5–6 digit code from that message above.")
        };
    }

    public static String okText() {
        return LuckgramStrings.get("知道了", "OK");
    }

    public static String helpButtonText() {
        return LuckgramStrings.get("收不到验证码？", "How can I receive the code?");
    }

    public static String helpTitleText() {
        return LuckgramStrings.get("怎样收到验证码", "How to receive the code");
    }

    public static String[] helpTexts() {
        return new String[]{
                LuckgramStrings.get("验证码会优先发到你已登录的 Telegram App 里，而不是短信。请在手机、电脑或平板上已登录的 Telegram（或 Luckgram）中查看官方「Telegram」账号的消息。",
                        "The code goes to the Telegram app where you're already logged in, not by SMS. Check the message from the official \"Telegram\" account in Telegram (or Luckgram) on your phone, computer or tablet."),
                LuckgramStrings.get("如果所有设备都已退出登录，点验证码页下方的「没有收到验证码？」，可以改为通过短信接收（是否可用由 Telegram 决定）。",
                        "If you're logged out everywhere, tap \"Didn't get the code?\" below the code field to get it by SMS instead (if Telegram allows it)."),
                LuckgramStrings.get("如果一直连不上网络，先点右上角的代理图标配置代理，再重新获取验证码。",
                        "If you can't connect, tap the proxy icon at the top right to set up a proxy first, then request the code again.")
        };
    }

    /** Highlights "Telegram" / "Luckgram" with the accent colour, like the reference layout. */
    public static CharSequence highlight(String text, int accent) {
        SpannableStringBuilder builder = new SpannableStringBuilder(text);
        for (String word : new String[]{"Telegram", "Luckgram"}) {
            int index = text.indexOf(word);
            while (index >= 0) {
                builder.setSpan(new ForegroundColorSpan(accent), index, index + word.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                builder.setSpan(new StyleSpan(Typeface.BOLD), index, index + word.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                index = text.indexOf(word, index + word.length());
            }
        }
        return builder;
    }

    // ---------------------------------------------------------------- card

    private final Runnable onDismiss;

    public CodeHelpCard(Context context, Runnable onDismiss) {
        super(context);
        this.onDismiss = onDismiss;
        setOrientation(VERTICAL);
        setPadding(dp(16), dp(14), dp(16), dp(14));

        int text = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        int secondary = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText6);
        int accent = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4);

        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(16));
        background.setColor(Theme.multAlpha(text, 0.05f));
        setBackground(background);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(context);
        Drawable bubble = ContextCompat.getDrawable(context, R.drawable.msg_discussion);
        if (bubble != null) {
            bubble = bubble.mutate();
            bubble.setColorFilter(new PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN));
            icon.setImageDrawable(bubble);
        }
        header.addView(icon, LayoutHelper.createLinear(24, 24, 0, 0, 10, 0));
        TextView title = new TextView(context);
        title.setText(highlight(titleText(), accent));
        title.setTextColor(text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        title.setTypeface(AndroidUtilities.bold());
        header.addView(title, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        String[] steps = stepTexts();
        for (int i = 0; i < steps.length; i++) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(HORIZONTAL);
            TextView number = new TextView(context);
            number.setText((i + 1) + ".");
            number.setTextColor(secondary);
            number.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            row.addView(number, LayoutHelper.createLinear(20, LayoutHelper.WRAP_CONTENT));
            TextView step = new TextView(context);
            step.setText(highlight(steps[i], accent));
            step.setTextColor(secondary);
            step.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            step.setLineSpacing(dp(2), 1f);
            row.addView(step, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, i == 0 ? 10 : 6, 0, 0));
        }

        TextView ok = new TextView(context);
        ok.setText(okText());
        ok.setGravity(Gravity.CENTER);
        ok.setTextColor(0xffffffff);
        ok.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        ok.setTypeface(AndroidUtilities.bold());
        GradientDrawable button = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xff3c8cff, 0xff7a5cff});
        button.setCornerRadius(dp(10));
        ok.setBackground(button);
        ok.setOnClickListener(v -> {
            if (this.onDismiss != null) {
                this.onDismiss.run();
            }
        });
        addView(ok, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40, 0, 12, 0, 0));
    }

    /** "How can I receive the code?" link under the card. */
    public static TextView helpButton(Context context, Runnable onClick) {
        TextView view = new TextView(context);
        view.setText(helpButtonText());
        view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(14), dp(8), dp(14), dp(8));
        view.setBackground(Theme.createSelectorDrawable(Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4), 0.12f), Theme.RIPPLE_MASK_ROUNDRECT_6DP));
        view.setOnClickListener(v -> onClick.run());
        return view;
    }

    /** Bottom sheet with the help text. */
    public static void showHelp(Context context) {
        BottomSheet.Builder builder = new BottomSheet.Builder(context);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(VERTICAL);
        content.setPadding(dp(21), dp(8), dp(21), dp(12));
        int text = Theme.getColor(Theme.key_dialogTextBlack);
        int accent = Theme.getColor(Theme.key_dialogTextBlue);
        TextView title = new TextView(context);
        title.setText(helpTitleText());
        title.setTextColor(text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        title.setTypeface(AndroidUtilities.bold());
        content.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 4));
        for (String paragraph : helpTexts()) {
            TextView view = new TextView(context);
            view.setText(highlight("• " + paragraph, accent));
            view.setTextColor(text);
            view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            view.setLineSpacing(dp(2), 1f);
            content.addView(view, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
        }
        builder.setCustomView(content);
        builder.show();
    }
}
