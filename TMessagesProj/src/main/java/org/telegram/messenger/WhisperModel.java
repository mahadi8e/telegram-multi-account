/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 *
 * TG fork: storage and one-time download of the Whisper models used by
 * the on-device speech-to-text engine (Feature 3). Three sizes of the
 * multilingual whisper.cpp model, quantized to q5_1, are offered —
 * Tiny, Base and Small — published in the upstream ggerganov/whisper.cpp
 * Hugging Face repository; the user picks one in the fork voice-to-text
 * settings (SharedConfig.forkSttWhisperModel, Base by default). A model
 * is downloaded only when the user explicitly taps its row, stored in
 * the app's private files directory, and verified by exact size and the
 * ggml magic before use. Nothing is downloaded silently and no audio
 * ever leaves the device for this engine.
 */

package org.telegram.messenger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class WhisperModel {

    private static final String MODEL_URL_PREFIX = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/";
    // Indexed by the SharedConfig.FORK_STT_MODEL_* constants.
    private static final String[] MODEL_FILE_NAMES = new String[]{"ggml-tiny-q5_1.bin", "ggml-base-q5_1.bin", "ggml-small-q5_1.bin"};
    // Exact sizes of the files in the upstream repository; a downloaded
    // file that does not match its size byte-for-byte (and carry the
    // ggml magic) is rejected and deleted.
    private static final long[] MODEL_SIZES = new long[]{32152673L, 59707625L, 190085487L};

    public interface DownloadCallback {
        void onProgress(long downloaded, long total);

        void onDone(boolean success);
    }

    private static volatile boolean downloading;
    private static volatile int downloadingModel = -1;

    public static long size(int model) {
        return MODEL_SIZES[model];
    }

    // The user's selected model, clamped to the catalog (a stale or
    // corrupt preference falls back to Base).
    public static int getSelectedModel() {
        int model = SharedConfig.forkSttWhisperModel;
        return model >= 0 && model < MODEL_FILE_NAMES.length ? model : SharedConfig.FORK_STT_MODEL_BASE;
    }

    public static File getModelFile(int model) {
        return new File(ApplicationLoader.getFilesDirFixed(), "whisper/" + MODEL_FILE_NAMES[model]);
    }

    public static File getSelectedModelFile() {
        return getModelFile(getSelectedModel());
    }

    public static boolean isDownloaded(int model) {
        File file = getModelFile(model);
        return file.exists() && file.length() == MODEL_SIZES[model];
    }

    public static boolean isSelectedDownloaded() {
        return isDownloaded(getSelectedModel());
    }

    public static boolean isDownloading() {
        return downloading;
    }

    public static int getDownloadingModel() {
        return downloadingModel;
    }

    public static boolean delete(int model) {
        File file = getModelFile(model);
        return !file.exists() || file.delete();
    }

    // Blocking download; MUST be called on a background thread. The
    // callback's onDone fires on that same thread. Only one download
    // runs at a time.
    public static void download(final int model, DownloadCallback callback) {
        if (downloading) {
            if (callback != null) {
                callback.onDone(false);
            }
            return;
        }
        downloading = true;
        downloadingModel = model;
        HttpURLConnection connection = null;
        File dest = getModelFile(model);
        File tmp = new File(dest.getParentFile(), MODEL_FILE_NAMES[model] + ".tmp");
        try {
            File dir = dest.getParentFile();
            if (dir != null && !dir.exists()) {
                dir.mkdirs();
            }
            connection = (HttpURLConnection) new URL(MODEL_URL_PREFIX + MODEL_FILE_NAMES[model]).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true);
            connection.connect();
            int code = connection.getResponseCode();
            if (code != 200) {
                throw new IOException("HTTP " + code);
            }
            InputStream in = connection.getInputStream();
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                long lastProgress = 0;
                int count;
                while ((count = in.read(buffer)) != -1) {
                    out.write(buffer, 0, count);
                    total += count;
                    if (callback != null && total - lastProgress >= 512 * 1024) {
                        lastProgress = total;
                        callback.onProgress(total, MODEL_SIZES[model]);
                    }
                }
                out.flush();
            } finally {
                try {
                    out.close();
                } catch (Exception ignore) {
                }
                try {
                    in.close();
                } catch (Exception ignore) {
                }
            }
            if (tmp.length() != MODEL_SIZES[model] || !hasGgmlMagic(tmp)) {
                throw new IOException("downloaded model failed verification");
            }
            if (dest.exists()) {
                dest.delete();
            }
            if (!tmp.renameTo(dest)) {
                throw new IOException("could not move the model into place");
            }
            if (callback != null) {
                callback.onDone(true);
            }
        } catch (Exception e) {
            FileLog.e(e);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            if (callback != null) {
                callback.onDone(false);
            }
        } finally {
            downloading = false;
            downloadingModel = -1;
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    // ggml model files start with the little-endian uint32 magic
    // 0x67676d6c, i.e. the bytes 'l', 'm', 'g', 'g'.
    private static boolean hasGgmlMagic(File file) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            byte[] magic = new byte[4];
            return in.read(magic) == 4 && magic[0] == 'l' && magic[1] == 'm' && magic[2] == 'g' && magic[3] == 'g';
        } catch (Exception e) {
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignore) {
                }
            }
        }
    }
}
