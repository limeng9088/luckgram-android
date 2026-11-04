package org.telegram.luckgram.ocr;

import org.telegram.luckgram.LuckgramStrings;
import org.telegram.messenger.MessageObject;
import org.telegram.ui.ActionBar.BaseFragment;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Entry points for image features (specs#106 OCR, specs#108 image translation): the photo
 * long-press menu and the viewer's "⋯" menu ({@link #PLACE_MENU}), the small buttons at the bottom
 * right of the full-screen viewer ({@link #PLACE_VIEWER_BUTTON}) and the gallery preview before
 * sending ({@link #PLACE_ATTACH_PREVIEW}). Those places list {@link #actions(int)}; a feature adds
 * one {@code register(...)} line in the static block below and doesn't touch ChatActivity /
 * PhotoViewer. Main thread only.
 */
public final class LuckgramImageActions {

    public static final int PLACE_MENU = 1;
    public static final int PLACE_VIEWER_BUTTON = 2;
    public static final int PLACE_ATTACH_PREVIEW = 4;

    public interface Visibility {
        /** {@code message} is null in the gallery preview. */
        boolean isVisible(MessageObject message);
    }

    public interface Runner {
        /**
         * @param host    the page the action was picked on (chat, viewer's parent, attach preview)
         * @param image   the image file on the device
         * @param message the photo message, or null in the gallery preview
         */
        void run(BaseFragment host, File image, MessageObject message);
    }

    public static final class Action {
        public final String id;
        final String titleZh, titleEn;
        /** Drawable resource for menus, or 0. */
        public final int icon;
        /** Short label of the viewer button (e.g. 「文字」 / 「译」). */
        final String buttonZh, buttonEn;
        /** {@link #PLACE_MENU} | {@link #PLACE_VIEWER_BUTTON} | {@link #PLACE_ATTACH_PREVIEW}. */
        public final int places;
        final Visibility visibility;
        public final Runner runner;

        public Action(String id, String titleZh, String titleEn, int icon, String buttonZh, String buttonEn, int places, Visibility visibility, Runner runner) {
            this.id = id;
            this.titleZh = titleZh;
            this.titleEn = titleEn;
            this.icon = icon;
            this.buttonZh = buttonZh;
            this.buttonEn = buttonEn;
            this.places = places;
            this.visibility = visibility;
            this.runner = runner;
        }

        public String title() {
            return LuckgramStrings.get(titleZh, titleEn);
        }

        public String buttonTitle() {
            return LuckgramStrings.get(buttonZh, buttonEn);
        }

        public boolean isVisible(MessageObject message) {
            return visibility == null || visibility.isVisible(message);
        }
    }

    /** Gives a new image (e.g. the translated one, #108) back to the gallery preview before sending. */
    public interface ImageReplacer {
        void replace(File newImage);
    }

    private static ImageReplacer attachReplacer;

    /**
     * Set by the gallery preview while an {@link #PLACE_ATTACH_PREVIEW} action runs (the current
     * item is replaced with the new file), cleared when the preview closes. Null elsewhere.
     */
    public static void setAttachReplacer(ImageReplacer replacer) {
        attachReplacer = replacer;
    }

    /** The gallery preview's replacer, or null (then don't offer "send the new image"). */
    public static ImageReplacer attachReplacer() {
        return attachReplacer;
    }

    private static final ArrayList<Action> list = new ArrayList<>();

    static {
        // ---- image features register here, one line each ----
        register(new Action("ocr", "识别文字", "Recognize Text", org.telegram.messenger.R.drawable.msg_photo_text_regular, "文字", "Text",
            PLACE_MENU | PLACE_VIEWER_BUTTON | PLACE_ATTACH_PREVIEW, message -> LuckgramOcr.isEnabled(),
            (host, image, message) -> LuckgramOcrSheet.open(host, image))); // specs#106
        // specs#108 image translation (「翻译图片」 / 「译」): w-1148
    }

    private LuckgramImageActions() {
    }

    /** Replaces an action with the same id. */
    public static void register(Action action) {
        unregister(action.id);
        list.add(action);
    }

    public static void unregister(String id) {
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).id.equals(id)) list.remove(i);
        }
    }

    /** Registered actions shown at {@code place}, in registration order. */
    public static List<Action> actions(int place) {
        ArrayList<Action> out = new ArrayList<>();
        for (Action a : list) if ((a.places & place) != 0) out.add(a);
        return out;
    }

    /**
     * The image file of a photo message on the device, or null when it isn't a photo or hasn't
     * been downloaded yet.
     */
    public static File imageFile(int account, MessageObject message) {
        if (message == null || !message.isPhoto()) return null;
        File f = org.telegram.messenger.FileLoader.getInstance(account).getPathToMessage(message.messageOwner);
        return f != null && f.exists() ? f : null;
    }

    /** Actions at {@code place} that are visible for {@code message}. */
    public static List<Action> visibleActions(int place, MessageObject message) {
        ArrayList<Action> out = new ArrayList<>();
        for (Action a : actions(place)) if (a.isVisible(message)) out.add(a);
        return out;
    }
}
