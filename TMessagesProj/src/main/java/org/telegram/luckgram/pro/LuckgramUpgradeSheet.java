package org.telegram.luckgram.pro;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.luckgram.LuckgramAIError;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.luckgram.account.LuckgramProActivity;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/**
 * specs#87 (29) 需求 6: the one "upgrade to Pro" bottom sheet, for {@code quota_exceeded} and for
 * free users tapping something Pro-only: one line saying why + "See Pro" → the subscription page.
 */
public final class LuckgramUpgradeSheet extends BottomSheet {

    /** Shows the sheet for {@code quota_exceeded} / {@code not_pro}; true if it did. */
    public static boolean handle(BaseFragment fragment, LuckgramAIError error) {
        if (error == null || fragment == null) return false;
        if (LuckgramAIError.QUOTA_EXCEEDED.equals(error.code)) {
            show(fragment, ProPerks.Reason.QUOTA);
            return true;
        }
        if (ProPerks.ERROR_NOT_PRO.equals(error.code)) {
            show(fragment, ProPerks.Reason.PRO_ONLY);
            return true;
        }
        return false;
    }

    public static void show(BaseFragment fragment, ProPerks.Reason reason) {
        if (fragment == null || fragment.getParentActivity() == null) return;
        fragment.showDialog(new LuckgramUpgradeSheet(fragment, reason));
    }

    private LuckgramUpgradeSheet(BaseFragment fragment, ProPerks.Reason reason) {
        super(fragment.getParentActivity(), false, fragment.getResourceProvider());
        fixNavigationBar();
        Context c = getContext();
        ProPerks.Texts t = new ProPerks.Texts(LuckgramStrings.isChinese());
        Theme.ResourcesProvider rp = fragment.getResourceProvider();

        LinearLayout layout = new LinearLayout(c);
        layout.setOrientation(LinearLayout.VERTICAL);

        TextView title = ProUi.text(c, 20, Theme.getColor(Theme.key_dialogTextBlack, rp));
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        title.setText(t.upgradeTitle());
        layout.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 20, 22, 8));

        TextView why = ProUi.text(c, 15, Theme.getColor(Theme.key_dialogTextGray3, rp));
        why.setGravity(Gravity.CENTER);
        why.setText(t.upgradeReason(reason));
        layout.addView(why, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 0, 22, 18));

        TextView button = ProUi.text(c, 15, Theme.getColor(Theme.key_featuredStickers_buttonText, rp));
        button.setTypeface(AndroidUtilities.bold());
        button.setGravity(Gravity.CENTER);
        button.setText(t.upgradeButton());
        button.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(8),
            Theme.getColor(Theme.key_featuredStickers_addButton, rp), Theme.getColor(Theme.key_featuredStickers_addButtonPressed, rp)));
        button.setOnClickListener(v -> {
            dismiss();
            fragment.presentFragment(new LuckgramProActivity());
        });
        layout.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 0, 16, 16));

        setCustomView(layout);
    }
}
