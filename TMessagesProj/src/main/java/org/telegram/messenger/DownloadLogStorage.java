/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 *
 * TG fork: per-file download speed log (Feature 6). Stock Telegram keeps
 * only aggregate byte counters and a volatile global speed estimate, so
 * there is no way to check what an individual download actually achieved.
 * This store records one row per finished or failed download — file name,
 * size, duration and average speed — in a small dedicated SQLite database
 * inside the account's own files directory. It is purely observational:
 * recording never changes any request behaviour, and nothing ever leaves
 * the device. At most the newest 500 rows are kept.
 */

package org.telegram.messenger;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.text.TextUtils;

import java.io.File;
import java.util.ArrayList;

public class DownloadLogStorage extends SQLiteOpenHelper {

    private static final int DATABASE_VERSION = 1;
    private static final String DATABASE_NAME = "downloadlog.db";

    private static final String TABLE_LOG = "download_log";

    private static final int MAX_ROWS = 500;
    // TG fork: a "download" that finishes faster than this did not measurably
    // transfer anything (e.g. the file was already fully cached on disk), so
    // its nominal speed would be meaningless and it is not recorded.
    private static final long MIN_MEASURED_DURATION_MS = 30;

    public static class Entry {
        public String fileName;
        public long sizeBytes;
        public long durationMs;
        public long avgBytesPerSec;
        public boolean success;
        public int reason;
        public long timestamp;
    }

    private static final DownloadLogStorage[] instances = new DownloadLogStorage[UserConfig.MAX_ACCOUNT_COUNT];

    public static synchronized DownloadLogStorage getInstance(int account) {
        DownloadLogStorage storage = instances[account];
        if (storage == null) {
            storage = new DownloadLogStorage(account);
            instances[account] = storage;
        }
        return storage;
    }

    // TG fork: single recording entry point, called from FileLoader's
    // per-operation delegate when a download finishes or fails. The duration
    // is measured from the operation's last start(), i.e. it is the active
    // download phase; a file that was paused or resumed has already fetched
    // some of its bytes earlier, so its recorded speed can look better than
    // the true end-to-end speed (the settings screen says exactly this).
    public static void record(int account, FileLoadOperation operation, String documentName, boolean success, int reason) {
        if (!SharedConfig.forkDownloadLogEnabled || operation == null) {
            return;
        }
        try {
            if (operation.isPreloadVideoOperation()) {
                return;
            }
            long sizeBytes = operation.totalBytesCount;
            if (sizeBytes <= 0) {
                return;
            }
            long startTime = operation.getStartTime();
            if (startTime <= 0) {
                return;
            }
            long durationMs = System.currentTimeMillis() - startTime;
            if (durationMs < 0 || (success && durationMs < MIN_MEASURED_DURATION_MS)) {
                return;
            }
            String fileName = !TextUtils.isEmpty(documentName) ? documentName : operation.getFileName();
            getInstance(account).insert(fileName, sizeBytes, durationMs, success, reason);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public DownloadLogStorage(int account) {
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
        db.execSQL("CREATE TABLE " + TABLE_LOG + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "ts INTEGER, " +
                "file_name TEXT, " +
                "size_bytes INTEGER, " +
                "duration_ms INTEGER, " +
                "avg_bytes_per_sec INTEGER, " +
                "success INTEGER, " +
                "reason INTEGER)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Only version 1 exists so far; nothing to migrate.
    }

    public synchronized void insert(String fileName, long sizeBytes, long durationMs, boolean success, int reason) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put("ts", System.currentTimeMillis());
            values.put("file_name", fileName == null ? "" : fileName);
            values.put("size_bytes", sizeBytes);
            values.put("duration_ms", durationMs);
            values.put("avg_bytes_per_sec", durationMs > 0 ? sizeBytes * 1000 / durationMs : 0);
            values.put("success", success ? 1 : 0);
            values.put("reason", reason);
            db.insert(TABLE_LOG, null, values);
            // Keep only the newest MAX_ROWS rows.
            db.execSQL("DELETE FROM " + TABLE_LOG + " WHERE id NOT IN (SELECT id FROM " + TABLE_LOG + " ORDER BY id DESC LIMIT " + MAX_ROWS + ")");
            db.setTransactionSuccessful();
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            db.endTransaction();
        }
    }

    public synchronized ArrayList<Entry> loadRecent(int limit) {
        ArrayList<Entry> result = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_LOG, new String[]{"ts", "file_name", "size_bytes", "duration_ms", "avg_bytes_per_sec", "success", "reason"},
                    null, null, null, null, "id DESC", Integer.toString(limit));
            while (cursor.moveToNext()) {
                Entry entry = new Entry();
                entry.timestamp = cursor.getLong(0);
                entry.fileName = cursor.getString(1);
                entry.sizeBytes = cursor.getLong(2);
                entry.durationMs = cursor.getLong(3);
                entry.avgBytesPerSec = cursor.getLong(4);
                entry.success = cursor.getInt(5) != 0;
                entry.reason = cursor.getInt(6);
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

    // Returns {count, totalBytes, totalDurationMs} over successful downloads,
    // for the viewer's average-speed header (totals / total time, not a mean
    // of per-file speeds, so big files weigh accordingly).
    public synchronized long[] getSuccessTotals() {
        long[] totals = new long[3];
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT COUNT(*), COALESCE(SUM(size_bytes), 0), COALESCE(SUM(duration_ms), 0) FROM " + TABLE_LOG + " WHERE success = 1", null);
            if (cursor.moveToFirst()) {
                totals[0] = cursor.getLong(0);
                totals[1] = cursor.getLong(1);
                totals[2] = cursor.getLong(2);
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return totals;
    }

    public synchronized void clear() {
        try {
            getWritableDatabase().delete(TABLE_LOG, null, null);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
