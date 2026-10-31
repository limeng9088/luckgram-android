package org.telegram.luckgram.account;

import android.view.View;

import org.json.JSONObject;
import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramAIError;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.TimeZone;

/**
 * Luckgram Features → Luckgram Pro (task S7, luckgram-specs#32): status, end date, plans.
 *
 * <p>Buying: {@code POST /v1/billing/checkout} → open the Stars invoice link (the payment form opens
 * inside this app) → the bot credits the payment; this page re-reads {@code GET /v1/billing/plans}
 * when it comes back and a few more times until the end date changes.
 */
public class LuckgramProActivity extends UniversalFragment {

    private static final int ID_STATUS = 1;
    private static final int ID_LINK = 2;
    private static final int ID_CHECKING = 3;
    private static final int ID_PLAN_BASE = 100;

    private final AccountLogic.Texts tx = new AccountLogic.Texts(LuckgramStrings.isChinese());

    private AccountLogic.Billing billing;
    private boolean loading = true;
    private boolean busy;
    private String expiresBefore;
    private int refreshesLeft;
    private LuckgramAI.Request inFlight;
    private final Runnable refresh = this::refreshAfterPurchase;

    @Override
    protected CharSequence getTitle() {
        return tx.proTitle();
    }

    @Override
    public void onResume() {
        super.onResume();
        load(null);
    }

    @Override
    public void onFragmentDestroy() {
        AndroidUtilities.cancelRunOnUIThread(refresh);
        if (inFlight != null) inFlight.cancel();
        super.onFragmentDestroy();
    }

    private void update() {
        if (listView != null) listView.adapter.update(true);
    }

    private void load(Runnable then) {
        if (inFlight != null) inFlight.cancel();
        inFlight = LuckgramAI.getInstance().requestJson("GET", "/v1/billing/plans", null, new LuckgramAI.Callback<JSONObject>() {
            @Override
            public void onResult(JSONObject result) {
                billing = AccountLogic.parseBilling(result);
                loading = false;
                update();
                if (then != null) then.run();
            }

            @Override
            public void onError(LuckgramAIError error) {
                loading = false;
                update();
                showError(error);
            }
        });
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(tx.proTitle()));
        if (billing == null) {
            items.add(loading ? UItem.asButtonLoadingValue(ID_STATUS, tx.proTitle()) : UItem.asButton(ID_STATUS, tx.proTitle(), "—").setEnabled(false));
            items.add(UItem.asShadow(null));
            return;
        }
        String state = billing.pro ? tx.proUntil(AccountLogic.date(billing.proExpiresAt, TimeZone.getDefault())) : tx.free();
        items.add(UItem.asButton(ID_STATUS, tx.proTitle(), state).setEnabled(false));
        if (!billing.enabled) {
            items.add(UItem.asShadow(tx.notOnSale()));
            return;
        }
        if (!billing.linked) {
            items.add(UItem.asButton(ID_LINK, tx.goLink()).accent());
            items.add(UItem.asShadow(tx.linkFirst()));
            return;
        }
        if (refreshesLeft > 0) {
            items.add(UItem.asButtonLoadingValue(ID_CHECKING, tx.checking()).setEnabled(false));
        }
        for (int i = 0; i < billing.plans.size(); i++) {
            AccountLogic.Plan p = billing.plans.get(i);
            items.add(UItem.asButton(ID_PLAN_BASE + i, tx.plan(p), tx.stars(p.stars)));
        }
        items.add(UItem.asShadow(tx.proInfo()));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (busy || billing == null) return;
        if (item.id == ID_LINK) {
            presentFragment(new LuckgramAccountActivity());
        } else if (item.id >= ID_PLAN_BASE && item.id - ID_PLAN_BASE < billing.plans.size()) {
            checkout(billing.plans.get(item.id - ID_PLAN_BASE));
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private void checkout(AccountLogic.Plan plan) {
        busy = true;
        JSONObject body = new JSONObject();
        try {
            body.put("plan", plan.id);
        } catch (Exception ignore) {
        }
        inFlight = LuckgramAI.getInstance().requestJson("POST", "/v1/billing/checkout", body, new LuckgramAI.Callback<JSONObject>() {
            @Override
            public void onResult(JSONObject result) {
                busy = false;
                String url = result.optString("url", "");
                if (!AccountLogic.isTelegramLink(url)) {
                    showError(new LuckgramAIError(LuckgramAIError.UPSTREAM_UNAVAILABLE, 0));
                    return;
                }
                expiresBefore = billing.proExpiresAt;
                refreshesLeft = AccountLogic.PRO_REFRESH_TIMES;
                Browser.openUrl(getParentActivity(), url);
                AndroidUtilities.runOnUIThread(refresh, AccountLogic.PRO_REFRESH_INTERVAL_MS);
            }

            @Override
            public void onError(LuckgramAIError error) {
                busy = false;
                showError(error);
                if ("not_linked".equals(error.code) || "billing_disabled".equals(error.code)) load(null);
            }
        });
    }

    /** The bot credits the payment asynchronously: re-read until the end date moves. */
    private void refreshAfterPurchase() {
        if (refreshesLeft <= 0) return;
        refreshesLeft--;
        load(() -> {
            String now = billing.proExpiresAt;
            if (now != null && !now.equals(expiresBefore)) {
                refreshesLeft = 0;
                update();
                BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, tx.proActive()).show();
            } else if (refreshesLeft > 0) {
                AndroidUtilities.runOnUIThread(refresh, AccountLogic.PRO_REFRESH_INTERVAL_MS);
            } else {
                update();
            }
        });
    }

    private void showError(LuckgramAIError error) {
        if (error == null || LuckgramAIError.CANCELLED.equals(error.code) || getParentActivity() == null) return;
        BulletinFactory.of(this).createErrorBulletin(tx.error(error.code)).show();
    }
}
