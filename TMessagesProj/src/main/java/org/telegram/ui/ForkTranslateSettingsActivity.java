/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 *
 * TG fork: settings screen for the fork translation engines (Feature 2).
 * Holds the feature's master switch, the engine picker (Telegram server /
 * ML Kit on-device / Google Cloud / DeepL), the user's own API keys for the
 * cloud engines, and shortcuts into the stock translation preferences
 * (target language and the "do not translate" list are the stock ones —
 * this screen changes them through the same storage the stock UI uses).
 * Selecting a cloud engine asks for the one-time privacy consent first.
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
import org.telegram.messenger.TranslateController;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.ForkTranslateAlert;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.TranslateAlert2;

import java.util.ArrayList;

public class ForkTranslateSettingsActivity extends BaseFragment {

    private RecyclerListView listView;
    private ListAdapter adapter;

    private static final int VIEW_TYPE_CHECK = 0;
    private static final int VIEW_TYPE_HINT = 1;
    private static final int VIEW_TYPE_HEADER = 2;
    private static final int VIEW_TYPE_ENGINE = 3;
    private static final int VIEW_TYPE_VALUE = 4;
    private static final int VIEW_TYPE_TEXT = 5;
    private static final int VIEW_TYPE_SHADOW = 6;

    private static final int ROW_COUNT = 17;
    private static final int ROW_ENGINE_START = 3;
    private static final int ROW_GOOGLE_KEY = 9;
    private static final int ROW_DEEPL_KEY = 10;
    private static final int ROW_TARGET_LANGUAGE = 14;
    private static final int ROW_DO_NOT_TRANSLATE = 15;

    private static final int[] ENGINE_ORDER = new int[]{
            SharedConfig.FORK_TRANSLATE_ENGINE_TELEGRAM,
            SharedConfig.FORK_TRANSLATE_ENGINE_MLKIT,
            SharedConfig.FORK_TRANSLATE_ENGINE_GOOGLE,
            SharedConfig.FORK_TRANSLATE_ENGINE_DEEPL
    };

