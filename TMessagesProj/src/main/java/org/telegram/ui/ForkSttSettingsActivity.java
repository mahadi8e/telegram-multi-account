/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 *
 * TG fork: settings screen for the fork voice-to-text engines
 * (Feature 3), modelled on ForkTranslateSettingsActivity. Holds the
 * feature's master switch (off by default), the engine picker
 * (on-device Whisper / OpenAI Whisper / Google Cloud Speech-to-Text),
 * the on-device model download/delete row and the user's own API keys
 * for the cloud engines. Selecting a cloud engine asks for the
 * one-time privacy consent first; the on-device engine needs none.
 * The stock Telegram transcription and its Premium gates are not
 * represented here and are not affected by anything on this screen.
 */

package org.telegram.ui;

import android.content.Context;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ForkSttEngine;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.WhisperModel;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.ForkTranscribeAlert;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

public class ForkSttSettingsActivity extends BaseFragment {

    private RecyclerListView listView;
    private ListAdapter adapter;

    private static final int VIEW_TYPE_CHECK = 0;
    private static final int VIEW_TYPE_HINT = 1;
    private static final int VIEW_TYPE_HEADER = 2;
    private static final int VIEW_TYPE_ENGINE = 3;
    private static final int VIEW_TYPE_VALUE = 4;
    private static final int VIEW_TYPE_SHADOW = 5;

    private static final int ROW_COUNT = 12;
    private static final int ROW_ENGINE_START = 3;
    private static final int ROW_MODEL = 6;
    private static final int ROW_OPENAI_KEY = 9;
    private static final int ROW_GOOGLE_KEY = 10;

    private static final int[] ENGINE_ORDER = new int[]{
            SharedConfig.FORK_STT_ENGINE_ONDEVICE,
            SharedConfig.FORK_STT_ENGINE_OPENAI,
            SharedConfig.FORK_STT_ENGINE_GOOGLE
    };

    public ForkSttSettingsActivity() {
        super();
    }

