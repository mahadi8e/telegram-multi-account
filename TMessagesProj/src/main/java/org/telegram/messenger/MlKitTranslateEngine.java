/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 *
 * TG fork: on-device translation engine backed by ML Kit Translate
 * (Feature 2). Message text never leaves the device; only the per-language
 * model files are downloaded from Google's servers through Play services
 * when a language pair is first used. On devices without Google Play
 * services the model download fails and the engine reports itself
 * unavailable instead of crashing.
 */

package org.telegram.messenger;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

public class MlKitTranslateEngine extends ForkTranslateEngine {

    @Override
    public String getId() {
        return ID_MLKIT;
    }

    @Override
    public boolean isCloud() {
        return false;
    }

    @Override
    public void translate(String text, String fromLanguage, String toLanguage, Callback callback) {
        try {
            String to = TranslateLanguage.fromLanguageTag(normalizeLanguage(toLanguage));
            if (to == null) {
                callback.onError("This language is not supported by ML Kit", false);
                return;
            }
            if (fromLanguage != null) {
                String from = TranslateLanguage.fromLanguageTag(normalizeLanguage(fromLanguage));
                if (from == null) {
                    callback.onError("This language is not supported by ML Kit", false);
                    return;
                }
                translateWithLanguages(text, from, to, callback);
            } else {
                // Auto-detect reuses the stock ML Kit language-id wrapper.
                LanguageDetector.detectLanguage(text, lang -> {
                    String from = lang == null ? null : TranslateLanguage.fromLanguageTag(normalizeLanguage(lang));
                    if (from == null) {
                        callback.onError("Could not detect the message language", false);
                        return;
                    }
                    translateWithLanguages(text, from, to, callback);
                }, e -> callback.onError("Could not detect the message language", false));
            }
        } catch (Throwable t) {
            // ML Kit / Play services classes may be missing entirely on
            // GMS-less builds; never let that crash the caller.
            FileLog.e(t, false);
            callback.onError("ML Kit is unavailable on this device", true);
        }
    }

    private void translateWithLanguages(String text, String from, String to, Callback callback) {
        Translator translator = null;
        try {
            TranslatorOptions options = new TranslatorOptions.Builder()
                    .setSourceLanguage(from)
                    .setTargetLanguage(to)
                    .build();
            translator = Translation.getClient(options);
            final Translator client = translator;
            client.downloadModelIfNeeded(new DownloadConditions.Builder().build())
                    .addOnSuccessListener(v -> client.translate(text)
                            .addOnSuccessListener(result -> {
                                closeQuietly(client);
                                callback.onSuccess(result, from);
                            })
                            .addOnFailureListener(e -> {
                                closeQuietly(client);
                                callback.onError("Translation failed", false);
                            }))
                    .addOnFailureListener(e -> {
                        // Model downloads go through Play services; a failure
                        // here almost always means the device cannot run
                        // ML Kit at all.
                        closeQuietly(client);
                        callback.onError("Could not download the language model", true);
                    });
        } catch (Throwable t) {
            closeQuietly(translator);
            FileLog.e(t, false);
            callback.onError("ML Kit is unavailable on this device", true);
        }
    }

    private static void closeQuietly(Translator translator) {
        if (translator != null) {
            try {
                translator.close();
            } catch (Throwable ignore) {
            }
        }
    }
}
