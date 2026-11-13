/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 *
 * TG fork: persistent cache for the fork voice-to-text engines
 * (Feature 3). Stock Telegram persists server transcriptions on the
 * message object itself; fork results must never touch that state, so
 * they live in this small dedicated SQLite database inside the
 * account's own files directory (same per-account layout and pattern
 * as TranslationsStorage), keyed by (dialog, message, engine). Voice
 * messages cannot be edited, so no source-hash invalidation is needed.
 * Nothing here talks to the server.
 */

package org.telegram.messenger;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.File;

public class TranscriptionsStorage extends SQLiteOpenHelper {

    private static final int DATABASE_VERSION = 1;
    private static final String DATABASE_NAME = "transcriptions.db";

    private static final String TABLE_TRANSCRIPTIONS = "transcriptions";

    // Cached transcriptions older than this are dropped on first use.
    private static final long MAX_CACHE_AGE_MS = 30L * 24 * 60 * 60 * 1000;

    public static class Entry {
        public String text;
        public String language;
        public int duration;
    }

    public TranscriptionsStorage(int account) {
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
        db.execSQL("CREATE TABLE " + TABLE_TRANSCRIPTIONS + " (" +
                "dialog_id INTEGER, " +
                "message_id INTEGER, " +
                "engine TEXT, " +
                "language TEXT, " +
                "text TEXT, " +
                "duration INTEGER, " +
                "created_at INTEGER, " +
                "PRIMARY KEY(dialog_id, message_id, engine))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Only version 1 exists so far; nothing to migrate.
    }

    // Returns the cached transcription, or null on a miss.
    public synchronized Entry getTranscription(long dialogId, int messageId, String engine) {
        Cursor cursor = null;
        try {
            SQLiteDatabase db = getReadableDatabase();
            cursor = db.query(TABLE_TRANSCRIPTIONS, new String[]{"text", "language", "duration"}, "dialog_id = ? AND message_id = ? AND engine = ?",
                    new String[]{Long.toString(dialogId), Integer.toString(messageId), engine}, null, null, null);
            if (cursor.moveToFirst()) {
                Entry entry = new Entry();
                entry.text = cursor.getString(0);
                entry.language = cursor.getString(1);
                entry.duration = cursor.getInt(2);
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

    public synchronized void putTranscription(long dialogId, int messageId, String engine, String language, String text, int duration, long createdAt) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put("dialog_id", dialogId);
            values.put("message_id", messageId);
            values.put("engine", engine);
            values.put("language", language);
            values.put("text", text);
            values.put("duration", duration);
            values.put("created_at", createdAt);
            db.insertWithOnConflict(TABLE_TRANSCRIPTIONS, null, values, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public synchronized void pruneOldTranscriptions() {
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.delete(TABLE_TRANSCRIPTIONS, "created_at < ?", new String[]{Long.toString(System.currentTimeMillis() - MAX_CACHE_AGE_MS)});
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
