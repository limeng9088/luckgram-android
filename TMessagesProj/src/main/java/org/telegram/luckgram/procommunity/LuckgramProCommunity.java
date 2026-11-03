package org.telegram.luckgram.procommunity;

import android.os.Bundle;

import org.json.JSONObject;
import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramAIError;
import org.telegram.luckgram.LuckgramSettings;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.luckgram.pro.LuckgramPro;
import org.telegram.luckgram.pro.ProState;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;

/**
 * specs#89 (31): 「加入 Pro 社群」. Pro users get a one-time invite link from S14 and it opens inside
 * the app (Telegram's own join sheet); members are told so and taken to the group; free users and
 * {@code not_pro} get the upgrade prompt.
 */
public final class LuckgramProCommunity {

    private static final String KEY_CHANNEL = "_pro_community_channel";

    private LuckgramProCommunity() {
    }

    public static final ProCommunityLogic.Texts TEXTS = new ProCommunityLogic.Texts(LuckgramStrings.isChinese());

    private static LuckgramAI.Request inFlight;

    /** Whether the 「加入 Pro 社群」 row is shown at all. */
    public static boolean showRow() {
        return ProCommunityLogic.showRow(LuckgramSettings.isEnabled(ProCommunityLogic.FEATURE_KEY),
            LuckgramPro.getInstance().hasPerk(ProState.PERK_COMMUNITY));
    }

    /** The row was tapped. {@code upgrade} shows the upgrade prompt (29) for free users. */
    public static void join(BaseFragment fragment, Runnable upgrade) {
        if (fragment == null || inFlight != null) return;
        if (!LuckgramPro.isPro()) {
            if (upgrade != null) upgrade.run();
            return;
        }
        inFlight = LuckgramAI.getInstance().requestJson("POST", ProCommunityLogic.INVITE_PATH, new JSONObject(), new LuckgramAI.Callback<JSONObject>() {
            @Override
            public void onResult(JSONObject result) {
                inFlight = null;
                ProCommunityLogic.Invite invite = ProCommunityLogic.parse(result);
                if (invite.kind == ProCommunityLogic.Invite.JOIN) {
                    if (fragment.getParentActivity() != null) Browser.openUrl(fragment.getParentActivity(), invite.link);
                } else if (invite.kind == ProCommunityLogic.Invite.ALREADY_MEMBER) {
                    openGroup(fragment, invite);
                } else {
                    showError(fragment, new LuckgramAIError(LuckgramAIError.UPSTREAM_UNAVAILABLE, 0));
                }
            }

            @Override
            public void onError(LuckgramAIError error) {
                inFlight = null;
                if (error != null && ProCommunityLogic.CODE_NOT_PRO.equals(error.code)) {
                    LuckgramPro.getInstance().refresh();
                    if (upgrade != null) upgrade.run();
                } else if (error != null && ProCommunityLogic.CODE_ALREADY_MEMBER.equals(error.code)) {
                    openGroup(fragment, ProCommunityLogic.alreadyMember(null));
                } else {
                    showError(fragment, error);
                }
            }
        });
    }

    /** 「你已经在 Pro 社群里」, then the group itself when we know which it is. */
    private static void openGroup(BaseFragment fragment, ProCommunityLogic.Invite invite) {
        if (fragment.getParentActivity() == null) return;
        long channelId = invite.channelId;
        if (channelId > 0) {
            LuckgramSettings.setString(KEY_CHANNEL, Long.toString(channelId));
        } else {
            try {
                channelId = Long.parseLong(LuckgramSettings.getString(KEY_CHANNEL, "0"));
            } catch (Exception ignore) {
                channelId = 0;
            }
        }
        BulletinFactory.global().createSimpleBulletin(R.raw.contact_check, TEXTS.alreadyMember()).show();
        TLRPC.Chat chat = channelId > 0 ? MessagesController.getInstance(fragment.getCurrentAccount()).getChat(channelId) : null;
        if (chat != null) {
            Bundle args = new Bundle();
            args.putLong("chat_id", channelId);
            fragment.presentFragment(new ChatActivity(args));
        } else if (invite.link != null) {
            Browser.openUrl(fragment.getParentActivity(), invite.link);
        }
    }

    private static void showError(BaseFragment fragment, LuckgramAIError error) {
        if (error == null || LuckgramAIError.CANCELLED.equals(error.code) || fragment.getParentActivity() == null) return;
        try {
            BulletinFactory.of(fragment).createErrorBulletin(error.getLocalizedMessage()).show();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}
