/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 *
 * TG fork: cloud speech-to-text engine backed by the official OpenAI
 * audio transcription API (Feature 3). The Telegram voice file (.ogg /
 * Opus) is one of the API's supported upload formats, so it is sent
 * as-is — no local decoding is needed. The API key is the user's own,
 * read from SharedConfig, sent only in the Authorization header and
 * never logged.
 */

package org.telegram.messenger;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class OpenAiWhisperEngine extends ForkSttEngine {

    // The transcription endpoint rejects uploads above 25 MB.
    private static final int MAX_UPLOAD_BYTES = 25 * 1024 * 1024;

    @Override
    public String getId() {
        return ID_OPENAI;
    }

    @Override
    public boolean isCloud() {
        return true;
    }

    @Override
    public void transcribe(File audioFile, String languageHint, Callback callback) {
        final String key = SharedConfig.forkSttOpenAiKey;
        if (key == null || key.trim().length() == 0) {
            callback.onError("OpenAI API key is not set");
            return;
        }
        try {
            // TG fork (audit G2): the size cap is checked before any
            // connection is opened, and the file is streamed — it is
            // never buffered in memory (previously the file was held in
            // up to three full copies at once).
            if (audioFile.length() > MAX_UPLOAD_BYTES) {
                throw new IOException("Audio file is too large");
            }
            String response = postMultipart(key.trim(), audioFile, languageHint);
            JSONObject json = new JSONObject(response);
            String text = json.optString("text", "").trim();
            if (text.length() == 0) {
                callback.onError("No speech recognized");
                return;
            }
            String language = json.optString("language", null);
            callback.onSuccess(text, language);
        } catch (Exception e) {
            // The exception messages produced above never contain the key;
            // the Authorization header is never read back here either.
            callback.onError("OpenAI transcription failed: " + e.getMessage());
        }
    }

    // Builds a multipart/form-data request carrying the audio file. The
    // part filename always ends in .ogg: the API picks the decoder from
    // the extension, while the on-disk cache name is not guaranteed to
    // carry one (the content is OGG/Opus either way).
    // TG fork (audit G2): the framing head and tail are fixed strings, so
    // the exact content length is known up front — the head is written,
    // the file is streamed in 8 KB chunks, then the tail, all under
    // setFixedLengthStreamingMode. Only the small head is held in memory.
    private static String postMultipart(String key, final File audioFile, String languageHint) throws IOException {
        String boundary = "ForkSttBoundary" + Long.toHexString(System.nanoTime());
        ByteArrayOutputStream headBuffer = new ByteArrayOutputStream();
        writeField(headBuffer, boundary, "model", "whisper-1");
        // verbose_json makes the API report the detected language.
        writeField(headBuffer, boundary, "response_format", "verbose_json");
        if (languageHint != null && languageHint.length() > 0) {
            writeField(headBuffer, boundary, "language", languageHint);
        }
        headBuffer.write(("--" + boundary + "\r\n").getBytes("UTF-8"));
        headBuffer.write("Content-Disposition: form-data; name=\"file\"; filename=\"voice.ogg\"\r\n".getBytes("UTF-8"));
        headBuffer.write("Content-Type: audio/ogg\r\n\r\n".getBytes("UTF-8"));
        final byte[] head = headBuffer.toByteArray();
        final byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes("UTF-8");

        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + key);
        long contentLength = head.length + audioFile.length() + tail.length;
        return httpPostStreaming("https://api.openai.com/v1/audio/transcriptions", "multipart/form-data; boundary=" + boundary, headers, contentLength, out -> {
            out.write(head);
            writeFileToStream(audioFile, out);
            out.write(tail);
        });
    }

    private static void writeField(ByteArrayOutputStream body, String boundary, String name, String value) throws IOException {
        body.write(("--" + boundary + "\r\n").getBytes("UTF-8"));
        body.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes("UTF-8"));
        body.write(value.getBytes("UTF-8"));
        body.write("\r\n".getBytes("UTF-8"));
    }
}
