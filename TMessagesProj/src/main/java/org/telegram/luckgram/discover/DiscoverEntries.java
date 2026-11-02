package org.telegram.luckgram.discover;

import org.telegram.luckgram.LuckgramStrings;
import org.telegram.luckgram.nearby.NearbyActivity;
import org.telegram.luckgram.nearby.NearbyController;
import org.telegram.luckgram.nearby.NearbyLogic;
import org.telegram.luckgram.square.SquareActivity;
import org.telegram.luckgram.square.SquareUi;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.ArrayList;
import java.util.List;

/**
 * The rows of the Discover tab (spec 27): "People Nearby" (25) and "Square" (26), each shown only
 * while its switch is on. They used to sit at the top of Contacts; since 27 the Discover tab is
 * the only entry on both platforms. New social entries go here too.
 */
public final class DiscoverEntries {

    private DiscoverEntries() {
    }

    public static final int NEARBY = 1;
    public static final int SQUARE = 2;

    public static List<Integer> visible() {
        ArrayList<Integer> out = new ArrayList<>();
        if (SquareUi.isFeatureEnabled()) out.add(SQUARE);
        if (NearbyController.isFeatureEnabled()) out.add(NEARBY);
        return out;
    }

    public static String title(int entry) {
        if (entry == NEARBY) return new NearbyLogic.Texts(LuckgramStrings.isChinese()).title();
        if (entry == SQUARE) return SquareUi.tx().title();
        return "";
    }

    public static String subtitle(int entry) {
        if (entry == NEARBY) return LuckgramStrings.get("发现你周围的人和群组", "Find people and groups around you");
        if (entry == SQUARE) return LuckgramStrings.get("看看大家在分享什么", "Posts from people and channels");
        return "";
    }

    public static int icon(int entry) {
        return entry == NEARBY ? R.drawable.msg_location : R.drawable.msg_discussion;
    }

    public static int iconColorTop(int entry) {
        return entry == NEARBY ? 0xFFF5A623 : 0xFF5AC8FA;
    }

    public static int iconColorBottom(int entry) {
        return entry == NEARBY ? 0xFFE6862E : 0xFF2F8BE6;
    }

    public static void open(BaseFragment from, int entry) {
        if (entry == NEARBY && NearbyController.isFeatureEnabled()) {
            from.presentFragment(new NearbyActivity());
        } else if (entry == SQUARE && SquareUi.isFeatureEnabled()) {
            from.presentFragment(new SquareActivity());
        }
    }

    // ---------------------------------------------------------------- page texts

    public static String pageTitle() {
        return LuckgramStrings.get("发现", "Discover");
    }

    public static String emptyText() {
        return LuckgramStrings.get("暂时没有可用的功能", "No features available right now");
    }
}
