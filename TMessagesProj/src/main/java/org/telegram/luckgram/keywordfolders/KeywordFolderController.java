/*
 * Luckgram — keyword folders.
 * Licensed under GNU GPL v. 2 or later, like the rest of this Telegram-based project.
 */

package org.telegram.luckgram.keywordfolders;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.SparseArray;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BaseController;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Per-account keyword folders.
 * <p>
 * Keyword folders are exposed to the rest of the app as {@link MessagesController.DialogFilter}
 * objects (with {@code keywordFolder != null} and an id &ge; {@link #FILTER_ID_BASE}) so they show up
 * in the folder tab bar next to cloud folders and can be reordered there. They are never sent to the
 * Telegram server: every place that syncs folders skips them via {@link #isLocalFilterId(int)}.
 */
public class KeywordFolderController extends BaseController implements NotificationCenter.NotificationCenterDelegate {

    /** Feature switch key, registered in {@link org.telegram.luckgram.LuckgramFeatures} (default on). */
    public static final String SETTING_KEY = "keyword_folders";
    public static final int MAX_FOLDERS = 10;
    public static final int MAX_KEYWORDS = 10;
    /** How many cached messages per chat are inspected besides the last one. */
    public static final int RECENT_MESSAGES_LIMIT = 50;
    /** How many cached messages are inspected when jumping between hits inside a chat. */
    public static final int JUMP_MESSAGES_LIMIT = 500;
    /** Cloud folder ids are 2..255; local keyword folders use ids from here up. */
    public static final int FILTER_ID_BASE = 1_000_000;

    private static final KeywordFolderController[] instances = new KeywordFolderController[UserConfig.MAX_ACCOUNT_COUNT];

    public static KeywordFolderController getInstance(int num) {
        KeywordFolderController local = instances[num];
        if (local == null) {
            synchronized (KeywordFolderController.class) {
                local = instances[num];
                if (local == null) {
                    instances[num] = local = new KeywordFolderController(num);
                }
            }
        }
        return local;
    }

    // region feature switch

    /** Local switch AND remote config (S4), via LuckgramSettings (#43, #54). */
    public static boolean isEnabled() {
        return org.telegram.luckgram.LuckgramSettings.isEnabled(SETTING_KEY);
    }

    public static void setEnabled(boolean enabled) {
        // LuckgramSettings posts luckgramSettingsChanged; every account reapplies its filters then.
        org.telegram.luckgram.LuckgramSettings.setEnabled(SETTING_KEY, enabled);
    }

    // endregion

    public static boolean isLocalFilterId(int id) {
        return id >= FILTER_ID_BASE;
    }

    public static boolean isKeywordFilter(MessagesController.DialogFilter filter) {
        return filter != null && filter.keywordFolder != null;
    }

    /** Keywords to highlight for the given (selected) filter, or null. */
    public static List<String> highlightKeywords(MessagesController.DialogFilter filter) {
        if (filter == null || filter.keywordFolder == null || !isEnabled()) {
            return null;
        }
        return filter.keywordFolder.keywords;
    }

    private final ArrayList<KeywordFolder> folders = new ArrayList<>();
    private final SparseArray<MessagesController.DialogFilter> filters = new SparseArray<>();
    /** folder id -> dialog ids whose recent cached messages matched. UI thread only. */
    private final HashMap<Integer, HashSet<Long>> matches = new HashMap<>();
    private boolean loaded;
    private boolean lastAppliedEnabled = isEnabled();
    private boolean scanned;
    private int scanToken;

    private KeywordFolderController(int num) {
        super(num);
        AndroidUtilities.runOnUIThread(() -> {
            getNotificationCenter().addObserver(this, NotificationCenter.didReceiveNewMessages);
            // Edits and deletions can add or remove a hit (#51).
            getNotificationCenter().addObserver(this, NotificationCenter.replaceMessagesObjects);
            getNotificationCenter().addObserver(this, NotificationCenter.messagesDeleted);
            // Local switch on the settings page or remote config update (S4).
            NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.luckgramSettingsChanged);
        });
    }

    private SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("luckgram_keyword_folders_" + currentAccount, Context.MODE_PRIVATE);
    }

    private void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        folders.clear();
        SharedPreferences p = prefs();
        long owner = p.getLong("owner", 0);
        long me = getUserConfig().getClientUserId();
        if (owner != 0 && me != 0 && owner != me) {
            // Data belongs to an account that used to live in this slot.
            p.edit().clear().commit();
            return;
        }
        try {
            JSONArray arr = new JSONArray(p.getString("folders", "[]"));
            for (int i = 0; i < arr.length() && folders.size() < MAX_FOLDERS; i++) {
                KeywordFolder f = KeywordFolder.fromJson(arr.getJSONObject(i));
                if (f.id > 0 && !f.keywords.isEmpty()) {
                    folders.add(f);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (KeywordFolder f : folders) {
                arr.put(f.toJson());
            }
            prefs().edit()
                    .putLong("owner", getUserConfig().getClientUserId())
                    .putString("folders", arr.toString())
                    .apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public ArrayList<KeywordFolder> getFolders() {
        load();
        return folders;
    }

    public boolean canCreate() {
        load();
        return folders.size() < MAX_FOLDERS;
    }

    public KeywordFolder getFolder(int folderId) {
        load();
        for (KeywordFolder f : folders) {
            if (f.id == folderId) {
                return f;
            }
        }
        return null;
    }

    private MessagesController.DialogFilter filterFor(KeywordFolder folder) {
        MessagesController.DialogFilter filter = filters.get(folder.id);
        if (filter == null) {
            filter = new MessagesController.DialogFilter();
            filters.put(folder.id, filter);
        }
        filter.id = FILTER_ID_BASE + folder.id;
        filter.name = folder.name;
        filter.entities = new ArrayList<>();
        filter.order = folder.order;
        filter.flags = 0;
        filter.color = -1;
        filter.locked = false;
        filter.unreadCount = filter.pendingUnreadCount = 0;
        filter.keywordFolder = folder;
        return filter;
    }

    /**
     * Called whenever MessagesController replaces its folder list: drops stale keyword filters and
     * appends the current ones. The caller sorts the list by {@code order}.
     */
    public void mergeInto(ArrayList<MessagesController.DialogFilter> list) {
        for (int i = list.size() - 1; i >= 0; i--) {
            if (isKeywordFilter(list.get(i)) || isLocalFilterId(list.get(i).id)) {
                list.remove(i);
            }
        }
        if (!isEnabled()) {
            return;
        }
        load();
        for (KeywordFolder f : folders) {
            list.add(filterFor(f));
        }
        if (!scanned && !folders.isEmpty()) {
            scanned = true;
            rescan();
        }
    }

    /** Re-reads the switch and the folder list into MessagesController (e.g. after the switch changed). */
    public void reapplyFilters() {
        AndroidUtilities.runOnUIThread(() -> {
            MessagesController mc = getMessagesController();
            if (!mc.dialogFiltersLoaded) {
                return;
            }
            for (int b = 0; b < mc.selectedDialogFilter.length; b++) {
                if (isKeywordFilter(mc.selectedDialogFilter[b])) {
                    mc.selectedDialogFilter[b] = null;
                }
            }
            mergeInto(mc.dialogFilters);
            mc.dialogFiltersById.clear();
            for (int a = 0; a < mc.dialogFilters.size(); a++) {
                mc.dialogFiltersById.put(mc.dialogFilters.get(a).id, mc.dialogFilters.get(a));
            }
            Collections.sort(mc.dialogFilters, (o1, o2) -> Integer.compare(o1.order, o2.order));
            getNotificationCenter().postNotificationName(NotificationCenter.dialogFiltersUpdated);
        });
    }

    /** Creates or updates a folder. {@code folder.id == 0} means new. */
    public void saveFolder(KeywordFolder folder) {
        load();
        MessagesController mc = getMessagesController();
        if (folder.id == 0) {
            if (folders.size() >= MAX_FOLDERS) {
                return;
            }
            int maxId = 0;
            for (KeywordFolder f : folders) {
                maxId = Math.max(maxId, f.id);
            }
            folder.id = maxId + 1;
            int maxOrder = 0;
            for (int a = 0; a < mc.dialogFilters.size(); a++) {
                maxOrder = Math.max(maxOrder, mc.dialogFilters.get(a).order);
            }
            folder.order = maxOrder + 1;
            folders.add(folder);
            save();
            if (isEnabled() && mc.dialogFiltersLoaded) {
                MessagesController.DialogFilter filter = filterFor(folder);
                mc.dialogFilters.add(filter);
                mc.dialogFiltersById.put(filter.id, filter);
            }
        } else {
            KeywordFolder existing = getFolder(folder.id);
            if (existing == null) {
                return;
            }
            existing.name = folder.name;
            existing.keywords = new ArrayList<>(folder.keywords);
            existing.scope = folder.scope;
            save();
            if (isEnabled() && mc.dialogFiltersLoaded) {
                filterFor(existing);
            }
            folder = existing;
        }
        matches.remove(folder.id);
        getNotificationCenter().postNotificationName(NotificationCenter.dialogFiltersUpdated);
        rescan();
    }

    public void deleteFolder(int folderId) {
        load();
        KeywordFolder folder = getFolder(folderId);
        if (folder == null) {
            return;
        }
        folders.remove(folder);
        save();
        matches.remove(folderId);
        MessagesController.DialogFilter filter = filters.get(folderId);
        filters.remove(folderId);
        if (filter != null) {
            MessagesController mc = getMessagesController();
            for (int b = 0; b < mc.selectedDialogFilter.length; b++) {
                if (mc.selectedDialogFilter[b] == filter) {
                    mc.selectedDialogFilter[b] = null;
                }
            }
            mc.removeFilter(filter);
        }
    }

    /** Persists the position of keyword folders after the user reordered the tab bar / folder list. */
    public void saveOrders(List<MessagesController.DialogFilter> list) {
        load();
        boolean changed = false;
        for (int i = 0; i < list.size(); i++) {
            MessagesController.DialogFilter filter = list.get(i);
            if (filter.keywordFolder != null && filter.keywordFolder.order != filter.order) {
                filter.keywordFolder.order = filter.order;
                changed = true;
            }
        }
        if (changed) {
            save();
        }
    }

    /** Called on log out: removes every keyword folder of this account from the device. */
    public void clearAccountData() {
        folders.clear();
        filters.clear();
        matches.clear();
        scanned = false;
        loaded = false;
        scanToken++;
        prefs().edit().clear().commit();
    }

    // region matching

    private boolean inScope(KeywordFolder folder, long dialogId) {
        if (folder.scope == KeywordFolder.SCOPE_ALL) {
            return true;
        }
        if (DialogObject.isEncryptedDialog(dialogId) || DialogObject.isUserDialog(dialogId)) {
            return (folder.scope & KeywordFolder.SCOPE_PRIVATE) != 0;
        }
        TLRPC.Chat chat = getMessagesController().getChat(-dialogId);
        if (ChatObject.isChannelAndNotMegaGroup(chat)) {
            return (folder.scope & KeywordFolder.SCOPE_CHANNELS) != 0;
        }
        return (folder.scope & KeywordFolder.SCOPE_GROUPS) != 0;
    }

    static CharSequence textOf(TLRPC.Message message) {
        if (message == null) {
            return null;
        }
        return message.message;
    }

    /** Folder membership check used by {@link MessagesController.DialogFilter#includesDialog}. */
    public boolean includesDialog(KeywordFolder folder, TLRPC.Dialog dialog) {
        if (folder == null || dialog == null || !isEnabled()) {
            return false;
        }
        final long did = dialog.id;
        if (!inScope(folder, did)) {
            return false;
        }
        HashSet<Long> set = matches.get(folder.id);
        if (set != null && set.contains(did)) {
            return true;
        }
        // The last message is checked live so new messages show up immediately.
        ArrayList<MessageObject> last = getMessagesController().dialogMessage.get(did);
        if (last != null) {
            for (int i = 0; i < last.size(); i++) {
                MessageObject m = last.get(i);
                if (m != null && KeywordMatcher.matchesAny(textOf(m.messageOwner), folder.keywords)) {
                    addMatch(folder.id, did);
                    return true;
                }
            }
        }
        return false;
    }

    private boolean addMatch(int folderId, long did) {
        HashSet<Long> set = matches.get(folderId);
        if (set == null) {
            set = new HashSet<>();
            matches.put(folderId, set);
        }
        return set.add(did);
    }

    /** Scans the latest {@link #RECENT_MESSAGES_LIMIT} cached messages of every local chat. */
    public void rescan() {
        load();
        if (folders.isEmpty() || !isEnabled()) {
            return;
        }
        final int token = ++scanToken;
        final ArrayList<KeywordFolder> snapshot = new ArrayList<>();
        for (KeywordFolder f : folders) {
            snapshot.add(f.copy());
        }
        final long selfId = getUserConfig().getClientUserId();
        getMessagesStorage().getStorageQueue().postRunnable(() -> {
            final HashMap<Integer, HashSet<Long>> result = new HashMap<>();
            SQLiteCursor cursor = null;
            try {
                SQLiteDatabase db = getMessagesStorage().getDatabase();
                ArrayList<Long> dids = new ArrayList<>();
                cursor = db.queryFinalized("SELECT did FROM dialogs");
                while (cursor.next()) {
                    dids.add(cursor.longValue(0));
                }
                cursor.dispose();
                cursor = null;
                for (int d = 0; d < dids.size(); d++) {
                    if (token != scanToken) {
                        return;
                    }
                    long did = dids.get(d);
                    cursor = db.queryFinalized(String.format(Locale.US, "SELECT data FROM messages_v2 WHERE uid = %d ORDER BY date DESC LIMIT %d", did, RECENT_MESSAGES_LIMIT));
                    while (cursor.next()) {
                        NativeByteBuffer data = cursor.byteBufferValue(0);
                        if (data == null) {
                            continue;
                        }
                        TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                        data.reuse();
                        CharSequence text = textOf(message);
                        if (text == null || text.length() == 0) {
                            continue;
                        }
                        for (KeywordFolder f : snapshot) {
                            HashSet<Long> set = result.get(f.id);
                            if (set != null && set.contains(did)) {
                                continue;
                            }
                            if (KeywordMatcher.matchesAny(text, f.keywords)) {
                                if (set == null) {
                                    set = new HashSet<>();
                                    result.put(f.id, set);
                                }
                                set.add(did);
                            }
                        }
                    }
                    cursor.dispose();
                    cursor = null;
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                if (cursor != null) {
                    cursor.dispose();
                }
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (token != scanToken) {
                    return;
                }
                matches.clear();
                matches.putAll(result);
                notifyFoldersChanged();
            });
        });
    }

    private void notifyFoldersChanged() {
        MessagesController mc = getMessagesController();
        for (int b = 0; b < mc.selectedDialogFilter.length; b++) {
            if (isKeywordFilter(mc.selectedDialogFilter[b])) {
                mc.onFilterUpdate(mc.selectedDialogFilter[b]);
                return;
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.luckgramSettingsChanged) {
            Object key = args.length > 0 ? args[0] : null;
            if (key == null || SETTING_KEY.equals(key)) {
                if (isEnabled() != lastAppliedEnabled) {
                    lastAppliedEnabled = !lastAppliedEnabled;
                    reapplyFilters();
                    if (lastAppliedEnabled) {
                        rescan();
                    }
                }
            }
            return;
        }
        if (!isEnabled() || !loaded || folders.isEmpty()) {
            return;
        }
        if (id == NotificationCenter.replaceMessagesObjects) {
            if (args.length > 0 && args[0] instanceof Long) {
                HashSet<Long> dids = new HashSet<>();
                dids.add((Long) args[0]);
                recheckDialogs(dids);
            }
            return;
        }
        if (id == NotificationCenter.messagesDeleted) {
            if (args.length > 2 && args[2] instanceof Boolean && (Boolean) args[2]) {
                return; // scheduled messages
            }
            HashSet<Long> dids = new HashSet<>();
            long channelId = args.length > 1 && args[1] instanceof Long ? (Long) args[1] : 0;
            if (channelId != 0) {
                dids.add(-channelId);
            } else {
                // Private chats and basic groups: the event does not say which chat, so re-check
                // every chat that currently counts as a hit.
                for (HashSet<Long> set : matches.values()) {
                    dids.addAll(set);
                }
            }
            recheckDialogs(dids);
            return;
        }
        if (id != NotificationCenter.didReceiveNewMessages) {
            return;
        }
        if (args.length > 2 && args[2] instanceof Boolean && (Boolean) args[2]) {
            return; // scheduled messages
        }
        if (!(args[0] instanceof Long) || !(args[1] instanceof ArrayList)) {
            return;
        }
        long did = (Long) args[0];
        ArrayList<MessageObject> messages = (ArrayList<MessageObject>) args[1];
        boolean changed = false;
        for (KeywordFolder f : folders) {
            for (int i = 0; i < messages.size(); i++) {
                MessageObject m = messages.get(i);
                if (m != null && KeywordMatcher.matchesAny(textOf(m.messageOwner), f.keywords)) {
                    changed |= addMatch(f.id, did);
                    break;
                }
            }
        }
        if (changed) {
            // Let MessagesController finish updating the dialog first.
            AndroidUtilities.runOnUIThread(this::notifyFoldersChanged);
        }
    }

    public static class Hit {
        public final int messageId;
        public final String keyword;

        Hit(int messageId, String keyword) {
            this.messageId = messageId;
            this.keyword = keyword;
        }
    }

    /**
     * Re-evaluates hits of the given chats from the latest cached messages, after edits or deletions.
     * Queued behind the storage writes for the same change, so it reads the updated rows.
     */
    private void recheckDialogs(HashSet<Long> dids) {
        if (dids.isEmpty()) {
            return;
        }
        final int token = scanToken;
        final ArrayList<KeywordFolder> snapshot = new ArrayList<>();
        for (KeywordFolder f : folders) {
            snapshot.add(f.copy());
        }
        final ArrayList<Long> dialogIds = new ArrayList<>(dids);
        getMessagesStorage().getStorageQueue().postRunnable(() -> {
            final HashMap<Integer, HashSet<Long>> found = new HashMap<>();
            SQLiteCursor cursor = null;
            try {
                SQLiteDatabase db = getMessagesStorage().getDatabase();
                for (long did : dialogIds) {
                    cursor = db.queryFinalized(String.format(Locale.US, "SELECT data FROM messages_v2 WHERE uid = %d ORDER BY date DESC LIMIT %d", did, RECENT_MESSAGES_LIMIT));
                    while (cursor.next()) {
                        NativeByteBuffer data = cursor.byteBufferValue(0);
                        if (data == null) {
                            continue;
                        }
                        TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                        data.reuse();
                        CharSequence text = textOf(message);
                        if (text == null || text.length() == 0) {
                            continue;
                        }
                        for (KeywordFolder f : snapshot) {
                            if (KeywordMatcher.matchesAny(text, f.keywords)) {
                                HashSet<Long> set = found.get(f.id);
                                if (set == null) {
                                    set = new HashSet<>();
                                    found.put(f.id, set);
                                }
                                set.add(did);
                            }
                        }
                    }
                    cursor.dispose();
                    cursor = null;
                }
            } catch (Exception e) {
                FileLog.e(e);
                return;
            } finally {
                if (cursor != null) {
                    cursor.dispose();
                }
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (token != scanToken) {
                    return; // a full rescan is under way and will replace everything
                }
                boolean changed = false;
                for (KeywordFolder f : snapshot) {
                    HashSet<Long> hits = found.get(f.id);
                    for (long did : dialogIds) {
                        boolean hit = hits != null && hits.contains(did);
                        HashSet<Long> set = matches.get(f.id);
                        if (hit) {
                            changed |= addMatch(f.id, did);
                        } else if (set != null) {
                            changed |= set.remove(did);
                        }
                    }
                }
                if (changed) {
                    notifyFoldersChanged();
                }
            });
        });
    }

    /**
     * Loads cached messages of {@code dialogId} that contain any of {@code keywords}, newest first.
     */
    public void loadHits(long dialogId, List<String> keywords, Utilities.Callback<ArrayList<Hit>> callback) {
        final ArrayList<String> kws = new ArrayList<>(keywords);
        getMessagesStorage().getStorageQueue().postRunnable(() -> {
            ArrayList<Hit> ids = new ArrayList<>();
            SQLiteCursor cursor = null;
            try {
                cursor = getMessagesStorage().getDatabase().queryFinalized(String.format(Locale.US, "SELECT mid, data FROM messages_v2 WHERE uid = %d ORDER BY date DESC, mid DESC LIMIT %d", dialogId, JUMP_MESSAGES_LIMIT));
                while (cursor.next()) {
                    int mid = cursor.intValue(0);
                    NativeByteBuffer data = cursor.byteBufferValue(1);
                    if (data == null) {
                        continue;
                    }
                    TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                    data.reuse();
                    String k = KeywordMatcher.firstMatch(textOf(message), kws);
                    if (k != null) {
                        ids.add(new Hit(mid, k));
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                if (cursor != null) {
                    cursor.dispose();
                }
            }
            AndroidUtilities.runOnUIThread(() -> callback.run(ids));
        });
    }

    /** First keyword of {@code folder} that appears in the chat's last message or cached hits, for the jump button label. */
    public String keywordForDialog(KeywordFolder folder, long dialogId) {
        ArrayList<MessageObject> last = getMessagesController().dialogMessage.get(dialogId);
        if (last != null) {
            for (int i = 0; i < last.size(); i++) {
                MessageObject m = last.get(i);
                if (m != null) {
                    String k = KeywordMatcher.firstMatch(textOf(m.messageOwner), folder.keywords);
                    if (k != null) {
                        return k;
                    }
                }
            }
        }
        return folder.keywords.isEmpty() ? null : folder.keywords.get(0);
    }

    // endregion
}
