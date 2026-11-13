/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 *
 * TG fork: persistent cache for the fork translation engines (Feature 2).
 * Stock Telegram keeps translations in memory only; this fork stores them
 * in a small dedicated SQLite database inside the account's own files
 * directory (same per-account layout as the local folders DB), keyed by
 * (dialog, message, target language, engine) plus a hash of the source
 * text, so editing a message naturally invalidates its cached translation.
 * Only plain translated text is stored. The same file also persists the
 * fork's per-chat auto-translate set. Nothing here talks to the server.
 */

package org.telegram.messenger;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.File;
import java.util.HashMap;

public class TranslationsStorage extends SQLiteOpenHelper {

    private static final int DATABASE_VERSION = 1;
    private static final String DATABASE_NAME = "translations.db";

    private static final String TABLE_TRANSLATIONS = "translations";
    private static final String TABLE_AUTO_DIALOGS = "auto_dialogs";

    // Cached translations older than this are dropped on first use.
    private static final long MAX_CACHE_AGE_MS = 30L * 24 * 60 * 60 * 1000;

    public TranslationsStorage(int account) {
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
        db.execSQL("CREATE TABLE " + TABLE_TRANSLATIONS + " (" +
                "dialog_id INTEGER, " +
                "message_id INTEGER, " +
                "to_lang TEXT, " +
                "engine TEXT, " +
                "src_hash INTEGER, " +
                "translated TEXT, " +
                "created_at INTEGER, " +
                "PRIMARY KEY(dialog_id, message_id, to_lang, engine))");
        db.execSQL("CREATE TABLE " + TABLE_AUTO_DIALOGS + " (" +
                "dialog_id INTEGER PRIMARY KEY, " +
                "to_lang TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Only version 1 exists so far; nothing to migrate.
    }

    // ---- translation cache ----------------------------------------------

    // Returns the cached translation, or null on a miss. A cached row whose
    // stored source hash differs from srcHash counts as a miss, so an edited
    // message is re-translated (and the row replaced on the next put).
    public synchronized String getTranslation(long dialogId, int messageId, String toLang, String engine, int srcHash) {
        Cursor cursor = null;
        try {
            SQLiteDatabase db = getReadableDatabase();
            cursor = db.query(TABLE_TRANSLATIONS, new String[]{"translated"}, "dialog_id = ? AND message_id = ? AND to_lang = ? AND engine = ? AND src_hash = ?",
                    new String[]{Long.toString(dialogId), Integer.toString(messageId), toLang, engine, Integer.toString(srcHash)}, null, null, null);
            if (cursor.moveToFirst()) {
                return cursor.getString(0);
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

    public synchronized void putTranslation(long dialogId, int messageId, String toLang, String engine, int srcHash, String translated, long createdAt) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put("dialog_id", dialogId);
            values.put("message_id", messageId);
            values.put("to_lang", toLang);
            values.put("engine", engine);
            values.put("src_hash", srcHash);
            values.put("translated", translated);
            values.put("created_at", createdAt);
            db.insertWithOnConflict(TABLE_TRANSLATIONS, null, values, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public synchronized void pruneOldTranslations() {
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.delete(TABLE_TRANSLATIONS, "created_at < ?", new String[]{Long.toString(System.currentTimeMillis() - MAX_CACHE_AGE_MS)});
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    // ---- fork auto-translate dialogs --------------------------------------

    public synchronized void loadAutoDialogs(HashMap<Long, String> out) {
        out.clear();
        Cursor cursor = null;
        try {
            SQLiteDatabase db = getReadableDatabase();
            cursor = db.query(TABLE_AUTO_DIALOGS, new String[]{"dialog_id", "to_lang"}, null, null, null, null, null);
            while (cursor.moveToNext()) {
                out.put(cursor.getLong(0), cursor.getString(1));
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    public synchronized void setAutoDialog(long dialogId, String toLang) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            if (toLang == null) {
                db.delete(TABLE_AUTO_DIALOGS, "dialog_id = ?", new String[]{Long.toString(dialogId)});
            } else {
                ContentValues values = new ContentValues();
                values.put("dialog_id", dialogId);
                values.put("to_lang", toLang);
                db.insertWithOnConflict(TABLE_AUTO_DIALOGS, null, values, SQLiteDatabase.CONFLICT_REPLACE);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
