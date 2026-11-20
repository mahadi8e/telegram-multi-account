/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 *
 * TG fork: storage and one-time download of the Whisper model used by
 * the on-device speech-to-text engine (Feature 3). The model is the
 * multilingual "base" model of whisper.cpp, quantized to q5_1
 * (59,707,625 bytes, about 57 MB), published in the upstream
 * ggerganov/whisper.cpp Hugging Face repository. It is downloaded only
 * when the user explicitly taps the download row in the fork
 * voice-to-text settings, stored in the app's private files directory,
 * and verified by exact size and the ggml magic before use. Nothing is
 * downloaded silently and no audio ever leaves the device for this
 * engine.
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

    public static final String MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin";
    public static final String MODEL_FILE_NAME = "ggml-base-q5_1.bin";
    // Exact size of ggml-base-q5_1.bin in the upstream repository; a
    // downloaded file that does not match it byte-for-byte in size (and
    // carry the ggml magic) is rejected and deleted.
    public static final long MODEL_SIZE = 59707625L;

    public interface DownloadCallback {
        void onProgress(long downloaded, long total);

        void onDone(boolean success);
    }

    private static volatile boolean downloading;

    public static File getModelFile() {
        return new File(ApplicationLoader.getFilesDirFixed(), "whisper/" + MODEL_FILE_NAME);
    }

    public static boolean isDownloaded() {
        File file = getModelFile();
        return file.exists() && file.length() == MODEL_SIZE;
    }

    public static boolean isDownloading() {
        return downloading;
    }

    public static boolean delete() {
        File file = getModelFile();
        return !file.exists() || file.delete();
    }

    // Blocking download; MUST be called on a background thread. The
    // callback's onDone fires on that same thread.
    public static void download(DownloadCallback callback) {
        if (downloading) {
            if (callback != null) {
                callback.onDone(false);
            }
            return;
        }
        downloading = true;
        HttpURLConnection connection = null;
        File tmp = new File(getModelFile().getParentFile(), MODEL_FILE_NAME + ".tmp");
        try {
            File dir = getModelFile().getParentFile();
            if (dir != null && !dir.exists()) {
                dir.mkdirs();
            }
            connection = (HttpURLConnection) new URL(MODEL_URL).openConnection();
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
                        callback.onProgress(total, MODEL_SIZE);
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
            if (tmp.length() != MODEL_SIZE || !hasGgmlMagic(tmp)) {
                throw new IOException("downloaded model failed verification");
            }
            File dest = getModelFile();
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
