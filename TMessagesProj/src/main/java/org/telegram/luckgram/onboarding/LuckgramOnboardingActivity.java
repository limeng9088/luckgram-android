package org.telegram.luckgram.onboarding;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;
import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramAIError;
import org.telegram.luckgram.LuckgramSettings;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.luckgram.account.AccountLogic;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.LoginActivity;

import java.util.List;

/**
 * specs#76: onboarding steps between the welcome page and login: "n/N"
 * and a progress bar on top, a big title, text, an optional bold question, an illustration card
 * (drawn in code, made-up content) and a white button with a "not now" text button under it.
 * The two buttons write the step's feature switches; the Pro step (only while billing.enabled)
 * remembers "Subscribe" and opens the Pro page after login, since paying needs a Telegram account.
 */
public class LuckgramOnboardingActivity extends BaseFragment {

    private static final int CARD = 0xFF1C1C1E;
    private static final int CARD_LIGHT = 0xFF2C2C2E;
    private static final int SECONDARY_TEXT = 0xFF8E8E93;

    private final WelcomeLogic.Texts texts = new WelcomeLogic.Texts(LuckgramStrings.isChinese());
    private List<WelcomeLogic.Step> steps;
    private int index;

    private TextView progressText;
    private ProgressBar progressBar;
    private LinearLayout content;
    private TextView titleView;
    private TextView bodyView;
    private TextView questionView;
    private FrameLayout cardContainer;
    private TextView primaryButton;
    private TextView secondaryButton;

    private AccountLogic.Plan cheapestPlan;
    private LuckgramAI.Request plansRequest;

