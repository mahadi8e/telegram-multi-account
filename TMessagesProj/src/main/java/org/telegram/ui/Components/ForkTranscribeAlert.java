/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 *
 * TG fork: bottom sheet that presents a voice transcription produced by
 * a fork speech-to-text engine (Feature 3), modelled on
 * ForkTranslateAlert. The stock client renders its (Premium) server
 * transcription inside the message bubble via TranscribeButton; fork
 * results are deliberately kept out of the bubble and out of the
 * message object, and are shown here instead, labelled with the engine
 * that produced them. All methods must be called on the UI thread.
 */

package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;

public class ForkTranscribeAlert {

    private final BottomSheet sheet;
    private final TextView statusView;
    private final TextView resultView;
    private final TextView copyButton;
    private String resultText;

    public static String getEngineName(int engine) {
        switch (engine) {
            case SharedConfig.FORK_STT_ENGINE_GOOGLE:
                return LocaleController.getString(R.string.ForkSttEngineGoogle);
            default:
                return LocaleController.getString(R.string.ForkSttEngineOpenAi);
        }
    }

    public static ForkTranscribeAlert show(Context context, Theme.ResourcesProvider resourcesProvider, String engineName) {
        ForkTranscribeAlert alert = new ForkTranscribeAlert(context, resourcesProvider, engineName);
        alert.sheet.show();
        return alert;
    }

    private ForkTranscribeAlert(Context context, Theme.ResourcesProvider resourcesProvider, String engineName) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(8), AndroidUtilities.dp(20), AndroidUtilities.dp(16));

        statusView = new TextView(context);
        statusView.setTextSize(14);
        statusView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        statusView.setText(LocaleController.getString(R.string.ForkSttTranscribing));
        layout.addView(statusView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        resultView = new TextView(context);
        resultView.setTextSize(16);
        resultView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        resultView.setTextIsSelectable(true);
        resultView.setVisibility(View.GONE);
        layout.addView(resultView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT, 0, 10, 0, 0));

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        layout.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT, 0, 16, 0, 0));

        copyButton = createButton(context, resourcesProvider, LocaleController.getString(R.string.Copy));
        copyButton.setVisibility(View.GONE);
        copyButton.setOnClickListener(v -> {
            if (!TextUtils.isEmpty(resultText)) {
                AndroidUtilities.addToClipboard(resultText);
            }
            sheet.dismiss();
        });
        buttons.addView(copyButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        BottomSheet.Builder builder = new BottomSheet.Builder(context, false, resourcesProvider);
        builder.setTitle(engineName);
        builder.setCustomView(layout);
        sheet = builder.create();
    }

    private static TextView createButton(Context context, Theme.ResourcesProvider resourcesProvider, String text) {
        TextView button = new TextView(context);
        button.setText(text);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourcesProvider));
        button.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(6), AndroidUtilities.dp(8), AndroidUtilities.dp(6));
        return button;
    }

    public boolean isShowing() {
        return sheet.isShowing();
    }

    public void setResult(String text, String detectedLanguage) {
        if (!sheet.isShowing()) {
            return;
        }
        resultText = text;
        if (!TextUtils.isEmpty(detectedLanguage)) {
            statusView.setText(LocaleController.formatString("ForkSttDetectedLanguage", R.string.ForkSttDetectedLanguage, detectedLanguage));
            statusView.setVisibility(View.VISIBLE);
        } else {
            statusView.setVisibility(View.GONE);
        }
        resultView.setText(text);
        resultView.setVisibility(View.VISIBLE);
        copyButton.setVisibility(View.VISIBLE);
    }

    public void setError(String message) {
        if (!sheet.isShowing()) {
            return;
        }
        statusView.setText(message);
        statusView.setVisibility(View.VISIBLE);
        resultView.setVisibility(View.GONE);
        copyButton.setVisibility(View.GONE);
    }
}
