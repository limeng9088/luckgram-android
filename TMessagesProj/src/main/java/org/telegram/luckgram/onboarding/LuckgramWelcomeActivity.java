package org.telegram.luckgram.onboarding;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.luckgram.LuckgramSettings;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.LoginActivity;

/**
 * specs#76: first-launch welcome page shown instead of Telegram's intro while no account is logged
 * in. Dark page with a tile pattern, the Luckgram logo, a short intro and a "Get Started" button.
 * The privacy policy dialog opens by itself until the user taps Continue; Get Started then opens
 * the usual phone-number login. Back leaves the app (root fragment).
 */
public class LuckgramWelcomeActivity extends BaseFragment {

    private final WelcomeLogic.Texts texts = new WelcomeLogic.Texts(LuckgramStrings.isChinese());
    private AlertDialog privacyDialog;
    private boolean asked;

    public static boolean isAccepted() {
        return PrivacyDialog.isAccepted();
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(0xFF000000);
        root.addView(new TileBackground(context), LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        column.addView(new View(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        // No logo in the middle (user decision 2026-11-02): only the tile pattern above the text.
        column.addView(new View(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1.2f));

        TextView welcomeTo = new TextView(context);
        welcomeTo.setText(texts.welcomeTo());
        welcomeTo.setTextColor(0xFF8E8E93);
        welcomeTo.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        welcomeTo.setGravity(Gravity.CENTER);
        column.addView(welcomeTo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 0));

        TextView name = new TextView(context);
        name.setText(texts.appName());
        name.setTextColor(0xFFFFFFFF);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 42);
        name.setGravity(Gravity.CENTER);
        column.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 2, 24, 10));

        TextView intro = new TextView(context);
        intro.setText(texts.intro());
        intro.setTextColor(0xFFFFFFFF);
        intro.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        intro.setGravity(Gravity.CENTER);
        intro.setLineSpacing(dp(2), 1f);
        column.addView(intro, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 0));

        TextView button = new TextView(context);
        button.setText(texts.getStarted());
        button.setTextColor(0xFF000000);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        button.setTypeface(AndroidUtilities.bold());
        button.setGravity(Gravity.CENTER);
        button.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(26), 0xFFFFFFFF, 0xFFDDDDDD));
        button.setOnClickListener(v -> start());
        column.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52, 24, 36, 24, 40));

        fragmentView = root;
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!asked && WelcomeLogic.shouldAskPrivacy(isAccepted())) {
            asked = true;
            AndroidUtilities.runOnUIThread(() -> showPrivacy(false), 250);
        }
    }

    private void start() {
        if (WelcomeLogic.shouldAskPrivacy(isAccepted())) {
            showPrivacy(true);
            return;
        }
        if (WelcomeLogic.shouldShowSteps(LuckgramSettings.getBool(WelcomeLogic.ONBOARDED_KEY, false))) {
            // Steps on top of this page: back from the first step returns here.
            presentFragment(new LuckgramOnboardingActivity());
        } else {
            presentFragment(new LoginActivity(), true);
        }
    }

    private void showPrivacy(boolean thenStart) {
        if (getParentActivity() == null || isFinishing() || (privacyDialog != null && privacyDialog.isShowing())) {
            return;
        }
        privacyDialog = PrivacyDialog.show(getParentActivity(), this, false, thenStart ? this::start : null);
    }

    @Override
    public boolean isLightStatusBar() {
        return false;
    }

    @Override
    public int getNavigationBarColor() {
        return 0xFF000000;
    }

    /** Rounded tiles drawn in code (no image assets), fading out towards the text and button. */
    private static final class TileBackground extends View {
        private final Paint tile = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fade = new Paint();
        private final RectF rect = new RectF();

        TileBackground(Context context) {
            super(context);
            tile.setColor(0xFF1C1C1E);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            fade.setShader(new LinearGradient(0, h * 0.36f, 0, h * 0.55f, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final int w = getWidth(), h = getHeight();
            final float size = dp(112), gap = dp(18), radius = dp(30);
            final float step = size + gap;
            // Columns centred on the page so the middle tile sits behind the logo.
            final float startX = w / 2f - size / 2f - step * (float) Math.ceil((w / 2f) / step);
            for (float y = -size * 0.55f; y < h * 0.56f; y += step) {
                for (float x = startX; x < w; x += step) {
                    rect.set(x, y, x + size, y + size);
                    canvas.drawRoundRect(rect, radius, radius, tile);
                }
            }
            canvas.drawRect(0, h * 0.36f, w, h, fade);
        }
    }
}
