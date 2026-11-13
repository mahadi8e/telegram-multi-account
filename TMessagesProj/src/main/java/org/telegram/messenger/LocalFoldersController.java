/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 *
 * TG fork: controller for device-only ("local") chat folders and pins.
 *
 * Local folders behave like an extra, purely local layer next to Telegram's
 * server-synced dialog filters:
 *  - membership is explicit (a chat is in exactly the local folders the user
 *    put it in) and stored in a separate per-account SQLite database;
 *  - local pins only affect the ordering inside local folder pages and never
 *    touch TLRPC.Dialog.pinned / pinnedNum or any server pin state;
 *  - this class contains no server requests of any kind.
 *
 * In the dialogs tab strip, local folders use a negative tab-id namespace
 * (tabId = -folderId - 2) so they can never collide with the positional
 * server-filter tab ids (0..N-1) used by DialogsActivity/FilterTabsView.
 */

package org.telegram.messenger;

import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;

public class LocalFoldersController extends BaseController {

    private static volatile LocalFoldersController[] Instance = new LocalFoldersController[UserConfig.MAX_ACCOUNT_COUNT];

    public static LocalFoldersController getInstance(int num) {
        LocalFoldersController localInstance = Instance[num];
        if (localInstance == null) {
            synchronized (LocalFoldersController.class) {
                localInstance = Instance[num];
                if (localInstance == null) {
                    Instance[num] = localInstance = new LocalFoldersController(num);
                }
            }
        }
        return localInstance;
    }

    // Tab ids of local folders in DialogsActivity/FilterTabsView. Server tabs
    // use positional ids >= 0 and -1 means "no tab", hence the -2 offset.
    public static boolean isLocalTabId(int tabId) {
        return tabId <= -3;
    }

    public static int toTabId(int folderId) {
        return -folderId - 2;
    }

    public static int fromTabId(int tabId) {
        return -tabId - 2;
    }

    private final LocalFoldersStorage storage;
    private final ArrayList<LocalFolder> folders = new ArrayList<>();
    // Locally pinned dialog ids, most recently pinned first.
    private final ArrayList<Long> pinnedDialogs = new ArrayList<>();
    private boolean loaded;

    public LocalFoldersController(int num) {
        super(num);
        storage = new LocalFoldersStorage(num);
    }

    private void ensureLoaded() {
        if (!loaded) {
            loaded = true;
            storage.loadAll(folders, pinnedDialogs);
        }
    }

