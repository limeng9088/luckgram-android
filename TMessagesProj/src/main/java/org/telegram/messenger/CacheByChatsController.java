package org.telegram.messenger;

import android.text.TextUtils;
import android.util.LongSparseArray;

import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;

public class CacheByChatsController {

    public static int KEEP_MEDIA_DELETE = 4;
    public static int KEEP_MEDIA_FOREVER = 2;
    public static int KEEP_MEDIA_ONE_DAY = 3;
    public static int KEEP_MEDIA_ONE_WEEK = 0;
    public static int KEEP_MEDIA_ONE_MONTH = 1;
    public static int KEEP_MEDIA_TWO_DAY = 6;
    // Luckgram 08 (cache): finer keep-media options
    public static int KEEP_MEDIA_ONE_HOUR = 7;
    public static int KEEP_MEDIA_THREE_DAY = 8;
    //TEST VALUE
    public static int KEEP_MEDIA_ONE_MINUTE = 5;

    public static final int KEEP_MEDIA_TYPE_USER = 0;
    public static final int KEEP_MEDIA_TYPE_GROUP = 1;
    public static final int KEEP_MEDIA_TYPE_CHANNEL = 2;
    public static final int KEEP_MEDIA_TYPE_STORIES = 3;

    private final int currentAccount;

    private boolean gotKeepMediaByTypes = false;
    private final int[] keepMediaByTypes = { -1, -1, -1, -1 };

    public CacheByChatsController(int currentAccount) {
        this.currentAccount = currentAccount;
    }

    public static int getDefault(int type) {
        if (type == KEEP_MEDIA_TYPE_USER) {
            return KEEP_MEDIA_FOREVER;
        } else if (type == KEEP_MEDIA_TYPE_GROUP) {
            return KEEP_MEDIA_ONE_MONTH;
        } else if (type == KEEP_MEDIA_TYPE_CHANNEL) {
            return KEEP_MEDIA_ONE_WEEK;
        } else if (type == KEEP_MEDIA_TYPE_STORIES) {
            return KEEP_MEDIA_TWO_DAY;
        }
        return SharedConfig.keepMedia;
    }

    public static String getKeepMediaString(int keepMedia) {
        return org.telegram.luckgram.cache.LuckgramCacheStrings.keepMedia(keepMedia);
    }

    public static long getDaysInSeconds(int keepMedia) {
        long seconds;
        if (keepMedia == CacheByChatsController.KEEP_MEDIA_ONE_WEEK) {
            seconds =  60L * 60L * 24L * 7L;
        } else if (keepMedia == CacheByChatsController.KEEP_MEDIA_ONE_MONTH) {
            seconds = 60L * 60L * 24L * 30L;
        } else if (keepMedia == CacheByChatsController.KEEP_MEDIA_ONE_DAY) {
            seconds = 60L * 60L * 24L;
        } else if (keepMedia == CacheByChatsController.KEEP_MEDIA_TWO_DAY) {
            seconds = 60L * 60L * 24L * 2;
        } else if (keepMedia == CacheByChatsController.KEEP_MEDIA_THREE_DAY) {
            seconds = 60L * 60L * 24L * 3;
        } else if (keepMedia == CacheByChatsController.KEEP_MEDIA_ONE_HOUR) {
            seconds = 60L * 60L;
        } else if (keepMedia == CacheByChatsController.KEEP_MEDIA_ONE_MINUTE && BuildVars.DEBUG_PRIVATE_VERSION) { //one min
            seconds = 60L;
        } else {
            seconds = Long.MAX_VALUE;
        }
        return seconds;
    }

