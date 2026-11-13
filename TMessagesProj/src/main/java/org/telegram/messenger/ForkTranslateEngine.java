/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 *
 * TG fork: translation engine abstraction for the fork's own translation
 * paths (Feature 2). The stock Telegram server engine
 * (TLRPC.TL_messages_translateText via TranslateController/TranslateAlert2)
 * is NOT routed through this class and stays fully intact; this class only
 * serves the fork's alternative engines:
 *  - ML Kit on-device translation (text never leaves the device),
 *  - Google Cloud Translation through the official key-based API,
 *  - DeepL through its official API.
 * All engines take and return plain text (formatting entities are not
 * preserved — a known limitation of these engines). API keys are the user's
 * own, read from SharedConfig, and are never logged.
 */

package org.telegram.messenger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

public abstract class ForkTranslateEngine {

    public interface Callback {
        void onSuccess(String translatedText, String detectedFromLanguage);

        // engineUnavailable is true when the engine cannot work on this
        // device at all (e.g. ML Kit without Google Play services), as
        // opposed to a single request failing.
        void onError(String message, boolean engineUnavailable);
    }

    // Stable engine ids, also used as the engine column of the translation cache.
    public static final String ID_MLKIT = "mlkit";
    public static final String ID_GOOGLE = "google";
    public static final String ID_DEEPL = "deepl";

    public abstract String getId();

    // Cloud engines send message text to a third-party server; they are
    // refused for secret chats and require a one-time user consent.
    public abstract boolean isCloud();

    // fromLanguage may be null, meaning "auto-detect". Implementations must
    // do their work off the UI thread (or via async SDK callbacks) and must
    // eventually call exactly one of the callback methods.
    public abstract void translate(String text, String fromLanguage, String toLanguage, Callback callback);

    public static ForkTranslateEngine create(int engine) {
        switch (engine) {
            case SharedConfig.FORK_TRANSLATE_ENGINE_MLKIT:
                return new MlKitTranslateEngine();
            case SharedConfig.FORK_TRANSLATE_ENGINE_GOOGLE:
                return new GoogleCloudTranslateEngine();
            case SharedConfig.FORK_TRANSLATE_ENGINE_DEEPL:
                return new DeepLTranslateEngine();
            default:
                return null;
        }
    }

    // Normalizes a Telegram language code ("en_US", "nb") to the base
    // language tag the engines expect ("en", "no").
    public static String normalizeLanguage(String lang) {
        if (lang == null) {
            return null;
        }
        lang = lang.split("_")[0].split("-")[0].toLowerCase();
        if ("nb".equals(lang)) {
            lang = "no";
        }
        return lang;
    }

    // Minimal HTTPS POST helper for the key-based cloud engines. Returns the
    // response body on HTTP 200 and throws an IOException (without any URL or
    // credential material in the message) otherwise. Callers must invoke it
    // on a background thread.
    protected static String httpPost(String urlString, byte[] body, String contentType, Map<String, String> headers) throws IOException {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(20000);
            connection.setDoOutput(true);
            if (contentType != null) {
                connection.setRequestProperty("Content-Type", contentType);
            }
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    connection.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }
            OutputStream out = connection.getOutputStream();
            out.write(body);
            out.close();
            int code = connection.getResponseCode();
            InputStream in = code == 200 ? connection.getInputStream() : connection.getErrorStream();
            String response = readAll(in);
            if (code != 200) {
                throw new IOException("HTTP " + code + (response != null && response.length() > 0 ? ": " + trimForError(response) : ""));
            }
            return response;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return null;
        }
        try {
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) {
                result.write(buffer, 0, count);
            }
            return result.toString("UTF-8");
        } finally {
            try {
                in.close();
            } catch (Exception ignore) {
            }
        }
    }

    // Error bodies may echo request data; keep only a short prefix for the
    // user-facing error and never put URLs or keys here.
    private static String trimForError(String response) {
        String trimmed = response.replace('\n', ' ').trim();
        return trimmed.length() > 160 ? trimmed.substring(0, 160) : trimmed;
    }
}
