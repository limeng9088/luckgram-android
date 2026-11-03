package org.telegram.luckgram.pro;

import org.telegram.luckgram.LuckgramAIError;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.luckgram.ai.LuckgramAiConversation;
import org.telegram.luckgram.ai.LuckgramAiRegistry;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

import java.util.TimeZone;

/**
 * specs#87 (29) 需求 3: "Premium model · 1 credit per message" in the AI assistant (18) and agent
 * chats (22/30), added through the assistant's quick button extension point. The choice is a
 * conversation property ({@link LuckgramAiConversation#modelTier}); free users get the upgrade sheet,
 * and {@code credits_exhausted} switches back to the standard model with a message.
 */
public final class ProModelChoice {

    private ProModelChoice() {
    }

    public static void register() {
        LuckgramAiRegistry.registerQuickAction(new LuckgramAiRegistry.QuickAction("model", "模型", "Model", R.drawable.msg_premium_liststar,
            ProModelChoice::show));
    }

    private static void show(LuckgramAiRegistry.ChatHost host) {
        BaseFragment fragment = host.getFragment();
        LuckgramAiConversation conv = host.getConversation();
        if (fragment == null || fragment.getParentActivity() == null || conv == null) return;
        ProPerks.Texts t = new ProPerks.Texts(LuckgramStrings.isChinese());
        boolean premium = ProPerks.MODEL_TIER_PREMIUM.equals(conv.modelTier);
        String premiumLabel = t.modelPremium();
        ProState s = LuckgramPro.getInstance().state();
        long now = System.currentTimeMillis();
        if (s.isPro(now)) {
            int total = s.creditsTotal > 0 ? s.creditsTotal : ProUi.numbers().monthlyCredits;
            premiumLabel += "\n" + t.creditsLine(s.creditsRemaining, total, s.creditsResetsAtMs, TimeZone.getDefault());
        }
        CharSequence[] items = {(premium ? "" : "✓ ") + t.modelStandard(), (premium ? "✓ " : "") + premiumLabel};
        AlertDialog.Builder b = new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider());
        b.setTitle(t.modelTitle());
        b.setItems(items, (d, which) -> {
            if (which == 0) {
                conv.modelTier = null;
                return;
            }
            if (!LuckgramPro.isPro()) {
                conv.modelTier = null;
                LuckgramUpgradeSheet.show(fragment, ProPerks.Reason.PREMIUM_MODEL);
            } else if (LuckgramPro.getInstance().credits() <= 0) {
                conv.modelTier = null;
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, t.creditsExhausted()).show();
                LuckgramPro.getInstance().refresh(); // maybe a new period started
            } else {
                conv.modelTier = ProPerks.MODEL_TIER_PREMIUM;
            }
        });
        fragment.showDialog(b.create());
    }

    /** A premium reply cost a credit: re-read the count. */
    public static void onPremiumReply() {
        LuckgramPro.getInstance().refresh();
    }

    /** {@code not_pro} / {@code quota_exceeded} → upgrade sheet; {@code credits_exhausted} → back to standard. */
    public static void onError(BaseFragment fragment, LuckgramAiConversation conv, LuckgramAIError error) {
        if (error == null) return;
        if (LuckgramAIError.CREDITS_EXHAUSTED.equals(error.code)) {
            if (conv != null) conv.modelTier = null;
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info,
                new ProPerks.Texts(LuckgramStrings.isChinese()).creditsExhausted()).show();
            LuckgramPro.getInstance().refresh();
            return;
        }
        if (LuckgramAIError.NOT_PRO.equals(error.code)) {
            boolean premium = conv != null && conv.modelTier != null;
            if (conv != null) conv.modelTier = null;
            LuckgramPro.getInstance().refresh();
            // the premium model, or something else Pro-only (e.g. a Pro market agent, 30)
            LuckgramUpgradeSheet.show(fragment, premium ? ProPerks.Reason.PREMIUM_MODEL : ProPerks.Reason.PRO_ONLY);
            return;
        }
        LuckgramUpgradeSheet.handle(fragment, error);
    }
}