    public ForkTranslateSettingsActivity() {
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
        simpleActionBar.setTitle(LocaleController.getString(R.string.ForkTranslationEngine));
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
                SharedConfig.setForkTranslateEnabled(!SharedConfig.forkTranslateEnabled);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(SharedConfig.forkTranslateEnabled);
                }
            } else if (viewType == VIEW_TYPE_ENGINE) {
                selectEngine(ENGINE_ORDER[position - ROW_ENGINE_START]);
            } else if (position == ROW_GOOGLE_KEY) {
                showApiKeyDialog(true);
            } else if (position == ROW_DEEPL_KEY) {
                showApiKeyDialog(false);
            } else if (position == ROW_TARGET_LANGUAGE) {
                showTargetLanguagePicker();
            } else if (position == ROW_DO_NOT_TRANSLATE) {
                presentFragment(new RestrictedLanguagesSelectActivity());
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
        if (engine == SharedConfig.forkTranslateEngine) {
            return;
        }
        // TG fork privacy rule: choosing a cloud engine asks for the
        // one-time notice first (it is also asked before the first actual
        // cloud translation, in ChatActivity).
        if (SharedConfig.isForkCloudEngine(engine) && !SharedConfig.forkCloudTranslateConsent) {
            if (getParentActivity() == null) {
                return;
            }
            showDialog(new AlertDialog.Builder(getParentActivity())
                    .setTitle(LocaleController.getString(R.string.ForkTranslateCloudNoticeTitle))
                    .setMessage(LocaleController.formatString("ForkTranslateCloudNotice", R.string.ForkTranslateCloudNotice, ForkTranslateAlert.getEngineName(engine)))
                    .setPositiveButton(LocaleController.getString(R.string.OK), (dialog, which) -> {
                        SharedConfig.setForkCloudTranslateConsent(true);
                        SharedConfig.setForkTranslateEngine(engine);
                        if (adapter != null) {
                            adapter.notifyDataSetChanged();
                        }
                    })
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                    .create());
            return;
        }
        SharedConfig.setForkTranslateEngine(engine);
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void showApiKeyDialog(boolean google) {
        if (getParentActivity() == null) {
            return;
        }
        EditTextBoldCursor editText = new EditTextBoldCursor(getParentActivity());
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editText.setText(google ? SharedConfig.forkTranslateGoogleKey : SharedConfig.forkTranslateDeeplKey);
        editText.setSelection(editText.getText().length());
        showDialog(new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(google ? R.string.ForkTranslateGoogleKey : R.string.ForkTranslateDeeplKey))
                .setView(editText)
                .setPositiveButton(LocaleController.getString(R.string.Save), (dialog, which) -> {
                    String value = editText.getText() == null ? "" : editText.getText().toString().trim();
                    if (google) {
                        SharedConfig.setForkTranslateGoogleKey(value);
                    } else {
                        SharedConfig.setForkTranslateDeeplKey(value);
                    }
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create());
    }

    // Target language reuses the stock storage and locale list: the value is
    // read and written through TranslateAlert2.getToLanguage/setToLanguage,
    // exactly like the stock translate sheet does.
    private void showTargetLanguagePicker() {
        if (getParentActivity() == null) {
            return;
        }
        ArrayList<LocaleController.LocaleInfo> locales = TranslateController.getLocales();
        ArrayList<String> names = new ArrayList<>();
        ArrayList<String> codes = new ArrayList<>();
        for (int i = 0; i < locales.size(); i++) {
            LocaleController.LocaleInfo localeInfo = locales.get(i);
            if (!"remote".equals(localeInfo.pathToFile)) {
                continue;
            }
            names.add(TranslateAlert2.languageName(localeInfo.pluralLangCode));
            codes.add(localeInfo.pluralLangCode);
        }
        if (codes.isEmpty()) {
            return;
        }
        showDialog(new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.ForkTranslateTargetLanguage))
                .setItems(names.toArray(new CharSequence[0]), (dialog, which) -> {
                    TranslateAlert2.setToLanguage(codes.get(which));
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create());
    }

    private static int engineString(int engine) {
        switch (engine) {
            case SharedConfig.FORK_TRANSLATE_ENGINE_GOOGLE:
                return R.string.ForkTranslateEngineGoogle;
            case SharedConfig.FORK_TRANSLATE_ENGINE_DEEPL:
                return R.string.ForkTranslateEngineDeepl;
            case SharedConfig.FORK_TRANSLATE_ENGINE_TELEGRAM:
                return R.string.ForkTranslateEngineTelegram;
            default:
                return R.string.ForkTranslateEngineMlKit;
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
                case 16:
                    return VIEW_TYPE_HINT;
                case 2:
                case 8:
                case 13:
                    return VIEW_TYPE_HEADER;
                case 7:
                case 12:
                    return VIEW_TYPE_SHADOW;
                case ROW_GOOGLE_KEY:
                case ROW_DEEPL_KEY:
                case ROW_TARGET_LANGUAGE:
                    return VIEW_TYPE_VALUE;
                case ROW_DO_NOT_TRANSLATE:
                    return VIEW_TYPE_TEXT;
                default:
                    return VIEW_TYPE_ENGINE;
            }
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int viewType = holder.getItemViewType();
            return viewType == VIEW_TYPE_CHECK || viewType == VIEW_TYPE_ENGINE || viewType == VIEW_TYPE_VALUE || viewType == VIEW_TYPE_TEXT;
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
                case VIEW_TYPE_VALUE:
                    view = new TextSettingsCell(mContext);
                    break;
                default:
                    view = new TextCell(mContext);
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
                    cell.setTextAndCheck(LocaleController.getString(R.string.ForkTranslateEnginesEnabled), SharedConfig.forkTranslateEnabled, true);
                    break;
                }
                case VIEW_TYPE_HINT: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    if (position == 1) {
                        cell.setText(LocaleController.getString(R.string.ForkTranslateEnginesInfo));
                    } else if (position == 11) {
                        cell.setText(LocaleController.getString(R.string.ForkTranslateApiKeysInfo));
                    } else {
                        cell.setText(LocaleController.getString(R.string.ForkTranslateAutoInfo));
                    }
                    break;
                }
                case VIEW_TYPE_HEADER: {
                    HeaderCell cell = (HeaderCell) holder.itemView;
                    if (position == 2) {
                        cell.setText(LocaleController.getString(R.string.ForkTranslateEngineHeader));
                    } else if (position == 8) {
                        cell.setText(LocaleController.getString(R.string.ForkTranslateApiKeysHeader));
                    } else {
                        cell.setText(LocaleController.getString(R.string.ForkTranslateLanguageHeader));
                    }
                    break;
                }
                case VIEW_TYPE_ENGINE: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    int engine = ENGINE_ORDER[position - ROW_ENGINE_START];
                    cell.setTextAndCheck(LocaleController.getString(engineString(engine)), SharedConfig.forkTranslateEngine == engine, position - ROW_ENGINE_START < ENGINE_ORDER.length - 1);
                    break;
                }
                case VIEW_TYPE_VALUE: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    if (position == ROW_GOOGLE_KEY) {
                        cell.setTextAndValue(LocaleController.getString(R.string.ForkTranslateGoogleKey), keyValue(SharedConfig.forkTranslateGoogleKey), true);
                    } else if (position == ROW_DEEPL_KEY) {
                        cell.setTextAndValue(LocaleController.getString(R.string.ForkTranslateDeeplKey), keyValue(SharedConfig.forkTranslateDeeplKey), true);
                    } else {
                        cell.setTextAndValue(LocaleController.getString(R.string.ForkTranslateTargetLanguage), TranslateAlert2.languageName(TranslateAlert2.getToLanguage()), false);
                    }
                    break;
                }
                case VIEW_TYPE_TEXT: {
                    TextCell cell = (TextCell) holder.itemView;
                    cell.setText(LocaleController.getString(R.string.DoNotTranslate), false);
                    break;
                }
            }
        }

        private String keyValue(String key) {
            return key != null && key.trim().length() > 0 ? "••••••••" : LocaleController.getString(R.string.ForkTranslateKeyNotSet);
        }
    }
}