    public ArrayList<KeepMediaException> getKeepMediaExceptions(int type) {
        ArrayList<KeepMediaException> exceptions = new ArrayList<>();
        HashSet<Long> idsSet = new HashSet<>();
        String exceptionsHash = UserConfig.getInstance(currentAccount).getPreferences().getString("keep_media_exceptions_" + type, "");
        if (TextUtils.isEmpty(exceptionsHash)) {
            return exceptions;
        } else {
            ByteBuffer byteBuffer = ByteBuffer.wrap(Utilities.hexToBytes(exceptionsHash));
            int n = byteBuffer.getInt();
            for (int i = 0; i < n; i++) {
                KeepMediaException exception = new KeepMediaException(byteBuffer.getLong(), byteBuffer.getInt());
                if (!idsSet.contains(exception.dialogId)) {
                    idsSet.add(exception.dialogId);
                    exceptions.add(exception);
                }
            }
            byteBuffer.clear();
        }
        return exceptions;
    }

    public void saveKeepMediaExceptions(int type, ArrayList<KeepMediaException> exceptions) {
        String key = "keep_media_exceptions_" + type;
        if (exceptions.isEmpty()) {
            UserConfig.getInstance(currentAccount).getPreferences().edit().remove(key).apply();
        } else {
            int n = exceptions.size();
            ByteBuffer byteBuffer = ByteBuffer.allocate(4 + (8 + 4) * n);
            byteBuffer.putInt(n);
            for (int i = 0; i < n; i++) {
                byteBuffer.putLong(exceptions.get(i).dialogId);
                byteBuffer.putInt(exceptions.get(i).keepMedia);
            }
            UserConfig.getInstance(currentAccount).getPreferences().edit().putString(key, Utilities.bytesToHex(byteBuffer.array())).apply();
            byteBuffer.clear();
        }
    }

    public int getKeepMedia(int type) {
        if (!gotKeepMediaByTypes) {
            gotKeepMediaByTypes = true;
            for (int i = 0; i < 4; i++) {
                keepMediaByTypes[i] = SharedConfig.getPreferences().getInt("keep_media_type_" + i, getDefault(i));
            }
        }
        if (keepMediaByTypes[type] == -1) {
            return SharedConfig.keepMedia;
        }
        return keepMediaByTypes[type];
    }

    public void setKeepMedia(int type, int keepMedia) {
        if (!gotKeepMediaByTypes) {
            gotKeepMediaByTypes = true;
            for (int i = 0; i < 4; i++) {
                keepMediaByTypes[i] = SharedConfig.getPreferences().getInt("keep_media_type_" + i, getDefault(i));
            }
        }
        keepMediaByTypes[type] = keepMedia;
        SharedConfig.getPreferences().edit().putInt("keep_media_type_" + type, keepMedia).apply();
    }

    public void lookupFiles(ArrayList<? extends KeepMediaFile> keepMediaFiles) {
        LongSparseArray<ArrayList<KeepMediaFile>> filesByDialogId = FileLoader.getInstance(currentAccount).getFileDatabase().lookupFiles(keepMediaFiles);
        LongSparseArray<KeepMediaException> exceptionsByType = getKeepMediaExceptionsByDialogs();
        final long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
        for (int i = 0; i < filesByDialogId.size(); i++) {
            long dialogId = filesByDialogId.keyAt(i);
            ArrayList<? extends KeepMediaFile> files = filesByDialogId.valueAt(i);
            int type = getDialogKeepMediaType(dialogId, true);
            KeepMediaException exception = exceptionsByType.get(dialogId);
            // Luckgram 08: media in Saved Messages is never removed automatically.
            final boolean savedMessages = selfId != 0 && dialogId == selfId;
            for (int k = 0; k < files.size(); k++) {
                KeepMediaFile file = files.get(k);
                if (type >= 0) {
                    file.dialogType = type;
                }
                if (savedMessages) {
                    file.keepMedia = KEEP_MEDIA_FOREVER;
                    file.isStory = false;
                    file.isProtected = true;
                } else if (exception != null) {
                    file.keepMedia = exception.keepMedia;
                }
            }
        }
    }

