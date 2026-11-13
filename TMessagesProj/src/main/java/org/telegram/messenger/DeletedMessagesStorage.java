/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 *
 * TG fork: persistent store for the deleted-message vault (Feature 5).
 * Per-account SQLite database (cloned from the TranslationsStorage
 * pattern) holding, for chats the user explicitly opted in, copies of
 * messages the other party deleted:
 *
 *  - retained_dialogs: the per-chat opt-in set;
 *  - retained_messages: the verbatim serialized TLRPC.Message blob plus
 *    denormalized preview columns (text/caption, sender, date, media
 *    metadata) and a vault-relative path to a copied media file when the
 *    media was already downloaded at deletion time.
 *
 * Media copies live in the "deleted_media/" folder next to this database,
 * inside the account's internal files directory — app sandbox only,
 * covered by the stock passcode lock, never written to shared storage and
 * never sent anywhere. Nothing here talks to the server.
 */

package org.telegram.messenger;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;

public class DeletedMessagesStorage extends SQLiteOpenHelper {

    private static final int DATABASE_VERSION = 1;
    private static final String DATABASE_NAME = "deletedmessages.db";

    private static final String TABLE_DIALOGS = "retained_dialogs";
    private static final String TABLE_MESSAGES = "retained_messages";

    public static final int MEDIA_NONE = 0;
    public static final int MEDIA_PHOTO = 1;
    public static final int MEDIA_DOCUMENT = 2;
    public static final int MEDIA_OTHER = 3;

    public static class Entry {
        public long dialogId;
        public int messageId;
        public int date;            // original message date (server time, seconds)
        public long fromId;         // peer dialog id of the sender, dialog id if unknown
        public boolean out;
        public String text;         // plain text / caption for previews
        public int mediaKind;       // MEDIA_* above
        public String mediaName;    // original file name for documents, null otherwise
        public String mediaPath;    // vault-relative copied file path, null if no copy
        public long mediaSize;      // bytes of the copied file, 0 if none
        public long mediaTotalSize; // declared media size, 0 if unknown
        public long retainedAt;     // local wall-clock ms, eviction order
    }

    public DeletedMessagesStorage(int account) {
        super(ApplicationLoader.applicationContext, getDatabaseFile(account).getAbsolutePath(), null, DATABASE_VERSION);
    }

    private static File getAccountFilesDir(int account) {
        // Same per-account files layout as MessagesStorage (account<N> subdir).
        File filesDir = ApplicationLoader.getFilesDirFixed();
        if (account != 0) {
            filesDir = new File(filesDir, "account" + account + "/");
            filesDir.mkdirs();
        }
        return filesDir;
    }

    private static File getDatabaseFile(int account) {
        return new File(getAccountFilesDir(account), DATABASE_NAME);
    }

    // TG fork: vault media directory for an account (inside its files dir).
    public static File getVaultMediaDir(int account) {
        File dir = new File(getAccountFilesDir(account), "deleted_media/");
        dir.mkdirs();
        return dir;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_DIALOGS + " (" +
                "dialog_id INTEGER PRIMARY KEY)");
        db.execSQL("CREATE TABLE " + TABLE_MESSAGES + " (" +
                "dialog_id INTEGER, " +
                "message_id INTEGER, " +
                "date INTEGER, " +
                "from_id INTEGER, " +
                "out INTEGER, " +
                "text TEXT, " +
                "media_kind INTEGER, " +
                "media_name TEXT, " +
                "media_path TEXT, " +
                "media_size INTEGER, " +
                "media_total_size INTEGER, " +
                "blob BLOB, " +
                "retained_at INTEGER, " +
                "PRIMARY KEY(dialog_id, message_id))");
        db.execSQL("CREATE INDEX idx_retained_dialog_date ON " + TABLE_MESSAGES + "(dialog_id, date)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Only version 1 exists so far; nothing to migrate.
    }

    // ---- opt-in dialogs ----------------------------------------------------

