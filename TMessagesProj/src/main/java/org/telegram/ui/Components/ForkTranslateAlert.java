/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 *
 * TG fork: bottom sheet that presents a manual translation produced by a
 * fork translation engine (Feature 2). The stock manual flow shows server
 * translations inside TranslateAlert2; fork engines return plain text, so
 * they are presented here instead — the message bubble is not modified.
 * The sheet offers a Show original / Show translation toggle and a Copy
 * action. All methods must be called on the UI thread.
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

public class ForkTranslateAlert {

    private final BottomSheet sheet;
    private final TextView statusView;
    private final TextView translatedView;
    private final TextView originalView;
    private final TextView toggleButton;
    private final TextView copyButton;
    private final String originalText;
    private String translatedText;
    private boolean showingOriginal;

    public static String getEngineName(int engine) {
        switch (engine) {
            case SharedConfig.FORK_TRANSLATE_ENGINE_GOOGLE:
                return LocaleController.getString(R.string.ForkTranslateEngineGoogle);
            case SharedConfig.FORK_TRANSLATE_ENGINE_DEEPL:
                return LocaleController.getString(R.string.ForkTranslateEngineDeepl);
            case SharedConfig.FORK_TRANSLATE_ENGINE_TELEGRAM:
                return LocaleController.getString(R.string.ForkTranslateEngineTelegram);
            default:
                return LocaleController.getString(R.string.ForkTranslateEngineMlKit);
        }
    }

    public static ForkTranslateAlert show(Context context, Theme.ResourcesProvider resourcesProvider, String engineName, String originalText) {
        ForkTranslateAlert alert = new ForkTranslateAlert(context, resourcesProvider, engineName, originalText);
        alert.sheet.show();
        return alert;
    }

    private ForkTranslateAlert(Context context, Theme.ResourcesProvider resourcesProvider, String engineName, String originalText) {
        this.originalText = originalText == null ? "" : originalText;

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(8), AndroidUtilities.dp(20), AndroidUtilities.dp(16));

        statusView = new TextView(context);
        statusView.setTextSize(14);
        statusView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        statusView.setText(LocaleController.getString(R.string.ForkTranslateTranslating));
        layout.addView(statusView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        translatedView = new TextView(context);
        translatedView.setTextSize(16);
        translatedView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        translatedView.setTextIsSelectable(true);
        translatedView.setVisibility(View.GONE);
        layout.addView(translatedView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT, 0, 10, 0, 0));

        originalView = new TextView(context);
        originalView.setTextSize(14);
        originalView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        originalView.setTextIsSelectable(true);
        originalView.setText(this.originalText);
        originalView.setVisibility(View.GONE);
        layout.addView(originalView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT, 0, 10, 0, 0));

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        layout.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT, 0, 16, 0, 0));

        toggleButton = createButton(context, resourcesProvider, LocaleController.getString(R.string.ForkTranslateShowOriginal));
        toggleButton.setVisibility(View.GONE);
        toggleButton.setOnClickListener(v -> {
            showingOriginal = !showingOriginal;
            originalView.setVisibility(showingOriginal ? View.VISIBLE : View.GONE);
            translatedView.setVisibility(showingOriginal ? View.GONE : View.VISIBLE);
            toggleButton.setText(LocaleController.getString(showingOriginal ? R.string.ForkTranslateShowTranslation : R.string.ForkTranslateShowOriginal));
        });
        buttons.addView(toggleButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        copyButton = createButton(context, resourcesProvider, LocaleController.getString(R.string.Copy));
        copyButton.setVisibility(View.GONE);
        copyButton.setOnClickListener(v -> {
            String text = showingOriginal ? originalText : translatedText;
            if (!TextUtils.isEmpty(text)) {
                AndroidUtilities.addToClipboard(text);
            }
            sheet.dismiss();
        });
        buttons.addView(copyButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT, 12, 0, 0, 0));

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

    public void setResult(String text) {
        if (!sheet.isShowing()) {
            return;
        }
        translatedText = text;
        statusView.setVisibility(View.GONE);
        translatedView.setText(text);
        translatedView.setVisibility(View.VISIBLE);
        toggleButton.setVisibility(View.VISIBLE);
        copyButton.setVisibility(View.VISIBLE);
    }

    public void setError(String message) {
        if (!sheet.isShowing()) {
            return;
        }
        statusView.setText(message);
        statusView.setVisibility(View.VISIBLE);
        translatedView.setVisibility(View.GONE);
        toggleButton.setVisibility(View.GONE);
        copyButton.setVisibility(View.GONE);
    }
}
