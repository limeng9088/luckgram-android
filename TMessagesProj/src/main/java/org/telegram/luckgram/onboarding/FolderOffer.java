package org.telegram.luckgram.onboarding;

import org.telegram.luckgram.LuckgramSettings;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.tl.TL_chatlists;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LaunchActivity;

/**
 * specs#98: called once a new login has finished. Offers the "Luckgram picks" folder when the
 * remote config switches it on, this Telegram account has not seen the offer and has not joined
 * the folder yet. Silent on any error (bad link, network): the offer is optional.
 */
public final class FolderOffer {

    private FolderOffer() {
    }

    public static void afterLogin(int account, long userId) {
        final String link = LuckgramSettings.remoteConfig().getString(FolderOfferLogic.LINK_KEY, "");
        final boolean enabled = LuckgramSettings.remoteConfig().getBool(FolderOfferLogic.ENABLED_KEY, false);
        final String key = FolderOfferLogic.seenKey(userId);
        if (!FolderOfferLogic.shouldCheck(enabled, link, LuckgramSettings.getBool(key, false), userId)) {
            return;
        }
        TL_chatlists.TL_chatlists_checkChatlistInvite req = new TL_chatlists.TL_chatlists_checkChatlistInvite();
        req.slug = FolderOfferLogic.slug(link);
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (!(response instanceof TL_chatlists.chatlist_ChatlistInvite)) {
                return;
            }
            boolean already = response instanceof TL_chatlists.TL_chatlists_chatlistInviteAlready;
            int peers = 0;
            if (response instanceof TL_chatlists.TL_chatlists_chatlistInvite) {
                TL_chatlists.TL_chatlists_chatlistInvite inv = (TL_chatlists.TL_chatlists_chatlistInvite) response;
                MessagesController.getInstance(account).putChats(inv.chats, false);
                MessagesController.getInstance(account).putUsers(inv.users, false);
                peers = inv.peers.size();
            }
            if (!FolderOfferLogic.shouldOffer(already, peers)) {
                LuckgramSettings.setBool(key, true);
                return;
            }
            BaseFragment last = LaunchActivity.getSafeLastFragment();
            if (last != null && last.getParentActivity() != null) {
                LuckgramSettings.setBool(key, true);
                last.presentFragment(new LuckgramFolderOfferActivity(link));
            }
        }));
    }
}
