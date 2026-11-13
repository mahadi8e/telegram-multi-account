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
            byte[] audio = readFileBytes(audioFile, MAX_UPLOAD_BYTES);
            JSONObject config = new JSONObject();
            config.put("encoding", "OGG_OPUS");
            // Opus inside Ogg always decodes at 48 kHz.
            config.put("sampleRateHertz", 48000);
            config.put("languageCode", resolveLanguageCode(languageHint));
            config.put("enableAutomaticPunctuation", true);
            JSONObject audioJson = new JSONObject();
            audioJson.put("content", Base64.encodeToString(audio, Base64.NO_WRAP));
            JSONObject body = new JSONObject();
            body.put("config", config);
            body.put("audio", audioJson);

            String url = "https://speech.googleapis.com/v1/speech:recognize?key=" + URLEncoder.encode(key.trim(), "UTF-8");
            String response = httpPost(url, body.toString().getBytes("UTF-8"), "application/json; charset=utf-8", null);
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
