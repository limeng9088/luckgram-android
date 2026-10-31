package org.telegram.luckgram.ai;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Per-account local storage of AI conversations (spec 18 需求 6). Everything is kept in memory and
 * written to {@code files/luckgram_ai/<account>.json} on a background queue. Removed with the
 * account's data on logout ({@link #clearAccount}, called from LuckgramSettings.onAccountCleared).
 *
 * <p>All methods must be called on the UI thread.
 */
public final class LuckgramAiStore {

    public interface Listener {
        void onConversationsChanged();
    }

    private static final DispatchQueue diskQueue = new DispatchQueue("luckgramAiStore");
    private static final LuckgramAiStore[] instances = new LuckgramAiStore[UserConfig.MAX_ACCOUNT_COUNT];

    private final int account;
    private final ArrayList<LuckgramAiConversation> conversations = new ArrayList<>();
    private final ArrayList<Listener> listeners = new ArrayList<>();
    private boolean loaded;
    private Runnable pendingSave;

    public static LuckgramAiStore getInstance(int account) {
        LuckgramAiStore s = instances[account];
        if (s == null) {
            s = instances[account] = new LuckgramAiStore(account);
        }
        return s;
    }

    private LuckgramAiStore(int account) {
        this.account = account;
    }

    static File file(int account) {
        File dir = new File(ApplicationLoader.getFilesDirFixed(), "luckgram_ai");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, account + ".json");
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        File f = file(account);
        if (!f.exists()) {
            return;
        }
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            JSONArray arr = new JSONObject(new String(buf, 0, off, StandardCharsets.UTF_8)).optJSONArray("conversations");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) {
                        conversations.add(LuckgramAiConversation.fromJson(o));
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** Conversations, most recently used first. Ones with no messages are not listed. */
    public List<LuckgramAiConversation> list() {
        ensureLoaded();
        ArrayList<LuckgramAiConversation> out = new ArrayList<>();
        for (LuckgramAiConversation c : conversations) {
            if (!c.messages.isEmpty()) {
                out.add(c);
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        return out;
    }

    public LuckgramAiConversation get(String id) {
        ensureLoaded();
        if (id == null) return null;
        for (LuckgramAiConversation c : conversations) {
            if (c.id.equals(id)) {
                return c;
            }
        }
        return null;
    }

    /** A new, empty conversation (not listed until it has a message). */
    public LuckgramAiConversation create(String task, String systemPrompt, String title) {
        ensureLoaded();
        LuckgramAiConversation c = LuckgramAiConversation.create(task, systemPrompt, title);
        conversations.add(c);
        return c;
    }

    /**
     * The conversation with this {@code key}, created on first use (extension point: e.g. one
     * conversation per agent). Task and system instruction are updated if they changed.
     */
    public LuckgramAiConversation findOrCreate(String key, String task, String systemPrompt, String title) {
        ensureLoaded();
        for (LuckgramAiConversation c : conversations) {
            if (key.equals(c.key)) {
                boolean changed = false;
                if (task != null && !task.equals(c.task)) {
                    c.task = task;
                    changed = true;
                }
                if (systemPrompt != null && !systemPrompt.equals(c.systemPrompt)) {
                    c.systemPrompt = systemPrompt;
                    changed = true;
                }
                if (changed) save(c);
                return c;
            }
        }
        LuckgramAiConversation c = create(task, systemPrompt, title);
        c.key = key;
        return c;
    }

    /** Call after changing a conversation; writes it to disk shortly after. */
    public void save(LuckgramAiConversation c) {
        if (c != null) {
            c.updatedAt = System.currentTimeMillis();
        }
        scheduleWrite();
        notifyListeners();
    }

    public void delete(String id) {
        ensureLoaded();
        for (int i = 0; i < conversations.size(); i++) {
            if (conversations.get(i).id.equals(id)) {
                conversations.remove(i);
                break;
            }
        }
        scheduleWrite();
        notifyListeners();
    }

    private void scheduleWrite() {
        if (pendingSave != null) {
            AndroidUtilities.cancelRunOnUIThread(pendingSave);
        }
        pendingSave = () -> {
            pendingSave = null;
            final String json;
            try {
                JSONArray arr = new JSONArray();
                for (LuckgramAiConversation c : conversations) {
                    if (!c.messages.isEmpty()) {
                        arr.put(c.toJson());
                    }
                }
                json = new JSONObject().put("version", 1).put("conversations", arr).toString();
            } catch (Exception e) {
                FileLog.e(e);
                return;
            }
            final int acc = account;
            diskQueue.postRunnable(() -> {
                File f = file(acc);
                File tmp = new File(f.getPath() + ".tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                    out.getFD().sync();
                } catch (Exception e) {
                    FileLog.e(e);
                    return;
                }
                if (!tmp.renameTo(f)) {
                    FileLog.e("luckgram ai: can't write " + f);
                }
            });
        };
        AndroidUtilities.runOnUIThread(pendingSave, 300);
    }

    public void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void notifyListeners() {
        for (Listener l : new ArrayList<>(listeners)) {
            l.onConversationsChanged();
        }
    }

    /** Deletes the account's AI conversations (logout / account deleted). Any thread. */
    public static void clearAccount(int account) {
        AndroidUtilities.runOnUIThread(() -> {
            LuckgramAiStore s = instances[account];
            if (s != null) {
                if (s.pendingSave != null) {
                    AndroidUtilities.cancelRunOnUIThread(s.pendingSave);
                    s.pendingSave = null;
                }
                s.conversations.clear();
                s.loaded = true;
                s.notifyListeners();
            }
            // after any write already queued for this account
            diskQueue.postRunnable(() -> {
                //noinspection ResultOfMethodCallIgnored
                file(account).delete();
            });
        });
    }
}
