/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 *
 * TG fork: settings screen for the fork voice-to-text engines
 * (Feature 3), modelled on ForkTranslateSettingsActivity. Holds the
 * feature's master switch (off by default — the v1 engines are cloud
 * services), the engine picker (OpenAI Whisper / Google Cloud
 * Speech-to-Text) and the user's own API keys. Selecting an engine asks
 * for the one-time privacy consent first. The stock Telegram
 * transcription and its Premium gates are not represented here and are
 * not affected by anything on this screen.
 */

package org.telegram.ui;

import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
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

    private static final int ROW_COUNT = 10;
    private static final int ROW_ENGINE_START = 3;
    private static final int ROW_OPENAI_KEY = 7;
    private static final int ROW_GOOGLE_KEY = 8;

    private static final int[] ENGINE_ORDER = new int[]{
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
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
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
            } else if (position == ROW_OPENAI_KEY) {
                showApiKeyDialog(true);
            } else if (position == ROW_GOOGLE_KEY) {
                showApiKeyDialog(false);
            }
        });

        return fragmentView;
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
        // TG fork privacy rule: both v1 engines are cloud services, so
        // choosing one asks for the one-time notice first (it is also
        // asked before the first actual transcription, in ChatActivity).
        if (!SharedConfig.forkSttCloudConsent) {
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

    private void showApiKeyDialog(boolean openai) {
        if (getParentActivity() == null) {
            return;
        }
        EditTextBoldCursor editText = new EditTextBoldCursor(getParentActivity());
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editText.setText(openai ? SharedConfig.forkSttOpenAiKey : SharedConfig.forkSttGoogleKey);
        editText.setSelection(editText.getText().length());
        showDialog(new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(openai ? R.string.ForkSttOpenAiKey : R.string.ForkSttGoogleKey))
                .setView(editText)
                .setPositiveButton(LocaleController.getString(R.string.Save), (dialog, which) -> {
                    String value = editText.getText() == null ? "" : editText.getText().toString().trim();
                    if (openai) {
                        SharedConfig.setForkSttOpenAiKey(value);
                    } else {
                        SharedConfig.setForkSttGoogleKey(value);
                    }
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create());
    }

    private static int engineString(int engine) {
        switch (engine) {
            case SharedConfig.FORK_STT_ENGINE_GOOGLE:
                return R.string.ForkSttEngineGoogle;
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
                case 9:
                    return VIEW_TYPE_HINT;
                case 2:
                case 6:
                    return VIEW_TYPE_HEADER;
                case 5:
                    return VIEW_TYPE_SHADOW;
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
                    if (position == ROW_OPENAI_KEY) {
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