    @Override
    protected boolean useSimpleActionBar() {
        return true;
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);
        simpleActionBar.setBackButtonImage(R.drawable.ic_ab_back);
        simpleActionBar.setTitle(LocaleController.getString(R.string.ForkSttSettings));
        simpleActionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });
        if (parentLayout != null && parentLayout.isRightLayout()) {
            simpleActionBar.setBackButtonImage(R.drawable.ic_ab_close);
        }

        fragmentView = new FrameLayout(context);
        FrameLayout frameLayout = (FrameLayout) fragmentView;

        listView = new RecyclerListView(context);
        listView.setSections(true);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setSectionsDrawBackground(true);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setOnItemClickListener((view, position, x, y) -> {
            if (adapter == null || position < 0 || position >= adapter.getItemCount()) {
                return;
            }
            int viewType = adapter.getItemViewType(position);
            if (viewType == VIEW_TYPE_CHECK) {
                SharedConfig.setForkSttEnabled(!SharedConfig.forkSttEnabled);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(SharedConfig.forkSttEnabled);
                }
            } else if (viewType == VIEW_TYPE_ENGINE) {
                selectEngine(ENGINE_ORDER[position - ROW_ENGINE_START]);
            } else if (position == ROW_MODEL) {
                onModelRowClicked();
            } else if (position == ROW_OPENAI_KEY) {
                showApiKeyDialog(true);
            } else if (position == ROW_GOOGLE_KEY) {
                showApiKeyDialog(false);
            }
        });

        return fragmentView;
    }

    // TG fork UI fix: returning the list here lets BaseFragment apply the
    // simple-glass insets (status bar + action bar) as list padding, like
    // every stock settings screen. Without it the list slides under the
    // status bar. setSections()/setSectionsDrawBackground() above give the
    // rows Telegram's rounded section cards over the gray page background.
    @Override
    protected RecyclerListView getListViewForSimpleGlass() {
        return listView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void selectEngine(int engine) {
        if (engine == SharedConfig.forkSttEngine) {
            return;
        }
        // TG fork privacy rule: choosing a cloud engine asks for the
        // one-time notice first (it is also asked before the first
        // actual transcription, in ChatActivity). The on-device engine
        // sends nothing anywhere, so it needs no consent.
        if (ForkSttEngine.isCloudEngine(engine) && !SharedConfig.forkSttCloudConsent) {
            if (getParentActivity() == null) {
                return;
            }
            showDialog(new AlertDialog.Builder(getParentActivity())
                    .setTitle(LocaleController.getString(R.string.ForkSttCloudNoticeTitle))
                    .setMessage(LocaleController.formatString("ForkSttCloudNotice", R.string.ForkSttCloudNotice, ForkTranscribeAlert.getEngineName(engine)))
                    .setPositiveButton(LocaleController.getString(R.string.OK), (dialog, which) -> {
                        SharedConfig.setForkSttCloudConsent(true);
                        SharedConfig.setForkSttEngine(engine);
                        if (adapter != null) {
                            adapter.notifyDataSetChanged();
                        }
                    })
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                    .create());
            return;
        }
        SharedConfig.setForkSttEngine(engine);
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    // TG fork: the on-device engine's model row — downloads the Whisper
    // model on first use (with a spinner dialog; the download itself is
    // verified by WhisperModel) and offers to delete it afterwards.
    private void onModelRowClicked() {
        if (getParentActivity() == null || WhisperModel.isDownloading()) {
            return;
        }
        if (WhisperModel.isDownloaded()) {
            showDialog(new AlertDialog.Builder(getParentActivity())
                    .setTitle(LocaleController.getString(R.string.ForkSttModelDeleteTitle))
                    .setMessage(LocaleController.getString(R.string.ForkSttModelDeleteMessage))
                    .setPositiveButton(LocaleController.getString(R.string.Delete), (dialog, which) -> {
                        WhisperModel.delete();
                        if (adapter != null) {
                            adapter.notifyDataSetChanged();
                        }
                    })
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                    .create());
            return;
        }
        final AlertDialog progressDialog = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER, getResourceProvider());
        progressDialog.setMessage(LocaleController.getString(R.string.ForkSttModelDownloading));
        showDialog(progressDialog);
        Utilities.globalQueue.postRunnable(() -> WhisperModel.download(new WhisperModel.DownloadCallback() {
            @Override
            public void onProgress(long downloaded, long total) {
            }

            @Override
            public void onDone(boolean success) {
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        progressDialog.dismiss();
                    } catch (Exception ignore) {
                    }
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                    if (!success && getParentActivity() != null) {
                        showDialog(new AlertDialog.Builder(getParentActivity())
                                .setTitle(LocaleController.getString(R.string.ForkSttModel))
                                .setMessage(LocaleController.getString(R.string.ForkSttModelDownloadFailed))
                                .setPositiveButton(LocaleController.getString(R.string.OK), null)
                                .create());
                    }
                });
            }
        }));
    }

    private void showApiKeyDialog(boolean openai) {
        if (getParentActivity() == null) {
            return;
        }
        // TG fork UI fix: the bare EditText used to be passed straight to
        // the dialog \u2014 no padding and no background, so it was not even
        // recognizable as a text box. Style it like the stock text-input
        // alerts (AlertsCreator.createSimpleTextInputAlert): a padded field
        // with a rounded filled background inside a margined container,
        // capped dialog width, focus + keyboard on show.
        EditTextBoldCursor editText = new EditTextBoldCursor(getParentActivity());
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        editText.setHintTextColor(getThemedColor(Theme.key_groupcreate_hintText));
        editText.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(11), AndroidUtilities.dp(16), AndroidUtilities.dp(11));
        editText.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(22), Theme.multAlpha(getThemedColor(Theme.key_dialogTextBlack), 0.06f)));
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editText.setText(openai ? SharedConfig.forkSttOpenAiKey : SharedConfig.forkSttGoogleKey);
        editText.setSelection(editText.getText().length());

        LinearLayout container = new LinearLayout(getParentActivity());
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 9, 20, 9));

        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(openai ? R.string.ForkSttOpenAiKey : R.string.ForkSttGoogleKey));
        builder.setView(container);
        builder.setWidth(Math.min(AndroidUtilities.dp(320), AndroidUtilities.displaySize.x * 85 / 100));
        builder.setPositiveButton(LocaleController.getString(R.string.Save), (dialog, which) -> {
            String value = editText.getText() == null ? "" : editText.getText().toString().trim();
            if (openai) {
                SharedConfig.setForkSttOpenAiKey(value);
            } else {
                SharedConfig.setForkSttGoogleKey(value);
            }
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            editText.requestFocus();
            AndroidUtilities.showKeyboard(editText);
        });
        showDialog(dialog);
    }

    private static int engineString(int engine) {
        switch (engine) {
            case SharedConfig.FORK_STT_ENGINE_GOOGLE:
                return R.string.ForkSttEngineGoogle;
            case SharedConfig.FORK_STT_ENGINE_ONDEVICE:
                return R.string.ForkSttEngineOnDevice;
            default:
                return R.string.ForkSttEngineOpenAi;
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context mContext;

        public ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public int getItemCount() {
            return ROW_COUNT;
        }

        @Override
        public int getItemViewType(int position) {
            switch (position) {
                case 0:
                    return VIEW_TYPE_CHECK;
                case 1:
                case 11:
                    return VIEW_TYPE_HINT;
                case 2:
                case 8:
                    return VIEW_TYPE_HEADER;
                case 7:
                    return VIEW_TYPE_SHADOW;
                case ROW_MODEL:
                case ROW_OPENAI_KEY:
                case ROW_GOOGLE_KEY:
                    return VIEW_TYPE_VALUE;
                default:
                    return VIEW_TYPE_ENGINE;
            }
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int viewType = holder.getItemViewType();
            return viewType == VIEW_TYPE_CHECK || viewType == VIEW_TYPE_ENGINE || viewType == VIEW_TYPE_VALUE;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case VIEW_TYPE_CHECK:
                case VIEW_TYPE_ENGINE:
                    view = new TextCheckCell(mContext);
                    break;
                case VIEW_TYPE_HINT:
                    view = new TextInfoPrivacyCell(mContext);
                    break;
                case VIEW_TYPE_HEADER:
                    view = new HeaderCell(mContext);
                    break;
                case VIEW_TYPE_SHADOW:
                    view = new ShadowSectionCell(mContext);
                    break;
                default:
                    view = new TextSettingsCell(mContext);
                    break;
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            int viewType = holder.getItemViewType();
            switch (viewType) {
                case VIEW_TYPE_CHECK: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    cell.setTextAndCheck(LocaleController.getString(R.string.ForkSttEnabled), SharedConfig.forkSttEnabled, true);
                    break;
                }
                case VIEW_TYPE_HINT: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    if (position == 1) {
                        cell.setText(LocaleController.getString(R.string.ForkSttInfo));
                    } else {
                        cell.setText(LocaleController.getString(R.string.ForkSttApiKeysInfo));
                    }
                    break;
                }
                case VIEW_TYPE_HEADER: {
                    HeaderCell cell = (HeaderCell) holder.itemView;
                    if (position == 2) {
                        cell.setText(LocaleController.getString(R.string.ForkSttEngineHeader));
                    } else {
                        cell.setText(LocaleController.getString(R.string.ForkSttApiKeysHeader));
                    }
                    break;
                }
                case VIEW_TYPE_ENGINE: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    int engine = ENGINE_ORDER[position - ROW_ENGINE_START];
                    cell.setTextAndCheck(LocaleController.getString(engineString(engine)), SharedConfig.forkSttEngine == engine, position - ROW_ENGINE_START < ENGINE_ORDER.length - 1);
                    break;
                }
                case VIEW_TYPE_VALUE: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    if (position == ROW_MODEL) {
                        cell.setTextAndValue(LocaleController.getString(R.string.ForkSttModel), WhisperModel.isDownloading() ? LocaleController.getString(R.string.ForkSttModelDownloading) : WhisperModel.isDownloaded() ? AndroidUtilities.formatFileSize(WhisperModel.getModelFile().length()) : LocaleController.getString(R.string.ForkSttModelNotDownloaded), true);
                    } else if (position == ROW_OPENAI_KEY) {
                        cell.setTextAndValue(LocaleController.getString(R.string.ForkSttOpenAiKey), keyValue(SharedConfig.forkSttOpenAiKey), true);
                    } else {
                        cell.setTextAndValue(LocaleController.getString(R.string.ForkSttGoogleKey), keyValue(SharedConfig.forkSttGoogleKey), false);
                    }
                    break;
                }
            }
        }

        private String keyValue(String key) {
            return key != null && key.trim().length() > 0 ? "••••••••" : LocaleController.getString(R.string.ForkSttKeyNotSet);
        }
    }
}