    public synchronized void loadOptedInDialogs(HashSet<Long> out) {
        out.clear();
        Cursor cursor = null;
        try {
            SQLiteDatabase db = getReadableDatabase();
            cursor = db.query(TABLE_DIALOGS, new String[]{"dialog_id"}, null, null, null, null, null);
            while (cursor.moveToNext()) {
                out.add(cursor.getLong(0));
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    public synchronized void setOptedInDialog(long dialogId, boolean optedIn) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            if (optedIn) {
                ContentValues values = new ContentValues();
                values.put("dialog_id", dialogId);
                db.insertWithOnConflict(TABLE_DIALOGS, null, values, SQLiteDatabase.CONFLICT_IGNORE);
            } else {
                db.delete(TABLE_DIALOGS, "dialog_id = ?", new String[]{Long.toString(dialogId)});
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    // ---- retained messages --------------------------------------------------

    public synchronized void putRetained(Entry entry, byte[] blob) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put("dialog_id", entry.dialogId);
            values.put("message_id", entry.messageId);
            values.put("date", entry.date);
            values.put("from_id", entry.fromId);
            values.put("out", entry.out ? 1 : 0);
            values.put("text", entry.text != null ? entry.text : "");
            values.put("media_kind", entry.mediaKind);
            values.put("media_name", entry.mediaName);
            values.put("media_path", entry.mediaPath);
            values.put("media_size", entry.mediaSize);
            values.put("media_total_size", entry.mediaTotalSize);
            values.put("blob", blob);
            values.put("retained_at", entry.retainedAt);
            db.insertWithOnConflict(TABLE_MESSAGES, null, values, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    // Newest first. The blob column is deliberately not loaded for lists.
    public synchronized ArrayList<Entry> getRetainedMessages(long dialogId) {
        ArrayList<Entry> result = new ArrayList<>();
        Cursor cursor = null;
        try {
            SQLiteDatabase db = getReadableDatabase();
            cursor = db.query(TABLE_MESSAGES,
                    new String[]{"dialog_id", "message_id", "date", "from_id", "out", "text", "media_kind", "media_name", "media_path", "media_size", "media_total_size", "retained_at"},
                    "dialog_id = ?", new String[]{Long.toString(dialogId)}, null, null, "date DESC, retained_at DESC");
            while (cursor.moveToNext()) {
                Entry entry = new Entry();
                entry.dialogId = cursor.getLong(0);
                entry.messageId = cursor.getInt(1);
                entry.date = cursor.getInt(2);
                entry.fromId = cursor.getLong(3);
                entry.out = cursor.getInt(4) != 0;
                entry.text = cursor.getString(5);
                entry.mediaKind = cursor.getInt(6);
                entry.mediaName = cursor.getString(7);
                entry.mediaPath = cursor.getString(8);
                entry.mediaSize = cursor.getLong(9);
                entry.mediaTotalSize = cursor.getLong(10);
                entry.retainedAt = cursor.getLong(11);
                result.add(entry);
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return result;
    }

    // Deletes one retained row; returns its vault-relative media path (or null).
    public synchronized String deleteRetained(long dialogId, int messageId) {
        String path = null;
        Cursor cursor = null;
        try {
            SQLiteDatabase db = getWritableDatabase();
            cursor = db.query(TABLE_MESSAGES, new String[]{"media_path"}, "dialog_id = ? AND message_id = ?",
                    new String[]{Long.toString(dialogId), Integer.toString(messageId)}, null, null, null);
            if (cursor.moveToFirst()) {
                path = cursor.getString(0);
            }
            cursor.close();
            cursor = null;
            db.delete(TABLE_MESSAGES, "dialog_id = ? AND message_id = ?",
                    new String[]{Long.toString(dialogId), Integer.toString(messageId)});
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return path;
    }

    // Deletes all retained rows of one dialog; returns their media paths so
    // the caller can remove the copied files.
    public synchronized ArrayList<String> purgeDialog(long dialogId) {
        ArrayList<String> paths = collectMediaPaths("dialog_id = ?", new String[]{Long.toString(dialogId)});
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.delete(TABLE_MESSAGES, "dialog_id = ?", new String[]{Long.toString(dialogId)});
        } catch (Exception e) {
            FileLog.e(e);
        }
        return paths;
    }

    // Deletes every retained row (all dialogs); returns all media paths.
    public synchronized ArrayList<String> purgeAll() {
        ArrayList<String> paths = collectMediaPaths(null, null);
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.delete(TABLE_MESSAGES, null, null);
        } catch (Exception e) {
            FileLog.e(e);
        }
        return paths;
    }

    private synchronized ArrayList<String> collectMediaPaths(String where, String[] args) {
        ArrayList<String> paths = new ArrayList<>();
        Cursor cursor = null;
        try {
            SQLiteDatabase db = getReadableDatabase();
            cursor = db.query(TABLE_MESSAGES, new String[]{"media_path"}, where, args, null, null, null);
            while (cursor.moveToNext()) {
                String path = cursor.getString(0);
                if (path != null) {
                    paths.add(path);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return paths;
    }

    // ---- accounting ----------------------------------------------------------

    public synchronized long getTotalMediaSize() {
        Cursor cursor = null;
        try {
            SQLiteDatabase db = getReadableDatabase();
            cursor = db.rawQuery("SELECT COALESCE(SUM(media_size), 0) FROM " + TABLE_MESSAGES, null);
            if (cursor.moveToFirst()) {
                return cursor.getLong(0);
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return 0;
    }

    public synchronized int getTotalCount() {
        Cursor cursor = null;
        try {
            SQLiteDatabase db = getReadableDatabase();
            cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_MESSAGES, null);
            if (cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return 0;
    }

    // Oldest row that actually holds copied media bytes (the byte cap is
    // effectively a media cap; text-only rows are not evicted for size).
    public synchronized Entry getOldestMediaEntry() {
        Cursor cursor = null;
        try {
            SQLiteDatabase db = getReadableDatabase();
            cursor = db.query(TABLE_MESSAGES, new String[]{"dialog_id", "message_id", "media_path", "media_size"},
                    "media_size > 0", null, null, null, "retained_at ASC", "1");
            if (cursor.moveToFirst()) {
                Entry entry = new Entry();
                entry.dialogId = cursor.getLong(0);
                entry.messageId = cursor.getInt(1);
                entry.mediaPath = cursor.getString(2);
                entry.mediaSize = cursor.getLong(3);
                return entry;
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }
}
