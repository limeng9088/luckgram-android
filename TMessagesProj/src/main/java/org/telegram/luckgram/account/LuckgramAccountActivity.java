package org.telegram.luckgram.account;

import android.view.View;

import org.json.JSONObject;
import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramAIError;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/**
 * Luckgram Features → Account (task S6, luckgram-specs#31): link status, "Link Telegram account",
 * "Unlink".
 *
 * <p>Linking: {@code POST /v1/link/start} → open {@code t.me/<bot>?start=<nonce>} (handled inside
 * this app: the bot chat opens, the user taps Start) → poll {@code GET /v1/link?nonce=} every 2 s
 * while this page exists, until the request is no longer pending or the nonce expires.
 */
public class LuckgramAccountActivity extends UniversalFragment {

    private static final int ID_STATUS = 1;
    private static final int ID_LINK = 2;
    private static final int ID_UNLINK = 3;

    private final AccountLogic.Texts tx = new AccountLogic.Texts(LuckgramStrings.isChinese());

    private AccountLogic.LinkStatus status;
    private boolean loading = true;
    private boolean busy;
    private String pendingNonce;
    private long pendingExpiresMs;
    private LuckgramAI.Request inFlight;
    private final Runnable poll = this::pollOnce;

    @Override
    protected CharSequence getTitle() {
        return tx.accountTitle();
    }

    @Override
    public boolean onFragmentCreate() {
        load();
        return super.onFragmentCreate();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (pendingNonce == null && !busy) {
            load();
        }
    }

    @Override
    public void onFragmentDestroy() {
        AndroidUtilities.cancelRunOnUIThread(poll);
        if (inFlight != null) inFlight.cancel();
        super.onFragmentDestroy();
    }

    private void update() {
        if (listView != null) listView.adapter.update(true);
    }

    private void load() {
        if (inFlight != null) inFlight.cancel();
        inFlight = LuckgramAI.getInstance().requestJson("GET", "/v1/link", null, new LuckgramAI.Callback<JSONObject>() {
            @Override
            public void onResult(JSONObject result) {
                status = AccountLogic.parseLink(result);
                loading = false;
                update();
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
        items.add(UItem.asHeader(tx.telegramAccount()));
        if (loading && status == null) {
            items.add(UItem.asButtonLoadingValue(ID_STATUS, tx.telegramAccount()));
        } else {
            boolean linked = status != null && status.linked;
            items.add(UItem.asButton(ID_STATUS, tx.telegramAccount(), linked ? tx.linked() : tx.notLinked()).setEnabled(false));
            if (pendingNonce != null) {
                items.add(UItem.asButtonLoadingValue(ID_LINK, tx.waiting()).setEnabled(false));
            } else if (linked) {
                items.add(UItem.asButton(ID_UNLINK, tx.unlink()).red());
            } else {
                items.add(UItem.asButton(ID_LINK, tx.link()).accent());
            }
        }
        String info = tx.linkInfo();
        if (status != null && status.linked && status.maxDevices > 0) {
            info = tx.devices(status.devices, status.maxDevices) + "\n\n" + info;
        }
        items.add(UItem.asShadow(info));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (busy) return;
        if (item.id == ID_LINK && pendingNonce == null) {
            startLink();
        } else if (item.id == ID_UNLINK) {
            showDialog(new AlertDialog.Builder(getParentActivity())
                .setTitle(tx.unlinkConfirmTitle())
                .setMessage(tx.unlinkConfirmText())
                .setPositiveButton(tx.unlink(), (d, w) -> unlink())
                .setNegativeButton(LuckgramStrings.cancel(), null)
                .create());
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private void startLink() {
        busy = true;
        inFlight = LuckgramAI.getInstance().requestJson("POST", "/v1/link/start", null, new LuckgramAI.Callback<JSONObject>() {
            @Override
            public void onResult(JSONObject result) {
                busy = false;
                AccountLogic.LinkStart st = AccountLogic.parseStart(result);
                if (!AccountLogic.isTelegramLink(st.url) || st.nonce.isEmpty()) {
                    showError(new LuckgramAIError(LuckgramAIError.UPSTREAM_UNAVAILABLE, 0));
                    return;
                }
                pendingNonce = st.nonce;
                pendingExpiresMs = st.expiresAtMs > 0 ? st.expiresAtMs : System.currentTimeMillis() + 10 * 60_000L;
                update();
                Browser.openUrl(getParentActivity(), st.url);
                AndroidUtilities.runOnUIThread(poll, AccountLogic.POLL_INTERVAL_MS);
            }

            @Override
            public void onError(LuckgramAIError error) {
                busy = false;
                showError(error);
                if ("already_linked".equals(error.code)) load();
            }
        });
    }

    private void pollOnce() {
        String nonce = pendingNonce;
        if (nonce == null) return;
        if (!AccountLogic.shouldPoll(null, System.currentTimeMillis(), pendingExpiresMs)) {
            finishLink("expired");
            return;
        }
        inFlight = LuckgramAI.getInstance().requestJson("GET", "/v1/link?nonce=" + nonce, null, new LuckgramAI.Callback<JSONObject>() {
            @Override
            public void onResult(JSONObject result) {
                AccountLogic.LinkStatus s = AccountLogic.parseLink(result);
                status = s;
                if (AccountLogic.shouldPoll(s.request, System.currentTimeMillis(), pendingExpiresMs)) {
                    AndroidUtilities.runOnUIThread(poll, AccountLogic.POLL_INTERVAL_MS);
                } else {
                    finishLink(s.request);
                }
            }

            @Override
            public void onError(LuckgramAIError error) {
                // Offline for a moment: keep trying until the nonce expires.
                AndroidUtilities.runOnUIThread(poll, AccountLogic.POLL_INTERVAL_MS);
            }
        });
    }

    private void finishLink(String request) {
        pendingNonce = null;
        AndroidUtilities.cancelRunOnUIThread(poll);
        if ("linked".equals(request)) {
            BulletinFactory.of(this).createSimpleBulletin(org.telegram.messenger.R.raw.contact_check, tx.linkedNow()).show();
        } else {
            showText(tx.linkResult(request, status != null ? status.maxDevices : 0));
        }
        load();
    }

    private void unlink() {
        busy = true;
        inFlight = LuckgramAI.getInstance().requestJson("DELETE", "/v1/link", null, new LuckgramAI.Callback<JSONObject>() {
            @Override
            public void onResult(JSONObject result) {
                busy = false;
                BulletinFactory.of(LuckgramAccountActivity.this).createSimpleBulletin(org.telegram.messenger.R.raw.done, tx.unlinkedNow()).show();
                load();
            }

            @Override
            public void onError(LuckgramAIError error) {
                busy = false;
                showError(error);
            }
        });
    }

    private void showError(LuckgramAIError error) {
        if (error == null || LuckgramAIError.CANCELLED.equals(error.code)) return;
        showText(tx.error(error.code));
    }

    private void showText(String text) {
        if (getParentActivity() == null) return;
        BulletinFactory.of(this).createErrorBulletin(text).show();
    }
}
