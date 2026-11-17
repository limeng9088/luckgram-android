package org.telegram.luckgram.onboarding;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import org.telegram.luckgram.LuckgramStrings;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/**
 * specs#98: full-screen dark page after a new login. "Add Luckgram Picks" opens the folder link
 * through Telegram's own chat folder invite sheet, where the user picks and confirms the chats;
 * nothing is joined from here. "No thanks" closes the page.
 */
public class LuckgramFolderOfferActivity extends BaseFragment {

    private final String link;
    private final FolderOfferLogic.Texts texts = new FolderOfferLogic.Texts(LuckgramStrings.isChinese());

    public LuckgramFolderOfferActivity(String link) {
        this.link = link;
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(0xFF000000);
        root.setOnClickListener(v -> { });

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        column.addView(new View(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
        column.addView(new FolderArt(context), LayoutHelper.createLinear(240, 180, Gravity.CENTER_HORIZONTAL));

        TextView title = new TextView(context);
        title.setText(texts.title());
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 26);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        column.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 28, 32, 28, 10));

        TextView subtitle = new TextView(context);
        subtitle.setText(texts.subtitle());
        subtitle.setTextColor(0xFFAEAEB2);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setLineSpacing(dp(2), 1f);
        column.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 32, 0, 32, 0));

        column.addView(new View(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1.2f));

        TextView add = new TextView(context);
        add.setText(texts.add());
        add.setTextColor(0xFF000000);
        add.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        add.setTypeface(AndroidUtilities.bold());
        add.setGravity(Gravity.CENTER);
        add.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(26), 0xFFFFFFFF, 0xFFDDDDDD));
        add.setOnClickListener(v -> addFolder());
        column.addView(add, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52, 24, 0, 24, 8));

        TextView skip = new TextView(context);
        skip.setText(texts.skip());
        skip.setTextColor(0xFFAEAEB2);
        skip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        skip.setGravity(Gravity.CENTER);
        skip.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(22), 0x00000000, 0x22FFFFFF));
        skip.setOnClickListener(v -> finishFragment());
        column.addView(skip, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 24, 0, 24, 28));

        fragmentView = root;
        return root;
    }

    private void addFolder() {
        Context context = getParentActivity();
        finishFragment();
        if (context != null) {
            // t.me/addlist links are handled in-app: checkChatlistInvite → the native folder sheet.
            AndroidUtilities.runOnUIThread(() -> Browser.openUrl(context, link), 300);
        }
    }

    @Override
    public boolean isLightStatusBar() {
        return false;
    }

    @Override
    public int getNavigationBarColor() {
        return 0xFF000000;
    }

    /** Drawn in code: a folder tile with a few coloured channel dots around it. */
    private static final class FolderArt extends View {
        private final Paint tile = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Drawable folder;
        private static final int[] COLORS = {0xFF3E88F7, 0xFFFF9F0A, 0xFF34C759, 0xFFAF52DE, 0xFFFF375F};
        private static final float[][] DOTS = {{-0.36f, -0.30f, 20}, {0.38f, -0.26f, 16}, {-0.40f, 0.30f, 14}, {0.36f, 0.32f, 22}, {0.02f, -0.46f, 12}};

        FolderArt(Context context) {
            super(context);
            tile.setColor(0xFF1C1C1E);
            folder = ContextCompat.getDrawable(context, R.drawable.msg_folders).mutate();
            folder.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float cx = getWidth() / 2f, cy = getHeight() / 2f, half = dp(44);
            for (int i = 0; i < DOTS.length; i++) {
                dot.setColor(COLORS[i % COLORS.length]);
                canvas.drawCircle(cx + DOTS[i][0] * getWidth(), cy + DOTS[i][1] * getHeight() * 1.3f, dp(DOTS[i][2]), dot);
            }
            rect.set(cx - half, cy - half, cx + half, cy + half);
            canvas.drawRoundRect(rect, dp(26), dp(26), tile);
            final int icon = dp(44);
            folder.setBounds((int) (cx - icon / 2f), (int) (cy - icon / 2f), (int) (cx + icon / 2f), (int) (cy + icon / 2f));
            folder.draw(canvas);
        }
    }
}