    public LongSparseArray<KeepMediaException> getKeepMediaExceptionsByDialogs() {
        LongSparseArray<KeepMediaException> sparseArray = new LongSparseArray<>();
        for (int i = 0; i < 3; i++) {
            ArrayList<KeepMediaException> exceptions = getKeepMediaExceptions(i);
            if (exceptions != null) {
                for (int k = 0; k < exceptions.size(); k++) {
                    sparseArray.put(exceptions.get(k).dialogId, exceptions.get(k));
                }
            }
        }
        return sparseArray;
    }

    /**
     * Luckgram 08: keep-media category of a dialog. Supergroups count as groups (Telegram used to
     * file them under channels); encrypted chats count as private chats. Returns -1 if unknown.
     */
    public int getDialogKeepMediaType(long dialogId, boolean allowStorageLookup) {
        if (DialogObject.isEncryptedDialog(dialogId) || dialogId >= 0) {
            return KEEP_MEDIA_TYPE_USER;
        }
        TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-dialogId);
        if (chat == null && allowStorageLookup) {
            chat = MessagesStorage.getInstance(currentAccount).getChatSync(-dialogId);
        }
        if (chat == null) {
            return -1;
        } else if (ChatObject.isChannel(chat) && !chat.megagroup) {
            return KEEP_MEDIA_TYPE_CHANNEL;
        } else {
            return KEEP_MEDIA_TYPE_GROUP;
        }
    }

    /** Luckgram 08: the per-chat override, or null if the chat follows its category. */
    public KeepMediaException getDialogKeepMediaException(long dialogId) {
        for (int i = 0; i < 3; i++) {
            ArrayList<KeepMediaException> exceptions = getKeepMediaExceptions(i);
            for (int k = 0; k < exceptions.size(); k++) {
                if (exceptions.get(k).dialogId == dialogId) {
                    return exceptions.get(k);
                }
            }
        }
        return null;
    }

    /** Luckgram 08: effective keep-media value for a dialog (override, else category). */
    public int getDialogKeepMedia(long dialogId) {
        KeepMediaException exception = getDialogKeepMediaException(dialogId);
        if (exception != null) {
            return exception.keepMedia;
        }
        int type = getDialogKeepMediaType(dialogId, false);
        return getKeepMedia(type < 0 ? KEEP_MEDIA_TYPE_GROUP : type);
    }

    /**
     * Luckgram 08: sets (keepMedia >= 0) or removes (keepMedia < 0 or KEEP_MEDIA_DELETE) the
     * per-chat override. The override is stored in the list of the chat's own category.
     */
    public void setDialogKeepMedia(long dialogId, int keepMedia) {
        int type = getDialogKeepMediaType(dialogId, false);
        if (type < 0) {
            type = KEEP_MEDIA_TYPE_GROUP;
        }
        for (int i = 0; i < 3; i++) {
            ArrayList<KeepMediaException> exceptions = getKeepMediaExceptions(i);
            boolean changed = false;
            for (int k = 0; k < exceptions.size(); k++) {
                if (exceptions.get(k).dialogId == dialogId) {
                    exceptions.remove(k);
                    k--;
                    changed = true;
                }
            }
            if (i == type && keepMedia >= 0 && keepMedia != KEEP_MEDIA_DELETE) {
                exceptions.add(new KeepMediaException(dialogId, keepMedia));
                changed = true;
            }
            if (changed) {
                saveKeepMediaExceptions(i, exceptions);
            }
        }
    }

    public static class KeepMediaException {
        public final long dialogId;
        public int keepMedia;

        public KeepMediaException(long dialogId, int keepMedia) {
            this.dialogId = dialogId;
            this.keepMedia = keepMedia;
        }
    }

    public static class KeepMediaFile {
        final File file;
        int keepMedia = -1;
        int dialogType = KEEP_MEDIA_TYPE_CHANNEL;
        boolean isStory;
        // Luckgram 08: Saved Messages media, kept by both the keep-media and the size-limit cleanup
        boolean isProtected;

        public KeepMediaFile(File file) {
            this.file = file;
        }
    }
}
