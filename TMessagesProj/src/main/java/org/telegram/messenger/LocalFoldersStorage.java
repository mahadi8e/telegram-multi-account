/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 *
 * TG fork: storage for device-only ("local") folders and pins. Uses a small
 * dedicated SQLite database file inside the account's own files directory
 * (same per-account layout as MessagesStorage's cache4.db), so local folder
 * data of different accounts never mixes and never touches the Telegram
 * server or the main message database. Only dialog ids and folder names are
 * stored here — never message content.
 */

package org.telegram.messenger;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.File;
import java.util.ArrayList;

public class LocalFoldersStorage extends SQLiteOpenHelper {

    private static final int DATABASE_VERSION = 1;
    private static final String DATABASE_NAME = "localfolders.db";

    private static final String TABLE_FOLDERS = "local_folders";
    private static final String TABLE_FOLDER_CHATS = "local_folder_chats";
    private static final String TABLE_PINS = "local_pins";

    public LocalFoldersStorage(int account) {
        super(ApplicationLoader.applicationContext, getDatabaseFile(account).getAbsolutePath(), null, DATABASE_VERSION);
    }

    private static File getDatabaseFile(int account) {
        // Same per-account files layout as MessagesStorage (account<N> subdir).
        File filesDir = ApplicationLoader.getFilesDirFixed();
        if (account != 0) {
            filesDir = new File(filesDir, "account" + account + "/");
            filesDir.mkdirs();
        }
        return new File(filesDir, DATABASE_NAME);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_FOLDERS + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "name TEXT, " +
                "sort_order INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE " + TABLE_FOLDER_CHATS + " (" +
                "folder_id INTEGER, " +
                "dialog_id INTEGER, " +
                "position INTEGER DEFAULT 0, " +
                "PRIMARY KEY(folder_id, dialog_id))");
        db.execSQL("CREATE TABLE " + TABLE_PINS + " (" +
                "dialog_id INTEGER PRIMARY KEY, " +
                "pin_order INTEGER DEFAULT 0)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Only version 1 exists so far; nothing to migrate.
    }

    // ---- loading -------------------------------------------------------

    public synchronized void loadAll(ArrayList<LocalFolder> outFolders, ArrayList<Long> outPins) {
        outFolders.clear();
        outPins.clear();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_FOLDERS, new String[]{"id", "name", "sort_order"}, null, null, null, null, "sort_order ASC, id ASC");
            while (cursor.moveToNext()) {
                LocalFolder folder = new LocalFolder(cursor.getInt(0), cursor.getString(1), cursor.getInt(2));
                outFolders.add(folder);
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        for (int i = 0; i < outFolders.size(); i++) {
            loadFolderChats(db, outFolders.get(i));
        }
        cursor = null;
        try {
            cursor = db.query(TABLE_PINS, new String[]{"dialog_id"}, null, null, null, null, "pin_order ASC");
            while (cursor.moveToNext()) {
                outPins.add(cursor.getLong(0));
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private void loadFolderChats(SQLiteDatabase db, LocalFolder folder) {
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_FOLDER_CHATS, new String[]{"dialog_id"}, "folder_id = ?", new String[]{Integer.toString(folder.id)}, null, null, "position ASC, dialog_id ASC");
            while (cursor.moveToNext()) {
                folder.dialogIds.add(cursor.getLong(0));
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    // ---- folders -------------------------------------------------------

    public synchronized int insertFolder(String name, int order) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("name", name);
        values.put("sort_order", order);
        long id = db.insert(TABLE_FOLDERS, null, values);
        return (int) id;
    }

    public synchronized void updateFolderName(int folderId, String name) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("name", name);
        db.update(TABLE_FOLDERS, values, "id = ?", new String[]{Integer.toString(folderId)});
    }

    public synchronized void updateFolderOrder(int folderId, int order) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("sort_order", order);
        db.update(TABLE_FOLDERS, values, "id = ?", new String[]{Integer.toString(folderId)});
    }

    public synchronized void deleteFolder(int folderId) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_FOLDER_CHATS, "folder_id = ?", new String[]{Integer.toString(folderId)});
        db.delete(TABLE_FOLDERS, "id = ?", new String[]{Integer.toString(folderId)});
    }

    // ---- folder membership ----------------------------------------------

    public synchronized void replaceFolderChats(int folderId, ArrayList<Long> dialogIds) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(TABLE_FOLDER_CHATS, "folder_id = ?", new String[]{Integer.toString(folderId)});
            ContentValues values = new ContentValues();
            for (int i = 0; i < dialogIds.size(); i++) {
                values.clear();
                values.put("folder_id", folderId);
                values.put("dialog_id", dialogIds.get(i));
                values.put("position", i);
                db.insertWithOnConflict(TABLE_FOLDER_CHATS, null, values, SQLiteDatabase.CONFLICT_IGNORE);
            }
            db.setTransactionSuccessful();
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            db.endTransaction();
        }
    }

    public synchronized void addFolderChat(int folderId, long dialogId, int position) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("folder_id", folderId);
        values.put("dialog_id", dialogId);
        values.put("position", position);
        db.insertWithOnConflict(TABLE_FOLDER_CHATS, null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public synchronized void removeFolderChat(int folderId, long dialogId) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_FOLDER_CHATS, "folder_id = ? AND dialog_id = ?", new String[]{Integer.toString(folderId), Long.toString(dialogId)});
    }

    // ---- local pins -------------------------------------------------------

    public synchronized void replacePins(ArrayList<Long> pinnedDialogIds) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(TABLE_PINS, null, null);
            ContentValues values = new ContentValues();
            for (int i = 0; i < pinnedDialogIds.size(); i++) {
                values.clear();
                values.put("dialog_id", pinnedDialogIds.get(i));
                values.put("pin_order", i);
                db.insertWithOnConflict(TABLE_PINS, null, values, SQLiteDatabase.CONFLICT_IGNORE);
            }
            db.setTransactionSuccessful();
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            db.endTransaction();
        }
    }
}