    @Override
    public boolean onFragmentCreate() {
        steps = WelcomeLogic.steps(LuckgramSettings.remoteConfig().getBool("billing.enabled", false));
        if (steps.contains(WelcomeLogic.Step.PRO)) {
            loadPlans();
        }
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        if (plansRequest != null) plansRequest.cancel();
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(0xFF000000);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        progressText = text(context, 14, SECONDARY_TEXT);
        header.addView(progressText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 12, 0));
        progressBar = new ProgressBar(context);
        header.addView(progressBar, LayoutHelper.createLinear(0, 6, 1f));
        column.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 14 + AndroidUtilities.statusBarHeight / AndroidUtilities.density, 20, 0));

        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        column.addView(content, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        titleView = text(context, 30, 0xFFFFFFFF);
        content.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 18, 20, 10));
        bodyView = text(context, 16, 0xFFE5E5EA);
        bodyView.setLineSpacing(dp(2), 1f);
        content.addView(bodyView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 0, 20, 0));
        questionView = text(context, 16, 0xFFFFFFFF);
        questionView.setTypeface(AndroidUtilities.bold());
        content.addView(questionView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 14, 20, 0));
        content.addView(new View(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
        cardContainer = new FrameLayout(context);
        content.addView(cardContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 12, 20, 0));

        primaryButton = text(context, 16, 0xFF000000);
        primaryButton.setTypeface(AndroidUtilities.bold());
        primaryButton.setGravity(Gravity.CENTER);
        primaryButton.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(26), 0xFFFFFFFF, 0xFFDDDDDD));
        primaryButton.setOnClickListener(v -> choose(true));
        column.addView(primaryButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52, 20, 20, 20, 0));

        secondaryButton = text(context, 15, SECONDARY_TEXT);
        secondaryButton.setGravity(Gravity.CENTER);
        secondaryButton.setTypeface(AndroidUtilities.bold());
        secondaryButton.setOnClickListener(v -> choose(false));
        column.addView(secondaryButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 20, 4, 20, 24));

        bind(false);
        fragmentView = root;
        return root;
    }

    private static TextView text(Context context, int sp, int color) {
        TextView v = new TextView(context);
        v.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sp);
        v.setTextColor(color);
        return v;
    }

    // ---------------------------------------------------------------- flow

    private void choose(boolean primary) {
        WelcomeLogic.Step step = steps.get(index);
        if (!primary && !WelcomeLogic.hasSecondary(step)) {
            return;
        }
        for (String key : WelcomeLogic.toggles(step)) {
            LuckgramSettings.setEnabled(key, primary);
        }
        if (step == WelcomeLogic.Step.PRO) {
            LuckgramSettings.setBool(WelcomeLogic.OPEN_PRO_AFTER_LOGIN_KEY, primary);
        }
        if (index + 1 < steps.size()) {
            index++;
            bind(true);
        } else {
            LuckgramSettings.setBool(WelcomeLogic.ONBOARDED_KEY, true);
            presentFragment(new LoginActivity(), true);
        }
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (index > 0) {
            if (invoked) {
                index--;
                bind(true);
            }
            return false;
        }
        return super.onBackPressed(invoked);
    }

    private void bind(boolean animated) {
        if (content == null) return;
        WelcomeLogic.Step step = steps.get(index);
        progressText.setText(WelcomeLogic.progress(index, steps.size()));
        progressBar.setFraction(WelcomeLogic.progressFraction(index, steps.size()), animated);
        Runnable apply = () -> {
            titleView.setText(texts.title(step));
            bodyView.setText(texts.body(step));
            String q = texts.question(step);
            questionView.setText(q);
            questionView.setVisibility(q != null ? View.VISIBLE : View.GONE);
            cardContainer.removeAllViews();
            cardContainer.addView(card(step), LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            primaryButton.setText(step == WelcomeLogic.Step.PRO && cheapestPlan != null ? texts.subscribe(cheapestPlan.stars, cheapestPlan.days) : texts.primary(step));
            String sec = texts.secondary(step);
            secondaryButton.setText(sec);
            secondaryButton.setVisibility(sec != null ? View.VISIBLE : View.INVISIBLE);
        };
        if (!animated) {
            apply.run();
            return;
        }
        content.animate().cancel();
        content.animate().alpha(0f).translationX(-dp(24)).setDuration(140).setInterpolator(CubicBezierInterpolator.EASE_IN).withEndAction(() -> {
            apply.run();
            content.setTranslationX(dp(24));
            content.animate().alpha(1f).translationX(0).setDuration(220).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        }).start();
    }

    private void loadPlans() {
        plansRequest = LuckgramAI.getInstance().requestJson("GET", "/v1/billing/plans", null, new LuckgramAI.Callback<JSONObject>() {
            @Override
            public void onResult(JSONObject result) {
                AccountLogic.Billing billing = AccountLogic.parseBilling(result);
                for (AccountLogic.Plan p : billing.plans) {
                    if (cheapestPlan == null || p.stars < cheapestPlan.stars) cheapestPlan = p;
                }
                if (steps != null && index < steps.size() && steps.get(index) == WelcomeLogic.Step.PRO && primaryButton != null && cheapestPlan != null) {
                    primaryButton.setText(texts.subscribe(cheapestPlan.stars, cheapestPlan.days));
                }
            }

            @Override
            public void onError(LuckgramAIError error) {
                // The button stays "Subscribe"; the Pro page after login loads the plans again.
            }
        });
    }

    // ---------------------------------------------------------------- illustration cards

    private View card(WelcomeLogic.Step step) {
        Context c = getContext();
        switch (step) {
            case AI: return aiCard(c);
            case AGENTS: return agentsCard(c);
            case SUMMARY: return summaryCard(c);
            case DISCOVER: return discoverCard(c);
            default: return proCard(c);
        }
    }

    private static GradientDrawable round(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private static LinearLayout panel(Context c, int color) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(round(color, 22));
        l.setPadding(dp(14), dp(14), dp(14), dp(14));
        return l;
    }

    private static TextView bubble(Context c, String text, boolean out) {
        TextView t = text(c, 15, 0xFFFFFFFF);
        t.setText(text);
        t.setBackground(round(out ? 0xFF2F6EA5 : CARD_LIGHT, 16));
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        return t;
    }

    private View aiCard(Context c) {
        LinearLayout p = panel(c, CARD);
        TextView name = text(c, 15, 0xFFFFFFFF);
        name.setTypeface(AndroidUtilities.bold());
        name.setGravity(Gravity.CENTER);
        name.setText(texts.demoChatName());
        p.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        TextView status = text(c, 12, SECONDARY_TEXT);
        status.setGravity(Gravity.CENTER);
        status.setText(texts.demoChatStatus());
        p.addView(status, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));
        p.addView(bubble(c, texts.demoIncoming(), false), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT));

        LinearLayout quick = new LinearLayout(c);
        quick.setOrientation(LinearLayout.VERTICAL);
        quick.setBackground(round(0xFF3A3A3C, 14));
        quick.setPadding(dp(12), dp(8), dp(12), dp(8));
        TextView qt = text(c, 11, SECONDARY_TEXT);
        qt.setText("✦ " + texts.demoQuickTitle());
        quick.addView(qt);
        String[] replies = texts.demoQuickReplies();
        for (int i = 0; i < replies.length; i++) {
            TextView r = text(c, 15, 0xFFFFFFFF);
            r.setText(replies[i]);
            r.setPadding(0, dp(6), 0, dp(2));
            if (i == 0) r.setTypeface(AndroidUtilities.bold());
            quick.addView(r);
        }
        p.addView(quick, LayoutHelper.createLinear(220, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT, 0, 8, 0, 8));

        LinearLayout out = new LinearLayout(c);
        out.setOrientation(LinearLayout.VERTICAL);
        out.setBackground(round(0xFF2F6EA5, 16));
        out.setPadding(dp(12), dp(8), dp(12), dp(8));
        TextView o = text(c, 15, 0xFFFFFFFF);
        o.setText(texts.demoTranslated());
        out.addView(o);
        TextView label = text(c, 11, 0xFFB8D8FF);
        label.setText("✦ " + texts.demoTranslatedLabel());
        out.addView(label);
        p.addView(out, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT));
        return p;
    }

    private View agentsCard(Context c) {
        LinearLayout p = panel(c, CARD);
        TextView title = text(c, 15, 0xFFFFFFFF);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        title.setText(texts.demoAgentsTitle());
        p.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));
        String[][] agents = texts.demoAgents();
        int[] colors = {0xFF5E5CE6, 0xFF30B0C7, 0xFFFF9F0A};
        for (int i = 0; i < agents.length; i++) {
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(round(i == 0 ? 0xFF3A3A3C : CARD_LIGHT, 16));
            row.setPadding(dp(10), dp(10), dp(12), dp(10));
            TextView icon = text(c, 18, 0xFFFFFFFF);
            icon.setGravity(Gravity.CENTER);
            icon.setText(agents[i][0].substring(0, 1));
            icon.setBackground(round(colors[i % colors.length], 20));
            row.addView(icon, LayoutHelper.createLinear(40, 40, 0, 0, 10, 0));
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView n = text(c, 15, 0xFFFFFFFF);
            n.setTypeface(AndroidUtilities.bold());
            n.setText(agents[i][0]);
            col.addView(n);
            TextView d = text(c, 13, SECONDARY_TEXT);
            d.setText(agents[i][1]);
            d.setMaxLines(2);
            d.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(d);
            row.addView(col, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            p.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, i == 0 ? 0 : 8, 0, 0));
        }
        return p;
    }

    private View summaryCard(Context c) {
        LinearLayout p = panel(c, CARD);
        TextView title = text(c, 15, 0xFFFFFFFF);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        title.setText(texts.demoSummaryTitle());
        p.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));
        TextView head = text(c, 15, 0xFFFFFFFF);
        head.setTypeface(AndroidUtilities.bold());
        head.setText(texts.demoSummaryHead());
        p.addView(head, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));
        String[] points = texts.demoSummaryPoints();
        for (int i = 0; i < points.length; i++) {
            TextView t = text(c, 15, i < 2 ? 0xFFFFFFFF : (i == 2 ? 0xFFAEAEB2 : 0xFF636366));
            t.setText("•  " + points[i]);
            t.setPadding(dp(4), dp(5), 0, dp(5));
            p.addView(t);
        }
        return p;
    }

    private View discoverCard(Context c) {
        LinearLayout p = panel(c, CARD);
        TextView nearbyTitle = text(c, 13, SECONDARY_TEXT);
        nearbyTitle.setText(texts.demoNearbyTitle());
        p.addView(nearbyTitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));
        int[] colors = {0xFF34C759, 0xFFFF375F};
        String[][] people = texts.demoNearby();
        for (int i = 0; i < people.length; i++) {
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView avatar = text(c, 16, 0xFFFFFFFF);
            avatar.setGravity(Gravity.CENTER);
            avatar.setText(people[i][0].substring(0, 1));
            avatar.setBackground(round(colors[i % colors.length], 18));
            row.addView(avatar, LayoutHelper.createLinear(36, 36, 0, 0, 10, 0));
            TextView n = text(c, 15, 0xFFFFFFFF);
            n.setText(people[i][0]);
            row.addView(n, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            TextView d = text(c, 13, SECONDARY_TEXT);
            d.setText(people[i][1]);
            row.addView(d);
            p.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 4));
        }
        TextView squareTitle = text(c, 13, SECONDARY_TEXT);
        squareTitle.setText(texts.demoSquareTitle());
        p.addView(squareTitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 6));
        LinearLayout post = new LinearLayout(c);
        post.setOrientation(LinearLayout.VERTICAL);
        post.setBackground(round(CARD_LIGHT, 16));
        post.setPadding(dp(12), dp(10), dp(12), dp(10));
        View photo = new View(c);
        GradientDrawable sky = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF3A2C6E, 0xFFE8735A, 0xFFF7B267});
        sky.setCornerRadius(dp(12));
        photo.setBackground(sky);
        post.addView(photo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 72));
        TextView caption = text(c, 14, 0xFFFFFFFF);
        caption.setText(texts.demoSquarePost() + "   ♥ 24");
        post.addView(caption, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
        p.addView(post);
        return p;
    }

    private View proCard(Context c) {
        LinearLayout p = new LinearLayout(c);
        p.setOrientation(LinearLayout.VERTICAL);
        String[][] features = texts.proFeatures();
        String[] icons = {"✦", "⇄", "⭐"};
        int[] colors = {0xFF5E5CE6, 0xFF0A84FF, 0xFFFF9F0A};
        for (int i = 0; i < features.length; i++) {
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setBackground(round(CARD, 16));
            row.setPadding(dp(12), dp(12), dp(12), dp(12));
            TextView icon = text(c, 16, 0xFFFFFFFF);
            icon.setGravity(Gravity.CENTER);
            icon.setText(icons[i % icons.length]);
            icon.setBackground(round(colors[i % colors.length], 10));
            row.addView(icon, LayoutHelper.createLinear(34, 34, 0, 0, 12, 0));
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView n = text(c, 15, 0xFFFFFFFF);
            n.setTypeface(AndroidUtilities.bold());
            n.setText(features[i][0]);
            col.addView(n);
            TextView d = text(c, 13, SECONDARY_TEXT);
            d.setText(features[i][1]);
            col.addView(d);
            row.addView(col, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            p.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, i == 0 ? 0 : 8, 0, 0));
        }
        TextView note = text(c, 12, SECONDARY_TEXT);
        note.setText(texts.proFootnote());
        note.setGravity(Gravity.CENTER);
        p.addView(note, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));
        return p;
    }

    @Override
    public boolean isLightStatusBar() {
        return false;
    }

    @Override
    public int getNavigationBarColor() {
        return 0xFF000000;
    }

    /** Rounded track with a white fill that slides to the current step. */
    private static final class ProgressBar extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float fraction;

        ProgressBar(Context context) {
            super(context);
            track.setColor(0xFF3A3A3C);
            fill.setColor(0xFFFFFFFF);
        }

        void setFraction(float value, boolean animated) {
            if (!animated) {
                fraction = value;
                invalidate();
                return;
            }
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(fraction, value);
            a.setDuration(300);
            a.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            a.addUpdateListener(an -> {
                fraction = (float) an.getAnimatedValue();
                invalidate();
            });
            a.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float r = getHeight() / 2f;
            rect.set(0, 0, getWidth(), getHeight());
            canvas.drawRoundRect(rect, r, r, track);
            rect.set(0, 0, Math.max(getHeight(), getWidth() * fraction), getHeight());
            canvas.drawRoundRect(rect, r, r, fill);
        }
    }
}
