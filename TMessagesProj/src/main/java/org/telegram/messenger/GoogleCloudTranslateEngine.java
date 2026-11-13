/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 *
 * TG fork: cloud translation engine backed by the official Google Cloud
 * Translation API (v2, key-based) over HTTPS (Feature 2). This is the
 * documented, key-authenticated API — not the unofficial free web endpoint
 * the stock client used to scrape. The API key is the user's own, read from
 * SharedConfig, and is never logged (it travels in the request URL, so the
 * URL itself is never written to any log either).
 */

package org.telegram.messenger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;

public class GoogleCloudTranslateEngine extends ForkTranslateEngine {

    @Override
    public String getId() {
        return ID_GOOGLE;
    }

    @Override
    public boolean isCloud() {
        return true;
    }

    @Override
    public void translate(String text, String fromLanguage, String toLanguage, Callback callback) {
        final String key = SharedConfig.forkTranslateGoogleKey;
        if (key == null || key.trim().length() == 0) {
            callback.onError("Google Cloud API key is not set", false);
            return;
        }
        final String from = normalizeLanguage(fromLanguage);
        final String to = normalizeLanguage(toLanguage);
        Utilities.globalQueue.postRunnable(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("q", text);
                body.put("target", to);
                body.put("format", "text");
                if (from != null) {
                    body.put("source", from);
                }
                String url = "https://translation.googleapis.com/language/translate/v2?key=" + URLEncoder.encode(key.trim(), "UTF-8");
                String response = httpPost(url, body.toString().getBytes("UTF-8"), "application/json; charset=utf-8", null);
                JSONObject json = new JSONObject(response);
                JSONArray translations = json.getJSONObject("data").getJSONArray("translations");
                if (translations.length() == 0) {
                    callback.onError("Translation failed", false);
                    return;
                }
                JSONObject first = translations.getJSONObject(0);
                String translatedText = first.getString("translatedText");
                String detected = first.optString("detectedSourceLanguage", null);
                callback.onSuccess(translatedText, detected);
            } catch (Exception e) {
                // Deliberately no request details here: the URL contains the key.
                callback.onError("Google Cloud Translation failed: " + e.getMessage(), false);
            }
        });
    }
}
