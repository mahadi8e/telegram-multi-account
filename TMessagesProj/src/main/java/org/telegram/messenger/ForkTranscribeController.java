/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 *
 * TG fork: controller for the fork voice-to-text engines (Feature 3).
 *
 * Responsibilities:
 *  - cache-first transcription through the selected fork engine
 *    (TranscriptionsStorage -> ForkSttEngine -> cache store), with all
 *    work on a dedicated serial DispatchQueue (one transcription at a
 *    time — sane CPU/battery behaviour),
 *  - hard privacy rules: the v1 engines are all cloud, so encrypted
 *    (secret) chats are refused — checked both at request entry and
 *    again at engine dispatch — and self-destructing (TTL) voice
 *    messages are refused for every engine, mirroring the stock rule
 *    that hides its own transcription button for ephemeral media,
 *  - voice messages only: round videos (video notes) are refused,
 *  - the voice file must already be downloaded (via the normal stock
 *    download); this controller never starts a download itself.
 *
 * Nothing here touches the stock transcription pipeline: no
 * TL_messages_transcribeAudio request, no TranscribeButton, and no
 * writes to Message.voiceTranscription* — those stay exclusively
 * Telegram's (and Premium-gated).
 */

package org.telegram.messenger;

import org.telegram.tgnet.TLRPC;

import java.io.File;

public class ForkTranscribeController extends BaseController {

    public interface Callback {
        void onSuccess(String text, String detectedLanguage);

        void onError(int errorCode, String detail);
    }

    public static final int ERROR_GENERIC = 1;
    public static final int ERROR_DISABLED = 2;
    public static final int ERROR_NO_API_KEY = 3;
    public static final int ERROR_SECRET_CHAT = 4;
    public static final int ERROR_TTL_MEDIA = 5;
    public static final int ERROR_NOT_DOWNLOADED = 6;
    public static final int ERROR_NOT_VOICE = 7;

    private static volatile ForkTranscribeController[] Instance = new ForkTranscribeController[UserConfig.MAX_ACCOUNT_COUNT];

    public static ForkTranscribeController getInstance(int num) {
        ForkTranscribeController localInstance = Instance[num];
        if (localInstance == null) {
            synchronized (ForkTranscribeController.class) {
                localInstance = Instance[num];
                if (localInstance == null) {
                    Instance[num] = localInstance = new ForkTranscribeController(num);
                }
            }
        }
        return localInstance;
    }

    private final TranscriptionsStorage storage;
    private final DispatchQueue transcribeQueue;
    private boolean pruned;

    public ForkTranscribeController(int num) {
        super(num);
        storage = new TranscriptionsStorage(num);
        transcribeQueue = new DispatchQueue("forkTranscribeQueue" + num);
    }

    public void transcribe(final MessageObject messageObject, final Callback callback) {
        if (!SharedConfig.forkSttEnabled) {
            AndroidUtilities.runOnUIThread(() -> callback.onError(ERROR_DISABLED, null));
            return;
        }
        final ForkSttEngine engine = ForkSttEngine.create(SharedConfig.forkSttEngine);
        if (engine == null || messageObject == null || messageObject.messageOwner == null) {
            AndroidUtilities.runOnUIThread(() -> callback.onError(ERROR_GENERIC, null));
            return;
        }
        final long dialogId = messageObject.getDialogId();
        final int messageId = messageObject.getId();
        // TG fork: voice messages only. Round videos carry an MP4/AAC
        // track and are refused here (isVoice() is false for them).
        if (!messageObject.isVoice()) {
            AndroidUtilities.runOnUIThread(() -> callback.onError(ERROR_NOT_VOICE, null));
            return;
        }
        // TG fork privacy rule (entry check): cloud engines never receive
        // audio from a secret chat. Both v1 engines are cloud; a future
        // on-device engine (isCloud() == false) would be allowed here,
        // mirroring the ML Kit policy of Feature 2.
        if (engine.isCloud() && DialogObject.isEncryptedDialog(dialogId)) {
            AndroidUtilities.runOnUIThread(() -> callback.onError(ERROR_SECRET_CHAT, null));
            return;
        }
        // TG fork: self-destructing voice messages are refused for every
        // engine — their sender expects the content to disappear, and the
        // stock client applies the same rule to its transcription button
        // (it requires media.ttl_seconds == 0).
        TLRPC.MessageMedia media = messageObject.messageOwner.media;
        if (media != null && media.ttl_seconds != 0) {
            AndroidUtilities.runOnUIThread(() -> callback.onError(ERROR_TTL_MEDIA, null));
            return;
        }
        if (engine.isCloud()) {
            String key = ForkSttEngine.keyForEngine(SharedConfig.forkSttEngine);
            if (key == null || key.trim().length() == 0) {
                AndroidUtilities.runOnUIThread(() -> callback.onError(ERROR_NO_API_KEY, null));
                return;
            }
        }
        final int duration = (int) Math.round(messageObject.getDuration());
        transcribeQueue.postRunnable(() -> {
            if (!pruned) {
                pruned = true;
                storage.pruneOldTranscriptions();
            }
            TranscriptionsStorage.Entry cached = storage.getTranscription(dialogId, messageId, engine.getId());
            if (cached != null) {
                AndroidUtilities.runOnUIThread(() -> callback.onSuccess(cached.text, cached.language));
                return;
            }
            File file = getFileLoader().getPathToMessage(messageObject.messageOwner);
            long expectedSize = -1;
            if (media instanceof TLRPC.TL_messageMediaDocument && media.document != null) {
                expectedSize = media.document.size;
            }
            // The file must be fully downloaded already; a partial cache
            // file (length below the document size) counts as missing.
            if (file == null || !file.exists() || (expectedSize > 0 && file.length() != expectedSize)) {
                AndroidUtilities.runOnUIThread(() -> callback.onError(ERROR_NOT_DOWNLOADED, null));
                return;
            }
            // TG fork privacy rule (dispatch check): re-verify the secret
            // chat block at the moment the engine is actually invoked.
            if (engine.isCloud() && DialogObject.isEncryptedDialog(dialogId)) {
                AndroidUtilities.runOnUIThread(() -> callback.onError(ERROR_SECRET_CHAT, null));
                return;
            }
            engine.transcribe(file, null, new ForkSttEngine.Callback() {
                @Override
                public void onSuccess(String text, String detectedLanguage) {
                    storage.putTranscription(dialogId, messageId, engine.getId(), detectedLanguage, text, duration, System.currentTimeMillis());
                    AndroidUtilities.runOnUIThread(() -> callback.onSuccess(text, detectedLanguage));
                }

                @Override
                public void onError(String message) {
                    AndroidUtilities.runOnUIThread(() -> callback.onError(ERROR_GENERIC, message));
                }
            });
        });
    }
}
