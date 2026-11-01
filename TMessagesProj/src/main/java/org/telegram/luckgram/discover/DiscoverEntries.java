package org.telegram.luckgram.discover;

import org.telegram.luckgram.LuckgramStrings;
import org.telegram.luckgram.nearby.NearbyActivity;
import org.telegram.luckgram.nearby.NearbyController;
import org.telegram.luckgram.nearby.NearbyLogic;
import org.telegram.luckgram.square.SquareActivity;
import org.telegram.luckgram.square.SquareUi;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Cells.TextCell;

import java.util.ArrayList;
import java.util.List;

/**
 * Luckgram rows at the top of the Contacts tab ("People Nearby" 25, "Square" 26), each shown only
 * while its switch is on. This Telegram version has no side menu, so Contacts is the entry on
 * both platforms (same as iOS).
 */
public final class DiscoverEntries {

    private DiscoverEntries() {
    }

    public static final int NEARBY = 1;
    public static final int SQUARE = 2;

    public static List<Integer> visible() {
        ArrayList<Integer> out = new ArrayList<>();
        if (NearbyController.isFeatureEnabled()) out.add(NEARBY);
        if (SquareUi.isFeatureEnabled()) out.add(SQUARE);
        return out;
    }

    public static void bind(TextCell cell, int entry, boolean divider) {
        if (entry == NEARBY) {
            cell.setTextAndValueAndColorfulIcon(new NearbyLogic.Texts(LuckgramStrings.isChinese()).title(), "", false,
                R.drawable.msg_location, 0xFFF5A623, 0xFFE6862E, divider);
        } else if (entry == SQUARE) {
            cell.setTextAndValueAndColorfulIcon(SquareUi.tx().title(), "", false,
                R.drawable.msg_discussion, 0xFF5AC8FA, 0xFF2F8BE6, divider);
        }
    }

    public static void open(BaseFragment from, int entry) {
        if (entry == NEARBY && NearbyController.isFeatureEnabled()) {
            from.presentFragment(new NearbyActivity());
        } else if (entry == SQUARE && SquareUi.isFeatureEnabled()) {
            from.presentFragment(new SquareActivity());
        }
    }
}
