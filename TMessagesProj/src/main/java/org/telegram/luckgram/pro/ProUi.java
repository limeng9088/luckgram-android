package org.telegram.luckgram.pro;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.luckgram.LuckgramSettings;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.Components.LayoutHelper;

import java.util.List;

/**
 * specs#87 (29): the perk list view shared by the subscription page and the onboarding Pro step,
 * and the numbers it shows (remote config, then {@link ProState} limits).
 */
public final class ProUi {

    private ProUi() {
    }

    /** Colors of the list: the onboarding page is always dark, the Pro page follows the theme. */
    public static final class Colors {
        public final int card, title, subtitle, badgeText, badgeBackground;

        public Colors(int card, int title, int subtitle, int badgeText, int badgeBackground) {
            this.card = card;
            this.title = title;
            this.subtitle = subtitle;
            this.badgeText = badgeText;
            this.badgeBackground = badgeBackground;
        }
    }

    private static final String[] ICONS = {"✦", "👥", "⚡", "☰", "⇄", "★", "⊘"};
    private static final int[] ICON_COLORS = {0xFF5E5CE6, 0xFF0A84FF, 0xFFFF9F0A, 0xFF30B0C7, 0xFF34C759, 0xFFFF375F, 0xFF8E8E93};

    public static ProPerks.Numbers numbers() {
        ProPerks.Numbers n = new ProPerks.Numbers();
        ProState s = LuckgramPro.getInstance().state();
        long now = System.currentTimeMillis();
        n.accountsMaxPro = (int) LuckgramSettings.remoteLong(ProPerks.CFG_ACCOUNTS_MAX_PRO, ProState.ACCOUNTS_MAX_PRO);
        n.chatPro = (int) LuckgramSettings.remoteLong(ProPerks.CFG_CHAT_PRO, 0);
        n.chatFree = (int) LuckgramSettings.remoteLong(ProPerks.CFG_CHAT_FREE, 0);
        n.monthlyCredits = (int) LuckgramSettings.remoteLong(ProPerks.CFG_MONTHLY_CREDITS,
            s.creditsTotal > 0 ? s.creditsTotal : ProPerks.DEFAULT_MONTHLY_CREDITS);
        n.summaryMaxPro = (int) LuckgramSettings.remoteLong(ProPerks.CFG_SUMMARY_MAX,
            s.isPro(now) ? s.summaryMaxMessages(now) : ProState.SUMMARY_MAX_PRO);
        n.translatePro = (int) LuckgramSettings.remoteLong(ProPerks.CFG_TRANSLATE_PRO, 0);
        n.translateFree = (int) LuckgramSettings.remoteLong(ProPerks.CFG_TRANSLATE_FREE, 0);
        return n;
    }

    /** The rows that are live now (empty when neither /v1/me nor the config lists any perk). */
    public static List<ProPerks.Row> liveRows() {
        List<String> perks = ProPerks.livePerks(LuckgramPro.getInstance().state().perks,
            LuckgramSettings.remoteConfig().getString(ProPerks.CFG_PERKS, null));
        return ProPerks.rows(perks, numbers(), LuckgramStrings.isChinese());
    }

    /** Icon + title (+ "Pro 专享" badge) + one line of text per row. */
    public static LinearLayout perksView(Context c, List<ProPerks.Row> rows, Colors colors) {
        LinearLayout list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        ProPerks.Texts texts = new ProPerks.Texts(LuckgramStrings.isChinese());
        for (int i = 0; i < rows.size(); i++) {
            ProPerks.Row r = rows.get(i);
            int k = indexOf(r.perk);
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setBackground(round(colors.card, 16));
            row.setPadding(dp(12), dp(12), dp(12), dp(12));
            TextView icon = text(c, 16, 0xFFFFFFFF);
            icon.setGravity(Gravity.CENTER);
            icon.setText(ICONS[k]);
            icon.setBackground(round(ICON_COLORS[k], 10));
            row.addView(icon, LayoutHelper.createLinear(34, 34, 0, 0, 12, 0));
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            if (r.proOnly) {
                TextView badge = text(c, 11, colors.badgeText);
                badge.setTypeface(AndroidUtilities.bold());
                badge.setText(texts.proOnlyBadge());
                badge.setBackground(round(colors.badgeBackground, 6));
                badge.setPadding(dp(6), dp(1), dp(6), dp(1));
                col.addView(badge, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));
            }
            TextView title = text(c, 15, colors.title);
            title.setTypeface(AndroidUtilities.bold());
            title.setText(r.title);
            col.addView(title);
            TextView sub = text(c, 13, colors.subtitle);
            sub.setText(r.subtitle);
            col.addView(sub);
            row.addView(col, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            list.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, i == 0 ? 0 : 8, 0, 0));
        }
        return list;
    }

    private static int indexOf(String perk) {
        for (int i = 0; i < ProPerks.ORDER.length; i++) if (ProPerks.ORDER[i].equals(perk)) return i;
        return 0;
    }

    static GradientDrawable round(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    static TextView text(Context c, int sp, int color) {
        TextView v = new TextView(c);
        v.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sp);
        v.setTextColor(color);
        return v;
    }
}
