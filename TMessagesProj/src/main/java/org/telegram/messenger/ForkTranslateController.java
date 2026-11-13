/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 *
 * TG fork: controller for the fork translation engines (Feature 2).
 *
 * Responsibilities:
 *  - cache-first translation through the selected fork engine
 *    (TranslationsStorage -> ForkTranslateEngine -> cache store),
 *  - hard privacy rules: cloud engines are refused for encrypted (secret)
 *    chats here as well as in the UI layer, and cloud engines require an
 *    API key to be present,
 *  - the fork's own per-chat auto-translate set (persisted in
 *    TranslationsStorage), completely independent of the stock
 *    TranslateController state and of any Premium gate: this class never
 *    reads or writes TranslateController.translatingDialogs and never
 *    touches isFeatureAvailable()/isPremium().
 *
 * Auto-translate writes plain-text results into
 * messageOwner.translatedText / translatedToLanguage and swaps the bubble
 * text via MessageObject.applyNewText, reusing the stock rendering fields
 * without engaging the stock (Premium-gated) pipeline.
 */

package org.telegram.messenger;

import android.text.TextUtils;

import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.TranslateAlert2;
import org.telegram.ui.RestrictedLanguagesSelectActivity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

public class ForkTranslateController extends BaseController {

    public interface Callback {
        void onSuccess(String translatedText, String detectedFromLanguage);

        void onError(int errorCode, String detail);
    }

    public static final int ERROR_GENERIC = 1;
    public static final int ERROR_ENGINE_UNAVAILABLE = 2;
    public static final int ERROR_CLOUD_IN_SECRET_CHAT = 3;
    public static final int ERROR_NO_API_KEY = 4;
    public static final int ERROR_NO_FORK_ENGINE = 5;

    // Cap per load batch so opening a long chat cannot fire a request storm.
    private static final int MAX_AUTO_PER_BATCH = 30;

    private static volatile ForkTranslateController[] Instance = new ForkTranslateController[UserConfig.MAX_ACCOUNT_COUNT];

    public static ForkTranslateController getInstance(int num) {
        ForkTranslateController localInstance = Instance[num];
        if (localInstance == null) {
            synchronized (ForkTranslateController.class) {
                localInstance = Instance[num];
                if (localInstance == null) {
                    Instance[num] = localInstance = new ForkTranslateController(num);
                }
            }
        }
        return localInstance;
    }

    private final TranslationsStorage storage;
    // Fork-owned auto-translate dialogs: dialogId -> target language.
    private final HashMap<Long, String> autoDialogs = new HashMap<>();
    private boolean autoLoaded;
    private final HashSet<Integer> autoInFlight = new HashSet<>();

    public ForkTranslateController(int num) {
        super(num);
        storage = new TranslationsStorage(num);
    }

    // ---- single translation (manual path and auto path building block) ----

    public void translateText(long dialogId, int messageId, String text, Callback callback) {
        translateText(dialogId, messageId, text, TranslateAlert2.getToLanguage(), callback);
    }

    public void translateText(long dialogId, final int messageId, final String text, String toLanguage, final Callback callback) {
        final int engineType = SharedConfig.forkTranslateEngine;
        if (!SharedConfig.forkTranslateEnabled || engineType == SharedConfig.FORK_TRANSLATE_ENGINE_TELEGRAM) {
            AndroidUtilities.runOnUIThread(() -> callback.onError(ERROR_NO_FORK_ENGINE, null));
            return;
        }
        final ForkTranslateEngine engine = ForkTranslateEngine.create(engineType);
        if (engine == null) {
            AndroidUtilities.runOnUIThread(() -> callback.onError(ERROR_NO_FORK_ENGINE, null));
            return;
        }
        // TG fork privacy rule: message text of a secret chat must never be
        // sent to a cloud translation provider.
        if (engine.isCloud() && DialogObject.isEncryptedDialog(dialogId)) {
            AndroidUtilities.runOnUIThread(() -> callback.onError(ERROR_CLOUD_IN_SECRET_CHAT, null));
            return;
        }
        if (engine.isCloud()) {
            String key = engineType == SharedConfig.FORK_TRANSLATE_ENGINE_GOOGLE ? SharedConfig.forkTranslateGoogleKey : SharedConfig.forkTranslateDeeplKey;
            if (key == null || key.trim().length() == 0) {
                AndroidUtilities.runOnUIThread(() -> callback.onError(ERROR_NO_API_KEY, null));
                return;
            }
        }
        if (TextUtils.isEmpty(text)) {
            AndroidUtilities.runOnUIThread(() -> callback.onError(ERROR_GENERIC, "Empty text"));
            return;
        }
        final String toLang = ForkTranslateEngine.normalizeLanguage(toLanguage);
        final int srcHash = text.hashCode();
        String cached = storage.getTranslation(dialogId, messageId, toLang, engine.getId(), srcHash);
        if (cached != null) {
            AndroidUtilities.runOnUIThread(() -> callback.onSuccess(cached, null));
            return;
        }
        engine.translate(text, null, toLang, new ForkTranslateEngine.Callback() {
            @Override
            public void onSuccess(String translatedText, String detectedFromLanguage) {
                storage.putTranslation(dialogId, messageId, toLang, engine.getId(), srcHash, translatedText, System.currentTimeMillis());
                AndroidUtilities.runOnUIThread(() -> callback.onSuccess(translatedText, detectedFromLanguage));
            }

            @Override
            public void onError(String message, boolean engineUnavailable) {
                AndroidUtilities.runOnUIThread(() -> callback.onError(engineUnavailable ? ERROR_ENGINE_UNAVAILABLE : ERROR_GENERIC, message));
            }
        });
    }

