/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 *
 * TG fork: controller for the deleted-message vault (Feature 5).
 *
 * When the user enables the feature globally AND opts a specific chat in,
 * messages the other party deletes in that chat are snapshotted into a
 * private per-account store (DeletedMessagesStorage + a media folder in
 * the account's internal files dir) just before the stock deletion runs.
 *
 * Hard rules enforced here, in code:
 *  - the master switch (SharedConfig.forkDeletedMessagesEnabled) defaults
 *    to OFF; nothing is retained unless a chat is also opted in;
 *  - secret chats are never retained: the snapshot hook never sits on the
 *    secret-chat deletion path, opt-in refuses encrypted dialogs, and the
 *    snapshot hard-skips encrypted dialog ids again per row;
 *  - self-destruct / auto-delete content is never retained: any message
 *    with ttl_period set, or media with ttl_seconds set, is skipped;
 *  - the user's own deletions are really deleted: own delete flows remove
 *    the rows before any server echo could arrive, deleteMessages() also
 *    records the ids in a short-lived marker set the snapshot consults,
 *    clear-history / delete-dialog purge the chat's retained copies, and
 *    turning a chat's opt-in off purges its copies;
 *  - Saved Messages (self dialog) can never be opted in;
 *  - service messages are not retained;
 *  - media is copied only if it is already fully downloaded — this
 *    controller never starts a download — and only within the storage
 *    cap (oldest copied media is evicted first);
 *  - nothing here ever talks to the server, and retained copies have no
 *    reply / forward / share path anywhere in the app.
 */

package org.telegram.messenger;

import android.text.TextUtils;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

public class ForkDeletedMessagesController extends BaseController {

    // Entries in the own-delete marker set live this long (race insurance
    // only; own deletes are structurally excluded before this matters).
    private static final long DELETED_BY_ME_TTL_MS = 10L * 60 * 1000;

    private static volatile ForkDeletedMessagesController[] Instance = new ForkDeletedMessagesController[UserConfig.MAX_ACCOUNT_COUNT];

    public static ForkDeletedMessagesController getInstance(int num) {
        ForkDeletedMessagesController localInstance = Instance[num];
        if (localInstance == null) {
            synchronized (ForkDeletedMessagesController.class) {
                localInstance = Instance[num];
                if (localInstance == null) {
                    Instance[num] = localInstance = new ForkDeletedMessagesController(num);
                }
            }
        }
        return localInstance;
    }

    private final DeletedMessagesStorage storage;
    private final DispatchQueue queue;
    private final HashSet<Long> optedInDialogs = new HashSet<>();
    private boolean optedInLoaded;
    private final HashMap<Long, Long> recentlyDeletedByMe = new HashMap<>();

    public ForkDeletedMessagesController(int num) {
        super(num);
        storage = new DeletedMessagesStorage(num);
        queue = new DispatchQueue("forkDeletedMessagesQueue" + num);
    }

    public DispatchQueue getQueue() {
        return queue;
    }

    public boolean isEnabled() {
        return SharedConfig.forkDeletedMessagesEnabled;
    }

    // ---- opt-in set ----------------------------------------------------------

    private void ensureOptedInLoaded() {
        synchronized (optedInDialogs) {
            if (!optedInLoaded) {
                optedInLoaded = true;
                storage.loadOptedInDialogs(optedInDialogs);
            }
        }
    }

    // Hard exclusions live here so every caller inherits them: secret chats
    // and the self dialog (Saved Messages) can never be opted in.
    private boolean isOptInAllowed(long dialogId) {
        return dialogId != 0
                && !DialogObject.isEncryptedDialog(dialogId)
                && dialogId != getUserConfig().getClientUserId();
    }

    public boolean isOptedIn(long dialogId) {
        if (!isOptInAllowed(dialogId)) {
            return false;
        }
        ensureOptedInLoaded();
        synchronized (optedInDialogs) {
            return optedInDialogs.contains(dialogId);
        }
    }

    public boolean hasOptedInDialogs() {
        ensureOptedInLoaded();
        synchronized (optedInDialogs) {
            return !optedInDialogs.isEmpty();
        }
    }

    public ArrayList<Long> getOptedInDialogs() {
        ensureOptedInLoaded();
        synchronized (optedInDialogs) {
            return new ArrayList<>(optedInDialogs);
        }
    }

    public void setOptedIn(final long dialogId, boolean optedIn) {
        if (optedIn && !isOptInAllowed(dialogId)) {
            return;
        }
        ensureOptedInLoaded();
        synchronized (optedInDialogs) {
            if (optedIn) {
                optedInDialogs.add(dialogId);
            } else {
                optedInDialogs.remove(dialogId);
            }
        }
        storage.setOptedInDialog(dialogId, optedIn);
        if (!optedIn) {
            // Consent symmetry: opting a chat out purges its retained copies.
            queue.postRunnable(() -> purgeDialog(dialogId));
        }
    }

    // ---- own-delete marker set -------------------------------------------------

    // Called from MessagesController.deleteMessages() (the user's own delete
    // flow). Purely in-memory race insurance: the snapshot skips these ids.
    public void noteDeletedByMe(long dialogId, ArrayList<Integer> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (recentlyDeletedByMe) {
            if (recentlyDeletedByMe.size() > 1024) {
                recentlyDeletedByMe.clear();
            }
            for (int i = 0, size = messageIds.size(); i < size; i++) {
                recentlyDeletedByMe.put(deletedByMeKey(dialogId, messageIds.get(i)), now);
            }
        }
    }

    private boolean wasDeletedByMe(long dialogId, int messageId) {
        synchronized (recentlyDeletedByMe) {
            Long time = recentlyDeletedByMe.get(deletedByMeKey(dialogId, messageId));
            if (time == null) {
                return false;
            }
            if (System.currentTimeMillis() - time > DELETED_BY_ME_TTL_MS) {
                recentlyDeletedByMe.remove(deletedByMeKey(dialogId, messageId));
                return false;
            }
            return true;
        }
    }

    private static long deletedByMeKey(long dialogId, int messageId) {
        return (dialogId << 32) ^ (messageId & 0xffffffffL);
    }

    // ---- snapshot (runs on the MessagesStorage storage queue) ------------------

    // Called from the hook in MessagesController.processUpdateArray, posted
    // to the same serial storage queue immediately ahead of the stock
    // deletion runnable, so the rows in messages_v2 / messages_topics are
    // still present. dialogKey is 0 for TL_updateDeleteMessages (dialog is
    // resolved per row, exactly like markMessagesAsDeletedInternal) or
    // -channel_id for TL_updateDeleteChannelMessages.
    public void snapshotBeforeDelete(long dialogKey, ArrayList<Integer> messageIds) {
        if (!isEnabled() || messageIds == null || messageIds.isEmpty()) {
            return;
        }
        SQLiteDatabase database = getMessagesStorage().getDatabase();
        if (database == null) {
            return;
        }
        String ids = TextUtils.join(",", messageIds);
        SQLiteCursor cursor = null;
        try {
            if (dialogKey != 0) {
                cursor = database.queryFinalized(String.format(java.util.Locale.US, "SELECT uid, data, read_state, out, mention, mid FROM messages_v2 WHERE mid IN(%s) AND uid = %d", ids, dialogKey));
            } else {
                cursor = database.queryFinalized(String.format(java.util.Locale.US, "SELECT uid, data, read_state, out, mention, mid FROM messages_v2 WHERE mid IN(%s) AND is_channel = 0", ids));
            }
            snapshotCursorRows(cursor);
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
        // Forum-topic messages live in a separate table; the stock deletion
        // scans it too, so the snapshot must as well.
        cursor = null;
        try {
            if (dialogKey != 0) {
                cursor = database.queryFinalized(String.format(java.util.Locale.US, "SELECT uid, data, read_state, out, mention, mid FROM messages_topics WHERE mid IN(%s) AND uid = %d", ids, dialogKey));
            } else {
                cursor = database.queryFinalized(String.format(java.util.Locale.US, "SELECT uid, data, read_state, out, mention, mid FROM messages_topics WHERE mid IN(%s) AND uid > 0", ids));
            }
            snapshotCursorRows(cursor);
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
        enforceCap();
    }

    private void snapshotCursorRows(SQLiteCursor cursor) throws Exception {
        if (cursor == null) {
            return;
        }
        final long selfId = getUserConfig().getClientUserId();
        while (cursor.next()) {
            long dialogId = cursor.longValue(0);
            int messageId = cursor.intValue(5);
            // Per-row hard exclusions (defense in depth — the opt-in check
            // already refuses these, but a row must never be retained for a
            // secret chat or the self dialog whatever the set contains).
            if (DialogObject.isEncryptedDialog(dialogId) || dialogId == selfId) {
                continue;
            }
            if (!isOptedIn(dialogId)) {
                continue;
            }
            if (wasDeletedByMe(dialogId, messageId)) {
                continue;
            }
            NativeByteBuffer data = cursor.byteBufferValue(1);
            if (data == null) {
                continue;
            }
            try {
                // Keep the serialized blob verbatim, before deserializing
                // consumes the buffer.
                byte[] blob = null;
                if (data.buffer != null && data.buffer.limit() > 0) {
                    blob = new byte[data.buffer.limit()];
                    data.buffer.position(0);
                    data.buffer.get(blob);
                    data.buffer.position(0);
                }
                TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                if (message != null) {
                    message.readAttachPath(data, selfId);
                }
                if (message == null || blob == null) {
                    continue;
                }
                // Service events (joins, pins, title changes) and empty
                // placeholders are not user content — never retained.
                if (message instanceof TLRPC.TL_messageService || message instanceof TLRPC.TL_messageEmpty || message.action != null) {
                    continue;
                }
                // Self-destruct / auto-delete content is never retained,
                // even though its expiry arrives through this same path.
                if (message.ttl_period != 0) {
                    continue;
                }
                TLRPC.MessageMedia media = message.media;
                if (media != null && media.ttl_seconds != 0) {
                    continue;
                }
                retainMessage(dialogId, messageId, message, media, blob);
            } finally {
                data.reuse();
            }
        }
    }

    private void retainMessage(long dialogId, int messageId, TLRPC.Message message, TLRPC.MessageMedia media, byte[] blob) {
        DeletedMessagesStorage.Entry entry = new DeletedMessagesStorage.Entry();
        entry.dialogId = dialogId;
        entry.messageId = messageId;
        entry.date = message.date;
        entry.fromId = message.from_id != null ? DialogObject.getPeerDialogId(message.from_id) : dialogId;
        entry.out = message.out;
        entry.text = message.message != null ? message.message : "";
        entry.mediaKind = DeletedMessagesStorage.MEDIA_NONE;
        entry.retainedAt = System.currentTimeMillis();

        if (media instanceof TLRPC.TL_messageMediaPhoto) {
            entry.mediaKind = DeletedMessagesStorage.MEDIA_PHOTO;
            entry.mediaName = "photo.jpg";
            if (media.photo != null && media.photo.sizes != null) {
                long max = 0;
                for (int i = 0, size = media.photo.sizes.size(); i < size; i++) {
                    TLRPC.PhotoSize photoSize = media.photo.sizes.get(i);
                    if (photoSize != null && photoSize.size > max) {
                        max = photoSize.size;
                    }
                }
                entry.mediaTotalSize = max;
            }
        } else if (media instanceof TLRPC.TL_messageMediaDocument && media.document != null) {
            entry.mediaKind = DeletedMessagesStorage.MEDIA_DOCUMENT;
            entry.mediaName = FileLoader.getDocumentFileName(media.document);
            entry.mediaTotalSize = media.document.size;
        } else if (media != null) {
            entry.mediaKind = DeletedMessagesStorage.MEDIA_OTHER;
        }

        // Copy the media file only when it is already fully downloaded and
        // fits the cap; a download is never started for a deleted message.
        if ((entry.mediaKind == DeletedMessagesStorage.MEDIA_PHOTO || entry.mediaKind == DeletedMessagesStorage.MEDIA_DOCUMENT)
                && SharedConfig.forkDeletedMessagesCapMb > 0) {
            copyMediaIfDownloaded(entry, message);
        }
        storage.putRetained(entry, blob);
    }

    private void copyMediaIfDownloaded(DeletedMessagesStorage.Entry entry, TLRPC.Message message) {
        try {
            File source = getFileLoader().getPathToMessage(message);
            if (source == null || !source.exists() || source.length() <= 0) {
                return;
            }
            // A partial file (still downloading when the message was
            // deleted) does not count as downloaded.
            if (entry.mediaKind == DeletedMessagesStorage.MEDIA_DOCUMENT && entry.mediaTotalSize > 0 && source.length() != entry.mediaTotalSize) {
                return;
            }
            long capBytes = SharedConfig.forkDeletedMessagesCapMb * 1024L * 1024L;
            if (source.length() > capBytes) {
                return; // can never fit the cap
            }
            File vaultDir = DeletedMessagesStorage.getVaultMediaDir(currentAccount);
            File dialogDir = new File(vaultDir, Long.toString(entry.dialogId));
            if (!dialogDir.exists()) {
                dialogDir.mkdirs();
            }
            String fileName = entry.messageId + "_" + sanitizeFileName(entry.mediaName);
            File dest = new File(dialogDir, fileName);
            if (copyFile(source, dest)) {
                entry.mediaPath = entry.dialogId + "/" + fileName;
                entry.mediaSize = dest.length();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    // ---- cap enforcement ---------------------------------------------------------

    private void enforceCap() {
        long capBytes = SharedConfig.forkDeletedMessagesCapMb * 1024L * 1024L;
        int guard = 0;
        while (storage.getTotalMediaSize() > capBytes && guard++ < 10000) {
            DeletedMessagesStorage.Entry oldest = storage.getOldestMediaEntry();
            if (oldest == null) {
                break;
            }
            String path = storage.deleteRetained(oldest.dialogId, oldest.messageId);
            deleteVaultFile(path);
        }
    }

    // Re-runs cap eviction off the caller's thread (e.g. after the cap was
    // lowered in settings, between snapshot batches).
    public void enforceCapAsync() {
        queue.postRunnable(this::enforceCap);
    }

    // ---- purge / read APIs ---------------------------------------------------------

    // Synchronous purge of one dialog's retained copies (rows + files).
    // Callers run this off the UI thread (storage queue or the fork queue).
    public void purgeDialog(long dialogId) {
        ArrayList<String> paths = storage.purgeDialog(dialogId);
        for (int i = 0, size = paths.size(); i < size; i++) {
            deleteVaultFile(paths.get(i));
        }
    }

    public void purgeAll(final Runnable onDone) {
        queue.postRunnable(() -> {
            ArrayList<String> paths = storage.purgeAll();
            for (int i = 0, size = paths.size(); i < size; i++) {
                deleteVaultFile(paths.get(i));
            }
            // Remove any leftover files (e.g. from rows evicted earlier).
            deleteRecursive(DeletedMessagesStorage.getVaultMediaDir(currentAccount));
            if (onDone != null) {
                AndroidUtilities.runOnUIThread(onDone);
            }
        });
    }

    public ArrayList<DeletedMessagesStorage.Entry> getRetainedMessages(long dialogId) {
        return storage.getRetainedMessages(dialogId);
    }

    public void deleteRetained(final long dialogId, final int messageId, final Runnable onDone) {
        queue.postRunnable(() -> {
            String path = storage.deleteRetained(dialogId, messageId);
            deleteVaultFile(path);
            if (onDone != null) {
                AndroidUtilities.runOnUIThread(onDone);
            }
        });
    }

    public int getTotalCount() {
        return storage.getTotalCount();
    }

    public long getTotalMediaSize() {
        return storage.getTotalMediaSize();
    }

    // ---- file helpers ----------------------------------------------------------------

    private void deleteVaultFile(String relativePath) {
        if (relativePath == null) {
            return;
        }
        try {
            File file = new File(DeletedMessagesStorage.getVaultMediaDir(currentAccount), relativePath);
            if (file.exists()) {
                file.delete();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (int i = 0; i < children.length; i++) {
                    deleteRecursive(children[i]);
                }
            }
        }
        file.delete();
    }

    private static String sanitizeFileName(String name) {
        if (name == null || name.length() == 0) {
            return "file";
        }
        String cleaned = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (cleaned.length() > 80) {
            cleaned = cleaned.substring(cleaned.length() - 80);
        }
        return cleaned;
    }

    private static boolean copyFile(File source, File dest) {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(source);
            out = new FileOutputStream(dest);
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
            return dest.length() == source.length();
        } catch (Exception e) {
            FileLog.e(e);
            if (dest.exists()) {
                dest.delete();
            }
            return false;
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Exception ignore) {
            }
            try {
                if (out != null) {
                    out.close();
                }
            } catch (Exception ignore) {
            }
        }
    }
}
