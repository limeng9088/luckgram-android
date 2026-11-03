package org.telegram.luckgram.pro;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;
import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramAIError;
import org.telegram.luckgram.LuckgramSettings;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.LoginActivity;

/**
 * specs#87 (29) 需求 7: "Why not Pro for now?" after "Continue without Pro" on the onboarding Pro
 * step, once per device. Continue with an option posts {@code /v1/feedback/pro-skip} without waiting
 * (no retry, no message on failure) and goes to login; Skip or Continue with nothing picked sends
 * nothing. Only the option code and source are sent; no Telegram account exists yet.
 */
public class ProSkipSurveyActivity extends BaseFragment {

    private static final int CARD = 0xFF1C1C1E;
    private static final int SECONDARY_TEXT = 0xFF8E8E93;

    private final ProPerks.Texts texts = new ProPerks.Texts(LuckgramStrings.isChinese());
    private final TextView[] options = new TextView[ProPerks.SURVEY_REASONS.length];
    private int choice = -1;
    private boolean done;

    @Override
    public boolean onFragmentCreate() {
        LuckgramSettings.setBool(ProPerks.SURVEY_SHOWN_KEY, true); // once per device, even if left another way
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        int accent = Theme.getColor(Theme.key_featuredStickers_addButton);

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(0xFF000000);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        int top = (int) (AndroidUtilities.statusBarHeight / AndroidUtilities.density);
        TextView skip = ProUi.text(context, 16, SECONDARY_TEXT);
        skip.setText(texts.surveySkip());
        skip.setTypeface(AndroidUtilities.bold());
        skip.setGravity(Gravity.CENTER);
        skip.setPadding(dp(14), 0, dp(14), 0);
        skip.setOnClickListener(v -> finish(false));
        column.addView(skip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 44, Gravity.RIGHT, 0, top + 6, 8, 0));

        column.addView(new View(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        ImageView logo = new ImageView(context);
        try {
            Drawable icon = context.getPackageManager().getApplicationIcon(context.getApplicationInfo());
            logo.setImageDrawable(icon);
        } catch (Throwable e) {
            FileLog.e(e);
        }
        column.addView(logo, LayoutHelper.createLinear(72, 72, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 18));

        TextView subtitle = ProUi.text(context, 15, SECONDARY_TEXT);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setText(texts.surveySubtitle());
        column.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 6));

        TextView title = ProUi.text(context, 26, 0xFFFFFFFF);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        String t = texts.surveyTitle();
        SpannableString span = new SpannableString(t);
        int at = t.indexOf(texts.surveyTitleAccent());
        if (at >= 0) {
            span.setSpan(new ForegroundColorSpan(accent), at, at + texts.surveyTitleAccent().length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        title.setText(span);
        column.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 24));

        String[] labels = texts.surveyOptions();
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView o = ProUi.text(context, 16, 0xFFFFFFFF);
            o.setText(labels[i]);
            o.setGravity(Gravity.CENTER_VERTICAL);
            o.setPadding(dp(18), 0, dp(18), 0);
            o.setOnClickListener(v -> select(index, accent));
            options[i] = o;
            column.addView(o, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 54, 20, i == 0 ? 0 : 10, 20, 0));
        }
        select(-1, accent);

        column.addView(new View(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        TextView next = ProUi.text(context, 16, 0xFF000000);
        next.setTypeface(AndroidUtilities.bold());
        next.setGravity(Gravity.CENTER);
        next.setText(texts.surveyContinue());
        next.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(26), 0xFFFFFFFF, 0xFFDDDDDD));
        next.setOnClickListener(v -> finish(true));
        column.addView(next, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52, 20, 0, 20, 28));

        fragmentView = root;
        return root;
    }

    private void select(int index, int accent) {
        choice = index;
        for (int i = 0; i < options.length; i++) {
            if (options[i] == null) continue;
            android.graphics.drawable.GradientDrawable bg = ProUi.round(CARD, 14);
            if (i == index) bg.setStroke(dp(2), accent);
            options[i].setBackground(bg);
            options[i].setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
            options[i].setText((i == index ? "✓  " : "") + texts.surveyOptions()[i]);
        }
    }

    private void finish(boolean submit) {
        if (done) return;
        done = true;
        String reason = submit ? ProPerks.surveyReason(choice) : null;
        if (reason != null) send(reason);
        presentFragment(new LoginActivity(), true);
    }

    /** Fire and forget: login opens at once; failures are only logged. */
    private static void send(String reason) {
        try {
            JSONObject body = new JSONObject().put("reason", reason).put("source", ProPerks.SURVEY_SOURCE);
            LuckgramAI.getInstance().requestJson("POST", ProPerks.SURVEY_PATH, body, new LuckgramAI.Callback<JSONObject>() {
                @Override
                public void onResult(JSONObject result) {
                }

                @Override
                public void onError(LuckgramAIError error) {
                    FileLog.e("luckgram pro-skip: " + (error != null ? error.code : "?"));
                }
            });
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (invoked) finish(false);
        return false;
    }

    @Override
    public boolean isLightStatusBar() {
        return false;
    }

    @Override
    public int getNavigationBarColor() {
        return 0xFF000000;
    }
}
