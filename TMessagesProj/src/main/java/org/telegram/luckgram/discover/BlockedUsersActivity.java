package org.telegram.luckgram.discover;

import android.view.View;

import org.json.JSONObject;
import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramAIError;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/** Blocked people (shared by People Nearby and Square): list and unblock. */
public class BlockedUsersActivity extends UniversalFragment {

    private static final int ID_LOAD_MORE = 1;
    private static final int ID_RETRY = 2;
    private static final int ID_USER = 1000;

    private final BlockLogic.Texts tx = DiscoverBlocks.tx();
    private final ArrayList<String> ids = new ArrayList<>();
    private String nextCursor;
    private boolean loading;
    private boolean loadedOnce;
    private boolean failed;
    private LuckgramAI.Request inFlight;

    @Override
    protected CharSequence getTitle() {
        return tx.title();
    }

    @Override
    public boolean onFragmentCreate() {
        load();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        if (inFlight != null) inFlight.cancel();
        super.onFragmentDestroy();
    }

    private void update() {
        if (listView != null) listView.adapter.update(true);
    }

    private void load() {
        if (inFlight != null) inFlight.cancel();
        loading = true;
        failed = false;
        update();
        inFlight = DiscoverBlocks.list(nextCursor, new LuckgramAI.Callback<DiscoverBlocks.Page>() {
            @Override
            public void onResult(DiscoverBlocks.Page page) {
                for (String id : page.ids) {
                    if (!ids.contains(id)) ids.add(id);
                }
                nextCursor = page.nextCursor;
                loading = false;
                loadedOnce = true;
                update();
            }

            @Override
            public void onError(LuckgramAIError error) {
                loading = false;
                failed = true;
                update();
                showError(error);
            }
        });
    }

    private String label(String id) {
        String n = DiscoverBlocks.nameOf(id);
        return n != null ? n : tx.unknownUser();
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        for (int i = 0; i < ids.size(); i++) {
            items.add(UItem.asButton(ID_USER + i, label(ids.get(i)), tx.unblock()));
        }
        if (loading) {
            items.add(UItem.asButtonLoadingValue(0, tx.loading()).setEnabled(false));
        } else if (failed) {
            items.add(UItem.asButton(ID_RETRY, tx.retry()).accent());
        } else if (nextCursor != null) {
            items.add(UItem.asButton(ID_LOAD_MORE, tx.loadMore()).accent());
        }
        items.add(UItem.asShadow(loadedOnce && ids.isEmpty() && !loading ? tx.empty() : tx.info()));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_LOAD_MORE && !loading) {
            load();
        } else if (item.id == ID_RETRY) {
            load();
        } else if (item.id >= ID_USER && item.id - ID_USER < ids.size()) {
            confirmUnblock(ids.get(item.id - ID_USER));
        }
    }

    private void confirmUnblock(String id) {
        if (getParentActivity() == null) return;
        new AlertDialog.Builder(getParentActivity())
            .setTitle(tx.unblock())
            .setMessage(tx.unblockConfirm(label(id)))
            .setPositiveButton(tx.unblock(), (d, w) -> DiscoverBlocks.unblock(id, new LuckgramAI.Callback<JSONObject>() {
                @Override
                public void onResult(JSONObject result) {
                    ids.remove(id);
                    update();
                    BulletinFactory.of(BlockedUsersActivity.this).createSimpleBulletin(R.raw.done, tx.unblocked()).show();
                }

                @Override
                public void onError(LuckgramAIError error) {
                    showError(error);
                }
            }))
            .setNegativeButton(tx.cancel(), null)
            .show();
    }

    private void showError(LuckgramAIError error) {
        if (error == null || LuckgramAIError.CANCELLED.equals(error.code) || getParentActivity() == null) return;
        String text = "not_linked".equals(error.code) ? tx.notLinked() : error.getLocalizedMessage();
        BulletinFactory.of(this).createErrorBulletin(text).show();
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
