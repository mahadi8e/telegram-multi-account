/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 *
 * TG fork: cloud translation engine backed by the official DeepL API over
 * HTTPS (Feature 2). Free keys (ending in ":fx") are served by
 * api-free.deepl.com, all other keys by api.deepl.com. The API key is the
 * user's own, read from SharedConfig, sent in the Authorization header and
 * never logged.
 */

package org.telegram.messenger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.HashMap;

public class DeepLTranslateEngine extends ForkTranslateEngine {

    @Override
    public String getId() {
        return ID_DEEPL;
    }

    @Override
    public boolean isCloud() {
        return true;
    }

    @Override
    public void translate(String text, String fromLanguage, String toLanguage, Callback callback) {
        final String key = SharedConfig.forkTranslateDeeplKey;
        if (key == null || key.trim().length() == 0) {
            callback.onError("DeepL API key is not set", false);
            return;
        }
        final String from = normalizeLanguage(fromLanguage);
        final String to = normalizeLanguage(toLanguage);
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String trimmedKey = key.trim();
                String host = trimmedKey.endsWith(":fx") ? "https://api-free.deepl.com" : "https://api.deepl.com";
                StringBuilder body = new StringBuilder();
                body.append("text=").append(URLEncoder.encode(text, "UTF-8"));
                body.append("&target_lang=").append(URLEncoder.encode(toDeepLLanguage(to), "UTF-8"));
                if (from != null) {
                    body.append("&source_lang=").append(URLEncoder.encode(toDeepLLanguage(from), "UTF-8"));
                }
                HashMap<String, String> headers = new HashMap<>();
                headers.put("Authorization", "DeepL-Auth-Key " + trimmedKey);
                String response = httpPost(host + "/v2/translate", body.toString().getBytes("UTF-8"), "application/x-www-form-urlencoded", headers);
                JSONObject json = new JSONObject(response);
                JSONArray translations = json.getJSONArray("translations");
                if (translations.length() == 0) {
                    callback.onError("Translation failed", false);
                    return;
                }
                JSONObject first = translations.getJSONObject(0);
                String translatedText = first.getString("text");
                String detected = first.optString("detected_source_language", null);
                callback.onSuccess(translatedText, detected != null ? detected.toLowerCase() : null);
            } catch (Exception e) {
                callback.onError("DeepL translation failed: " + e.getMessage(), false);
            }
        });
    }

    // DeepL expects upper-case codes and uses NB for Norwegian Bokmål.
    private static String toDeepLLanguage(String lang) {
        if (lang == null) {
            return "EN";
        }
        if ("no".equals(lang)) {
            return "NB";
        }
        return lang.toUpperCase();
    }
}
