package org.telegram.luckgram.writing;

import android.content.Context;
import android.view.View;

import org.telegram.luckgram.LuckgramStrings;
import org.telegram.ui.ChatActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Extension points of AI writing (spec 21 需求 6), for 20 (AI reply) and 24 (AI post). Those
 * features add one registration line in {@link AiWritingFeatures#registerAll()} and never change
 * the input field code. UI thread only.
 *
 * <h3>Menu items</h3> in the ✦ menu of the message field:
 * <pre>
 * AiWritingRegistry.register(new AiWritingRegistry.MenuItem("reply", "帮我回复", "Help me reply",
 *         R.drawable.msg_reply, 0) // order: smaller first; built-in effects use 100..140
 *     .allowEmptyText()                         // also offered when the field is empty
 *     .visibleWhen(host -&gt; host.getDialogId() &gt; 0)
 *     .onClick(host -&gt; ...));
 * </pre>
 *
 * <h3>Panel above the input field</h3> and writing to the field with undo:
 * <pre>
 * View panel = ...;
 * host.showPanel(panel);                      // replaces any other panel
 * host.setText("new text", true);             // true: "撤销" restores the previous text
 * host.hidePanel(panel);
 * </pre>
 */
public final class AiWritingRegistry {

    private AiWritingRegistry() {
    }

    /** What a menu item or panel can do with the message field of the open chat. */
    public interface Host {
        int getCurrentAccount();

        long getDialogId();

        /** The chat screen, e.g. to read recent messages or check rights; may be null outside chats. */
        ChatActivity getChatActivity();

        Context getContext();

        CharSequence getText();

        /** Replaces the field's text; with {@code undoable} the previous text can be restored with {@link #undo()}. */
        void setText(CharSequence text, boolean undoable);

        boolean canUndo();

        void undo();

        /** Shows {@code view} in the panel container above the input field (one panel at a time). */
        void showPanel(View view);

        /** Hides {@code view} if it is the panel shown. */
        void hidePanel(View view);

        /** True in a channel where the user can post. */
        boolean canPostInChannel();
    }

    public interface Visibility {
        boolean isVisible(Host host);
    }

    public interface Action {
        void run(Host host);
    }

    public static final class MenuItem {
        public final String id;
        final String titleZh, titleEn;
        public final int icon;
        public final int order;
        Visibility visibility;
        Action action;
        boolean allowEmptyText;

        /**
         * @param icon  drawable resource (0 for none)
         * @param order position in the menu, smaller first; built-in effects use 100..140
         */
        public MenuItem(String id, String titleZh, String titleEn, int icon, int order) {
            this.id = id;
            this.titleZh = titleZh;
            this.titleEn = titleEn;
            this.icon = icon;
            this.order = order;
        }

        public MenuItem visibleWhen(Visibility visibility) {
            this.visibility = visibility;
            return this;
        }

        public MenuItem onClick(Action action) {
            this.action = action;
            return this;
        }

        /** Offer the item (and the ✦ button) even when the field is empty. */
        public MenuItem allowEmptyText() {
            this.allowEmptyText = true;
            return this;
        }

        public String title() {
            return LuckgramStrings.get(titleZh, titleEn);
        }

        boolean isVisible(Host host, boolean hasText) {
            if (!hasText && !allowEmptyText) return false;
            return visibility == null || visibility.isVisible(host);
        }
    }

    private static final ArrayList<MenuItem> items = new ArrayList<>();

    /** Replaces an item with the same id. */
    public static void register(MenuItem item) {
        unregister(item.id);
        items.add(item);
    }

    public static void unregister(String id) {
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).id.equals(id)) items.remove(i);
        }
    }

    /** Items to show for this field, sorted by order. */
    static List<MenuItem> visibleItems(Host host, boolean hasText) {
        AiWritingFeatures.registerAll();
        ArrayList<MenuItem> out = new ArrayList<>();
        for (MenuItem item : items) {
            if (item.isVisible(host, hasText)) out.add(item);
        }
        Collections.sort(out, (a, b) -> Integer.compare(a.order, b.order));
        return out;
    }

    /** Whether the ✦ button should show for an empty field (some item allows it). */
    static boolean hasItemsForEmptyText(Host host) {
        return !visibleItems(host, false).isEmpty();
    }
}