    // ---- fork per-chat auto-translate --------------------------------------

    private void ensureAutoLoaded() {
        if (!autoLoaded) {
            autoLoaded = true;
            storage.loadAutoDialogs(autoDialogs);
            storage.pruneOldTranslations();
        }
    }

    public synchronized boolean isAutoTranslateDialog(long dialogId) {
        ensureAutoLoaded();
        return autoDialogs.containsKey(dialogId);
    }

    public synchronized void setAutoTranslateDialog(long dialogId, boolean enabled, String toLanguage) {
        ensureAutoLoaded();
        if (enabled) {
            String lang = ForkTranslateEngine.normalizeLanguage(toLanguage);
            autoDialogs.put(dialogId, lang);
            storage.setAutoDialog(dialogId, lang);
        } else {
            autoDialogs.remove(dialogId);
            storage.setAutoDialog(dialogId, null);
        }
    }

    // Translates the given loaded messages of a dialog when the fork
    // auto-translate toggle is on for it. Safe to call on every message
    // load: it returns immediately for dialogs that are not enabled, and
    // messages that already carry a translation for the target language
    // (or are being translated) are skipped.
    public void translateMessagesIfNeeded(long dialogId, ArrayList<MessageObject> messages) {
        if (!SharedConfig.isForkTranslateEngineSelected() || messages == null || messages.isEmpty()) {
            return;
        }
        if (!isAutoTranslateDialog(dialogId)) {
            return;
        }
        final ForkTranslateEngine engine = ForkTranslateEngine.create(SharedConfig.forkTranslateEngine);
        if (engine == null) {
            return;
        }
        // TG fork privacy rule: per-chat auto-translate never uses a cloud
        // engine inside a secret chat.
        if (engine.isCloud() && DialogObject.isEncryptedDialog(dialogId)) {
            return;
        }
        final String toLang = ForkTranslateEngine.normalizeLanguage(TranslateAlert2.getToLanguage());
        int count = 0;
        for (int i = 0; i < messages.size() && count < MAX_AUTO_PER_BATCH; i++) {
            final MessageObject messageObject = messages.get(i);
            if (messageObject == null || messageObject.messageOwner == null || messageObject.isOut()) {
                continue;
            }
            if (!TranslateController.isTranslatable(messageObject)) {
                continue;
            }
            if (messageObject.messageOwner.translatedText != null && toLang != null && toLang.equals(messageObject.messageOwner.translatedToLanguage)) {
                continue;
            }
            final String text = messageObject.messageOwner.message;
            if (TextUtils.isEmpty(text)) {
                continue;
            }
            synchronized (autoInFlight) {
                if (!autoInFlight.add(messageObject.getId())) {
                    continue;
                }
            }
            count++;
            final int messageId = messageObject.getId();
            translateText(dialogId, messageId, text, toLang, new Callback() {
                @Override
                public void onSuccess(String translatedText, String detectedFromLanguage) {
                    synchronized (autoInFlight) {
                        autoInFlight.remove(messageId);
                    }
                    // Honour the stock "do not translate" language list and
                    // never "translate" a message into its own language.
                    String detected = ForkTranslateEngine.normalizeLanguage(detectedFromLanguage);
                    if (detected != null && (detected.equals(toLang) || RestrictedLanguagesSelectActivity.getRestrictedLanguages().contains(detected))) {
                        return;
                    }
                    applyAutoTranslation(messageObject, translatedText, toLang);
                }

                @Override
                public void onError(int errorCode, String detail) {
                    synchronized (autoInFlight) {
                        autoInFlight.remove(messageId);
                    }
                }
            });
        }
    }

    // Writes a fork translation into the message and swaps the displayed
    // text, mirroring what MessageObject.updateTranslation() does in its
    // translated branch — but driven by the fork's own dialog set, so the
    // stock Premium-gated pipeline is never engaged. Must be called on the
    // UI thread (translateText delivers its callbacks there).
    private void applyAutoTranslation(MessageObject messageObject, String translatedText, String toLang) {
        if (messageObject == null || messageObject.messageOwner == null || translatedText == null) {
            return;
        }
        TLRPC.TL_textWithEntities textWithEntities = new TLRPC.TL_textWithEntities();
        textWithEntities.text = translatedText;
        messageObject.messageOwner.translatedText = textWithEntities;
        messageObject.messageOwner.translatedToLanguage = toLang;
        messageObject.applyNewText(translatedText);
        messageObject.generateCaption();
        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.messageTranslated, messageObject);
        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.updateInterfaces, 0);
    }
}
