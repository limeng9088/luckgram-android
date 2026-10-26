package org.telegram.luckgram.cache;

import android.app.Activity;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.AutoDeleteMediaTask;
import org.telegram.messenger.CacheByChatsController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.RadioColorCell;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Luckgram 08 (cache): per-chat keep-media chooser, used from the storage page's chat sheet
 * and from the chat profile's "…" menu.
 */
public final class LuckgramChatKeepMedia {

    /** Options offered for a single chat, in display order. */
    public static final int[] CHAT_OPTIONS = {
            CacheByChatsController.KEEP_MEDIA_ONE_HOUR,
            CacheByChatsController.KEEP_MEDIA_ONE_DAY,
            CacheByChatsController.KEEP_MEDIA_THREE_DAY,
            CacheByChatsController.KEEP_MEDIA_ONE_WEEK,
            CacheByChatsController.KEEP_MEDIA_ONE_MONTH,
            CacheByChatsController.KEEP_MEDIA_FOREVER,
    };

    private LuckgramChatKeepMedia() {
    }

    /** Value shown for a chat: its override, or "Default (category value)". */
    public static String valueFor(CacheByChatsController controller, long dialogId) {
        CacheByChatsController.KeepMediaException exception = controller.getDialogKeepMediaException(dialogId);
        if (exception != null) {
            return CacheByChatsController.getKeepMediaString(exception.keepMedia);
        }
        return LuckgramCacheStrings.followCategory(CacheByChatsController.getKeepMediaString(categoryValue(controller, dialogId)));
    }

    private static int categoryValue(CacheByChatsController controller, long dialogId) {
        int type = controller.getDialogKeepMediaType(dialogId, false);
        return controller.getKeepMedia(type < 0 ? CacheByChatsController.KEEP_MEDIA_TYPE_GROUP : type);
    }

    /**
     * Shows the chooser. {@code sizeBytes} is the space the chat uses on this device;
     * {@code onChanged} runs after a new value has been saved.
     */
    public static void showPicker(BaseFragment fragment, long dialogId, long sizeBytes, Runnable onChanged) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        CacheByChatsController controller = fragment.getMessagesController().getCacheByChatsController();
        CacheByChatsController.KeepMediaException exception = controller.getDialogKeepMediaException(dialogId);

        String[] options = new String[CHAT_OPTIONS.length + 1];
        options[0] = LuckgramCacheStrings.useCategoryDefault(CacheByChatsController.getKeepMediaString(categoryValue(controller, dialogId)));
        int selected = 0;
        for (int i = 0; i < CHAT_OPTIONS.length; i++) {
            options[i + 1] = CacheByChatsController.getKeepMediaString(CHAT_OPTIONS[i]);
            if (exception != null && exception.keepMedia == CHAT_OPTIONS[i]) {
                selected = i + 1;
            }
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        LinearLayout linearLayout = new LinearLayout(activity);
        linearLayout.setOrientation(LinearLayout.VERTICAL);

        TextView info = new TextView(activity);
        info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        info.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
        info.setText(LuckgramCacheStrings.chatUsesSpace(AndroidUtilities.formatFileSize(Math.max(0, sizeBytes))) + "\n" + LuckgramCacheStrings.chatKeepMediaInfo());
        linearLayout.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 8));

        for (int a = 0; a < options.length; a++) {
            RadioColorCell cell = new RadioColorCell(activity);
            cell.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
            cell.setTag(a);
            cell.setCheckColor(Theme.getColor(Theme.key_radioBackground), Theme.getColor(Theme.key_dialogRadioBackgroundChecked));
            cell.setTextAndValue(options[a], selected == a);
            linearLayout.addView(cell);
            cell.setOnClickListener(v -> {
                int sel = (Integer) v.getTag();
                builder.getDismissRunnable().run();
                controller.setDialogKeepMedia(dialogId, sel == 0 ? -1 : CHAT_OPTIONS[sel - 1]);
                AutoDeleteMediaTask.runNow();
                if (onChanged != null) {
                    onChanged.run();
                }
            });
        }
        builder.setTitle(LuckgramCacheStrings.chatKeepMedia());
        builder.setView(linearLayout);
        builder.setPositiveButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        if (fragment.getVisibleDialog() != null) {
            // Opened on top of the chat's storage sheet: keep the sheet open underneath.
            dialog.show();
        } else {
            fragment.showDialog(dialog);
        }
    }

    /** Cache size limit options (gigabytes; Integer.MAX_VALUE = no limit). */
    public static final int[] CACHE_LIMIT_OPTIONS = {1, 2, 5, 10, 20, 50, Integer.MAX_VALUE};

    public static String cacheLimitLabel(int gigabytes) {
        return gigabytes == Integer.MAX_VALUE ? LuckgramCacheStrings.noLimit() : LuckgramCacheStrings.cacheLimitOption(gigabytes);
    }

    /** Shows the cache size limit chooser; {@code current} must be one of CACHE_LIMIT_OPTIONS. */
    public static void showCacheLimitPicker(BaseFragment fragment, int current, Runnable onChanged) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        String[] options = new String[CACHE_LIMIT_OPTIONS.length];
        int selected = options.length - 1;
        for (int i = 0; i < CACHE_LIMIT_OPTIONS.length; i++) {
            options[i] = cacheLimitLabel(CACHE_LIMIT_OPTIONS[i]);
            if (CACHE_LIMIT_OPTIONS[i] == current) {
                selected = i;
            }
        }
        fragment.showDialog(org.telegram.ui.Components.AlertsCreator.createSingleChoiceDialog(activity, options, LuckgramCacheStrings.cacheLimitTitle(), selected, (d, which) -> {
            org.telegram.messenger.SharedConfig.getPreferences().edit().putInt("cache_limit", CACHE_LIMIT_OPTIONS[which]).apply();
            AutoDeleteMediaTask.runNow();
            if (onChanged != null) {
                onChanged.run();
            }
        }));
    }
}