    private void notifyUpdated() {
        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.localFoldersUpdated);
    }

    // ---- folders -------------------------------------------------------

    public synchronized ArrayList<LocalFolder> getFolders() {
        ensureLoaded();
        return new ArrayList<>(folders);
    }

    public synchronized LocalFolder getFolder(int folderId) {
        ensureLoaded();
        for (int i = 0; i < folders.size(); i++) {
            if (folders.get(i).id == folderId) {
                return folders.get(i);
            }
        }
        return null;
    }

    public synchronized LocalFolder getFolderByTabId(int tabId) {
        if (!isLocalTabId(tabId)) {
            return null;
        }
        return getFolder(fromTabId(tabId));
    }

    public synchronized LocalFolder createFolder(String name) {
        ensureLoaded();
        int order = folders.size();
        int id = storage.insertFolder(name, order);
        if (id <= 0) {
            return null;
        }
        LocalFolder folder = new LocalFolder(id, name, order);
        folders.add(folder);
        notifyUpdated();
        return folder;
    }

    public synchronized void renameFolder(int folderId, String name) {
        LocalFolder folder = getFolder(folderId);
        if (folder == null) {
            return;
        }
        folder.name = name;
        storage.updateFolderName(folderId, name);
        notifyUpdated();
    }

    public synchronized void deleteFolder(int folderId) {
        LocalFolder folder = getFolder(folderId);
        if (folder == null) {
            return;
        }
        folders.remove(folder);
        storage.deleteFolder(folderId);
        // Re-normalize order values after removal.
        for (int i = 0; i < folders.size(); i++) {
            if (folders.get(i).order != i) {
                folders.get(i).order = i;
                storage.updateFolderOrder(folders.get(i).id, i);
            }
        }
        notifyUpdated();
    }

    // Applies a complete new folder order (used after drag-reordering in the
    // setup screen) and persists it.
    public synchronized void setFoldersOrder(ArrayList<LocalFolder> orderedFolders) {
        ensureLoaded();
        if (orderedFolders == null || orderedFolders.size() != folders.size()) {
            return;
        }
        folders.clear();
        folders.addAll(orderedFolders);
        for (int i = 0; i < folders.size(); i++) {
            if (folders.get(i).order != i) {
                folders.get(i).order = i;
                storage.updateFolderOrder(folders.get(i).id, i);
            }
        }
        notifyUpdated();
    }

    // Moves the folder at fromPosition to toPosition in the folder list and
    // persists the new order. Positions are indices into getFolders().
    public synchronized void moveFolder(int fromPosition, int toPosition) {
        ensureLoaded();
        if (fromPosition < 0 || toPosition < 0 || fromPosition >= folders.size() || toPosition >= folders.size() || fromPosition == toPosition) {
            return;
        }
        LocalFolder folder = folders.remove(fromPosition);
        folders.add(toPosition, folder);
        for (int i = 0; i < folders.size(); i++) {
            if (folders.get(i).order != i) {
                folders.get(i).order = i;
                storage.updateFolderOrder(folders.get(i).id, i);
            }
        }
        notifyUpdated();
    }

    // ---- folder membership ----------------------------------------------

    public synchronized void setFolderChats(int folderId, ArrayList<Long> dialogIds) {
        LocalFolder folder = getFolder(folderId);
        if (folder == null) {
            return;
        }
        folder.dialogIds.clear();
        if (dialogIds != null) {
            folder.dialogIds.addAll(dialogIds);
        }
        storage.replaceFolderChats(folderId, folder.dialogIds);
        notifyUpdated();
    }

    public synchronized void addDialogToFolder(int folderId, long dialogId) {
        LocalFolder folder = getFolder(folderId);
        if (folder == null || folder.dialogIds.contains(dialogId)) {
            return;
        }
        folder.dialogIds.add(dialogId);
        storage.addFolderChat(folderId, dialogId, folder.dialogIds.size() - 1);
        notifyUpdated();
    }

    public synchronized void removeDialogFromFolder(int folderId, long dialogId) {
        LocalFolder folder = getFolder(folderId);
        if (folder == null) {
            return;
        }
        if (folder.dialogIds.remove(dialogId)) {
            storage.removeFolderChat(folderId, dialogId);
            notifyUpdated();
        }
    }

    // ---- local pins -------------------------------------------------------
    // Local pins are per-account (not per-folder) and only reorder dialogs
    // inside local folder pages. They never modify server pin state.

    public synchronized boolean isPinnedLocally(long dialogId) {
        ensureLoaded();
        return pinnedDialogs.contains(dialogId);
    }

    public synchronized void setDialogPinnedLocally(long dialogId, boolean pin) {
        ensureLoaded();
        boolean changed;
        if (pin) {
            pinnedDialogs.remove(dialogId);
            pinnedDialogs.add(0, dialogId);
            changed = true;
        } else {
            changed = pinnedDialogs.remove(dialogId);
        }
        if (changed) {
            storage.replacePins(pinnedDialogs);
            notifyUpdated();
        }
    }

    private int getLocalPinIndex(long dialogId) {
        return pinnedDialogs.indexOf(dialogId);
    }

    // ---- page list building ----------------------------------------------
    // Builds the dialog list for a local folder page out of all dialogs:
    // members only, locally pinned dialogs first (in local pin order), the
    // rest in their original (date) order. Works on a new list; the passed
    // dialogs and their shared TLRPC.Dialog objects are never mutated.

    public synchronized ArrayList<TLRPC.Dialog> buildFolderDialogs(LocalFolder folder, ArrayList<TLRPC.Dialog> allDialogs) {
        ArrayList<TLRPC.Dialog> result = new ArrayList<>();
        if (folder == null || allDialogs == null) {
            return result;
        }
        ensureLoaded();
        HashSet<Long> members = new HashSet<>(folder.dialogIds);
        for (int i = 0; i < allDialogs.size(); i++) {
            TLRPC.Dialog dialog = allDialogs.get(i);
            if (dialog != null && members.contains(dialog.id)) {
                result.add(dialog);
            }
        }
        // Stable sort: locally pinned first by pin order, others keep order.
        Collections.sort(result, (dialog1, dialog2) -> {
            int index1 = getLocalPinIndex(dialog1.id);
            int index2 = getLocalPinIndex(dialog2.id);
            int order1 = index1 >= 0 ? index1 : Integer.MAX_VALUE;
            int order2 = index2 >= 0 ? index2 : Integer.MAX_VALUE;
            if (order1 != order2) {
                return order1 < order2 ? -1 : 1;
            }
            return 0;
        });
        return result;
    }

    // Unread counter for a local folder tab: sums unread counts of member
    // dialogs that still exist locally; muted dialogs do not contribute,
    // mirroring the server-filter unread bookkeeping.
    public synchronized int getFolderUnreadCount(int tabId) {
        LocalFolder folder = getFolderByTabId(tabId);
        if (folder == null) {
            return 0;
        }
        MessagesController messagesController = getMessagesController();
        int count = 0;
        for (int i = 0; i < folder.dialogIds.size(); i++) {
            long dialogId = folder.dialogIds.get(i);
            TLRPC.Dialog dialog = messagesController.dialogs_dict.get(dialogId);
            if (dialog != null && dialog.unread_count > 0 && !messagesController.isDialogMuted(dialogId, 0)) {
                count += dialog.unread_count;
            }
        }
        return count;
    }
}
