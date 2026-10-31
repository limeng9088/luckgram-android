package org.telegram.luckgram;

import android.content.Context;
import android.view.View;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.NotificationsCheckCell;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

/** A feature row: name, one-line description (wraps), switch; a divider before the switch when it has a subpage. */
public final class LuckgramFeatureCell {

    private LuckgramFeatureCell() {
    }

    public static final class Factory extends UItem.UItemFactory<NotificationsCheckCell> {
        static {
            setup(new Factory());
        }

        @Override
        public NotificationsCheckCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return new NotificationsCheckCell(context, 21, 64, false, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            NotificationsCheckCell cell = (NotificationsCheckCell) view;
            LuckgramFeature f = (LuckgramFeature) item.object;
            cell.setDrawLine(f.subpage != null);
            cell.setTextAndValueAndCheck(f.title(), f.description(), f.isEnabled(), 0, true, divider);
        }

        @Override
        public boolean equals(UItem a, UItem b) {
            return a.object == b.object;
        }

        @Override
        public boolean contentsEquals(UItem a, UItem b) {
            return a.object == b.object && a.checked == b.checked;
        }

        public static UItem of(int id, LuckgramFeature feature) {
            UItem item = UItem.ofFactory(Factory.class);
            item.id = id;
            item.object = feature;
            item.checked = feature.isEnabled();
            return item;
        }
    }
}
