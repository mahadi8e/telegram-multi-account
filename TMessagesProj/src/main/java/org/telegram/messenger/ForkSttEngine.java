/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 *
 * TG fork: speech-to-text engine abstraction for the fork's own voice
 * transcription (Feature 3). The stock Telegram server transcription
 * (TLRPC.TL_messages_transcribeAudio via TranscribeButton, with its
 * Premium gates and the Message.voiceTranscription* fields) is NOT routed
 * through this class and stays fully intact; this class only serves the
 * fork's own engines:
 *  - OpenAI Whisper API (cloud, the voice file is uploaded as-is),
 *  - Google Cloud Speech-to-Text (cloud, the voice file is sent as
 *    base64 OGG_OPUS).
 * An on-device engine (whisper.cpp via JNI) can be added later behind this
 * same interface without any UI changes: it would return isCloud() ==
 * false, at which point the controller's secret-chat policy changes from
 * "refuse" to "allow", exactly like ML Kit in Feature 2.
 * API keys are the user's own, read from SharedConfig, and are never
 * logged or included in error messages.
 */

package org.telegram.messenger;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

public abstract class ForkSttEngine {

    public interface Callback {
        void onSuccess(String text, String detectedLanguage);

        void onError(String message);
    }

    // Stable engine ids, also used as the engine column of the transcription cache.
    public static final String ID_OPENAI = "openai";
    public static final String ID_GOOGLE = "google";

    public abstract String getId();

    // Cloud engines send the voice audio to a third-party server; they are
    // refused for secret chats and require a one-time user consent.
    public abstract boolean isCloud();

    // Performs the transcription of the given local audio file. MUST be
    // called on a background thread: implementations do blocking network
    // IO and invoke exactly one callback method on that same thread.
    // languageHint may be null, meaning "auto-detect".
    public abstract void transcribe(File audioFile, String languageHint, Callback callback);

    public static ForkSttEngine create(int engine) {
        switch (engine) {
            case SharedConfig.FORK_STT_ENGINE_OPENAI:
                return new OpenAiWhisperEngine();
            case SharedConfig.FORK_STT_ENGINE_GOOGLE:
                return new GoogleSttEngine();
            default:
                return null;
        }
    }

    public static String keyForEngine(int engine) {
        switch (engine) {
            case SharedConfig.FORK_STT_ENGINE_OPENAI:
                return SharedConfig.forkSttOpenAiKey;
            case SharedConfig.FORK_STT_ENGINE_GOOGLE:
                return SharedConfig.forkSttGoogleKey;
            default:
                return "";
        }
    }

    // Reads a whole (voice-sized) file into memory. Throws an IOException
    // with a key-free, URL-free message when the file is unreasonably
    // large for a transcription upload.
    protected static byte[] readFileBytes(File file, int maxBytes) throws IOException {
        if (file.length() > maxBytes) {
            throw new IOException("Audio file is too large");
        }
        FileInputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int count;
            while ((count = in.read(buffer)) != -1) {
                result.write(buffer, 0, count);
                if (result.size() > maxBytes) {
                    throw new IOException("Audio file is too large");
                }
            }
            return result.toByteArray();
        } finally {
            try {
                in.close();
            } catch (Exception ignore) {
            }
        }
    }

    // Minimal HTTPS POST helper for the cloud engines. Returns the
    // response body on HTTP 200 and throws an IOException (without any URL
    // or credential material in the message) otherwise. Callers must
    // invoke it on a background thread.
    protected static String httpPost(String urlString, byte[] body, String contentType, Map<String, String> headers) throws IOException {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);
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
    protected static String trimForError(String response) {
        String trimmed = response.replace('\n', ' ').trim();
        return trimmed.length() > 160 ? trimmed.substring(0, 160) : trimmed;
    }
}
