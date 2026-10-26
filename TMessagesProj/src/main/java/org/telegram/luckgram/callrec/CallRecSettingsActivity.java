/*
 * Luckgram: "Call Recording" settings page (secondary page of the call_recording switch in
 * Settings → Luckgram features → Media, calls & storage).
 */
package org.telegram.luckgram.callrec;

import android.content.Context;
import android.os.Bundle;
import android.view.View;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import org.telegram.ui.DialogsActivity;

import java.util.ArrayList;
import java.util.function.Consumer;

public class CallRecSettingsActivity extends UniversalFragment {

    private static final int ID_ENABLE = 1;
    private static final int ID_AUTO = 2;
    private static final int ID_AI = 3;
    private static final int ID_TARGET = 4;
    private static final int ID_TARGET_RESET = 5;

    /**
     * Turns the feature on after the legal reminder (spec 04 §1). Cancelling leaves it off.
     * Also the entry point for the generic switch on the Luckgram features page.
     */
    public static void requestEnable(Context context, Theme.ResourcesProvider resourcesProvider, Consumer<Boolean> onResult) {
        if (CallRecSettings.isEnabled()) {
            onResult.accept(true);
            return;
        }
        final boolean[] accepted = {false};
        AlertDialog dialog = new AlertDialog.Builder(context, resourcesProvider)
                .setTitle(CallRecStrings.legalTitle())
                .setMessage(CallRecStrings.legalText())
                .setPositiveButton(CallRecStrings.gotIt(), (d, w) -> {
                    accepted[0] = true;
                    CallRecSettings.setEnabled(true);
                    onResult.accept(true);
                    maybeAskAiConsent(context);
                })
                .setNegativeButton(CallRecStrings.cancel(), null)
                .create();
        dialog.setOnDismissListener(d -> {
            if (!accepted[0]) {
                onResult.accept(false);
            }
        });
        dialog.show();
    }

    /**
     * The transcript is made after the call, when no dialog can be shown, so the shared AI
     * consent (spec 00 §18) is asked for up front. Declining turns the AI transcript off.
     */
    private static void maybeAskAiConsent(Context context) {
        final CallRecAi ai = CallRecAi.get();
        if (ai == null || !CallRecSettings.isAiTranscriptEnabled() || ai.hasConsent()) {
            return;
        }
        ai.requestConsent(context, agreed -> {
            if (!agreed) {
                CallRecSettings.setAiTranscriptEnabled(false);
            }
        });
    }

    @Override
    protected CharSequence getTitle() {
        return CallRecStrings.featureName();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(false);
        }
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        final boolean enabled = CallRecSettings.isEnabled();
        items.add(UItem.asRippleCheck(ID_ENABLE, CallRecStrings.enable()).setChecked(enabled));
        items.add(UItem.asShadow(CallRecStrings.enableInfo() + "\n\n" + CallRecStrings.legalText()));
        if (!enabled) {
            return;
        }
        items.add(UItem.asCheck(ID_AUTO, CallRecStrings.autoRecord()).setChecked(CallRecSettings.isAutoRecord()));
        items.add(UItem.asShadow(CallRecStrings.autoRecordInfo()));

        items.add(UItem.asCheck(ID_AI, CallRecStrings.aiTranscript()).setChecked(CallRecSettings.isAiTranscriptEnabled()));
        items.add(UItem.asShadow(CallRecStrings.aiTranscriptInfo()));

        items.add(UItem.asHeader(CallRecStrings.saveTo()));
        final long target = CallRecSettings.getTargetDialogId(currentAccount);
        items.add(UItem.asButton(ID_TARGET, CallRecStrings.chooseChat(), targetName(target)));
        if (target != 0) {
            items.add(UItem.asButton(ID_TARGET_RESET, CallRecStrings.resetToSaved()).accent());
        }
        items.add(UItem.asShadow(CallRecStrings.saveToInfo()));
    }

    private String targetName(long dialogId) {
        if (dialogId == 0 || dialogId == UserConfig.getInstance(currentAccount).getClientUserId()) {
            return CallRecStrings.savedMessages();
        }
        if (DialogObject.isUserDialog(dialogId)) {
            TLRPC.User user = getMessagesController().getUser(dialogId);
            return user != null ? UserObject.getUserName(user) : CallRecStrings.savedMessages();
        }
        TLRPC.Chat chat = getMessagesController().getChat(-dialogId);
        return chat != null ? chat.title : CallRecStrings.savedMessages();
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_ENABLE) {
            if (CallRecSettings.isEnabled()) {
                CallRecSettings.setEnabled(false);
                updateEnableCell(view, false);
                listView.adapter.update(true);
            } else {
                requestEnable(getContext(), getResourceProvider(), on -> {
                    updateEnableCell(view, on);
                    if (listView != null) {
                        listView.adapter.update(true);
                    }
                });
            }
        } else if (item.id == ID_AUTO) {
            boolean value = !CallRecSettings.isAutoRecord();
            CallRecSettings.setAutoRecord(value);
            ((TextCheckCell) view).setChecked(value);
        } else if (item.id == ID_AI) {
            boolean value = !CallRecSettings.isAiTranscriptEnabled();
            CallRecSettings.setAiTranscriptEnabled(value);
            ((TextCheckCell) view).setChecked(value);
            if (value) {
                final CallRecAi ai = CallRecAi.get();
                if (ai != null && !ai.hasConsent()) {
                    ai.requestConsent(getContext(), agreed -> {
                        if (!agreed) {
                            CallRecSettings.setAiTranscriptEnabled(false);
                            if (listView != null) {
                                listView.adapter.update(true);
                            }
                        }
                    });
                }
            }
        } else if (item.id == ID_TARGET) {
            Bundle args = new Bundle();
            args.putBoolean("onlySelect", true);
            args.putBoolean("checkCanWrite", true);
            args.putBoolean("allowGlobalSearch", false);
            DialogsActivity fragment = new DialogsActivity(args);
            fragment.setDelegate((dialogsFragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
                if (!dids.isEmpty()) {
                    CallRecSettings.setTargetDialogId(currentAccount, dids.get(0).dialogId);
                }
                dialogsFragment.finishFragment();
                if (listView != null) {
                    listView.adapter.update(true);
                }
                return true;
            });
            presentFragment(fragment);
        } else if (item.id == ID_TARGET_RESET) {
            CallRecSettings.setTargetDialogId(currentAccount, 0);
            listView.adapter.update(true);
        }
    }

    private void updateEnableCell(View view, boolean on) {
        if (view instanceof TextCheckCell) {
            ((TextCheckCell) view).setChecked(on);
            ((TextCheckCell) view).setBackgroundColorAnimated(on, Theme.getColor(on ? Theme.key_windowBackgroundChecked : Theme.key_windowBackgroundUnchecked));
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
