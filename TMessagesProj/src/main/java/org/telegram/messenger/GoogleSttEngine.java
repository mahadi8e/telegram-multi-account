/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 *
 * TG fork: cloud speech-to-text engine backed by the official Google
 * Cloud Speech-to-Text API (v1 speech:recognize, key-based) over HTTPS
 * (Feature 3). Telegram voice files are OGG/Opus, which the API accepts
 * natively (encoding OGG_OPUS), so the file content is sent base64 —
 * no local decoding is needed. The API key is the user's own, read from
 * SharedConfig, and is never logged (it travels in the request URL, so
 * the URL itself is never written to any log either).
 *
 * Note: the synchronous recognize endpoint is limited by Google to
 * roughly a minute of audio; longer voice messages return an API error,
 * which is surfaced to the user as-is.
 */

package org.telegram.messenger;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.util.Locale;

public class GoogleSttEngine extends ForkSttEngine {

    // Inline (base64) audio for the synchronous endpoint is capped by
    // Google at about 10 MB of request content.
    private static final int MAX_UPLOAD_BYTES = 10 * 1024 * 1024;

    @Override
    public String getId() {
        return ID_GOOGLE;
    }

    @Override
    public boolean isCloud() {
        return true;
    }

    @Override
    public void transcribe(File audioFile, String languageHint, Callback callback) {
        final String key = SharedConfig.forkSttGoogleKey;
        if (key == null || key.trim().length() == 0) {
            callback.onError("Google Cloud API key is not set");
            return;
        }
        try {
            // TG fork (audit G2): the size cap is checked before any
            // connection is opened, and the audio is base64-encoded and
            // streamed in chunks — previously the file, its base64 string
            // and the whole JSON body were all held in memory at once.
            if (audioFile.length() > MAX_UPLOAD_BYTES) {
                throw new IOException("Audio file is too large");
            }
            JSONObject config = new JSONObject();
            config.put("encoding", "OGG_OPUS");
            // Opus inside Ogg always decodes at 48 kHz.
            config.put("sampleRateHertz", 48000);
            config.put("languageCode", resolveLanguageCode(languageHint));
            config.put("enableAutomaticPunctuation", true);
            // The request body is {"config":<config>,"audio":{"content":
            // "<base64>"}} — the fixed prefix and suffix are written
            // around the streamed base64 content.
            final byte[] prefix = ("{\"config\":" + config.toString() + ",\"audio\":{\"content\":\"").getBytes("UTF-8");
            final byte[] suffix = "\"}}".getBytes("UTF-8");

            String url = "https://speech.googleapis.com/v1/speech:recognize?key=" + URLEncoder.encode(key.trim(), "UTF-8");
            String response = httpPostStreaming(url, "application/json; charset=utf-8", null, -1, out -> {
                out.write(prefix);
                writeFileBase64(audioFile, out);
                out.write(suffix);
            });
            JSONObject json = new JSONObject(response);
            JSONArray results = json.optJSONArray("results");
            if (results == null || results.length() == 0) {
                callback.onError("No speech recognized");
                return;
            }
            StringBuilder text = new StringBuilder();
            String language = null;
            for (int i = 0; i < results.length(); i++) {
                JSONObject result = results.getJSONObject(i);
                JSONArray alternatives = result.optJSONArray("alternatives");
                if (alternatives != null && alternatives.length() > 0) {
                    String transcript = alternatives.getJSONObject(0).optString("transcript", "").trim();
                    if (transcript.length() > 0) {
                        if (text.length() > 0) {
                            text.append(' ');
                        }
                        text.append(transcript);
                    }
                }
                if (language == null) {
                    language = result.optString("languageCode", null);
                }
            }
            if (text.length() == 0) {
                callback.onError("No speech recognized");
                return;
            }
            callback.onSuccess(text.toString(), language);
        } catch (Exception e) {
            // Deliberately no request details here: the URL contains the key.
            callback.onError("Google Speech-to-Text failed: " + e.getMessage());
        }
    }

    // TG fork (audit G2): streams the file base64-encoded (NO_WRAP)
    // without ever holding the whole file or the whole encoded string in
    // memory. Input is consumed in multiples of 3 bytes so each chunk
    // encodes independently; only a <3-byte leftover is carried between
    // reads, and the final partial group (with padding) is emitted at
    // EOF. Does not close the stream.
    private static void writeFileBase64(File file, OutputStream out) throws IOException {
        FileInputStream in = new FileInputStream(file);
        try {
            byte[] buffer = new byte[8190]; // multiple of 3
            byte[] leftover = new byte[0];
            int count;
            while ((count = in.read(buffer)) != -1) {
                byte[] chunk;
                if (leftover.length == 0) {
                    chunk = buffer;
                } else {
                    chunk = new byte[leftover.length + count];
                    System.arraycopy(leftover, 0, chunk, 0, leftover.length);
                    System.arraycopy(buffer, 0, chunk, leftover.length, count);
                    count = chunk.length;
                }
                int completeLength = count - (count % 3);
                if (completeLength > 0) {
                    out.write(Base64.encodeToString(chunk, 0, completeLength, Base64.NO_WRAP).getBytes("UTF-8"));
                }
                int rem = count - completeLength;
                leftover = new byte[rem];
                if (rem > 0) {
                    System.arraycopy(chunk, completeLength, leftover, 0, rem);
                }
            }
            if (leftover.length > 0) {
                out.write(Base64.encodeToString(leftover, Base64.NO_WRAP).getBytes("UTF-8"));
            }
        } finally {
            try {
                in.close();
            } catch (Exception ignore) {
            }
        }
    }

    // The synchronous API needs one concrete BCP-47 language code and has
    // no true auto-detect: an explicit hint wins, otherwise the device
    // locale is used.
    private static String resolveLanguageCode(String languageHint) {
        if (languageHint != null && languageHint.contains("-")) {
            return languageHint;
        }
        String tag = Locale.getDefault().toLanguageTag();
        return tag != null && tag.length() > 0 ? tag : "en-US";
    }
}
