/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 *
 * TG fork: settings screen for the media compression presets (Feature 4).
 * Holds the feature's master switch and the default photo / video presets.
 * The presets only steer the fork's own attach-sheet row, the per-send
 * carrier on SendingMediaInfo and the video editor's initial step;
 * Telegram's stock quality paths, the send-as-file flow and the file-size
 * limits are untouched.
 */

package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

public class ForkCompressSettingsActivity extends BaseFragment {

    private RecyclerListView listView;
    private ListAdapter adapter;

    private static final int VIEW_TYPE_CHECK = 0;
    private static final int VIEW_TYPE_HINT = 1;
    private static final int VIEW_TYPE_HEADER = 2;
    private static final int VIEW_TYPE_PRESET = 3;
    private static final int VIEW_TYPE_SHADOW = 4;

    private static final int ROW_COUNT = 13;
    private static final int ROW_PHOTO_PRESET_START = 4;
    private static final int ROW_VIDEO_PRESET_START = 9;

    private static final int[] PRESET_ORDER = new int[]{
            SharedConfig.FORK_COMPRESS_PRESET_ORIGINAL,
            SharedConfig.FORK_COMPRESS_PRESET_MEDIUM,
            SharedConfig.FORK_COMPRESS_PRESET_SMALL
    };

    public ForkCompressSettingsActivity() {
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
        simpleActionBar.setTitle(LocaleController.getString(R.string.ForkCompressSettings));
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
                SharedConfig.setForkCompressEnabled(!SharedConfig.forkCompressEnabled);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(SharedConfig.forkCompressEnabled);
                }
            } else if (viewType == VIEW_TYPE_PRESET) {
                if (position >= ROW_PHOTO_PRESET_START && position < ROW_PHOTO_PRESET_START + PRESET_ORDER.length) {
                    SharedConfig.setForkCompressPhotoPreset(PRESET_ORDER[position - ROW_PHOTO_PRESET_START]);
                } else if (position >= ROW_VIDEO_PRESET_START && position < ROW_VIDEO_PRESET_START + PRESET_ORDER.length) {
                    SharedConfig.setForkCompressVideoPreset(PRESET_ORDER[position - ROW_VIDEO_PRESET_START]);
                }
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
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

    private static int presetString(int preset) {
        switch (preset) {
            case SharedConfig.FORK_COMPRESS_PRESET_MEDIUM:
                return R.string.ForkCompressPresetMedium;
            case SharedConfig.FORK_COMPRESS_PRESET_SMALL:
                return R.string.ForkCompressPresetSmall;
            default:
                return R.string.ForkCompressPresetOriginal;
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
                case 12:
                    return VIEW_TYPE_HINT;
                case 3:
                case 8:
                    return VIEW_TYPE_HEADER;
                case 2:
                case 7:
                    return VIEW_TYPE_SHADOW;
                default:
                    return VIEW_TYPE_PRESET;
            }
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int viewType = holder.getItemViewType();
            return viewType == VIEW_TYPE_CHECK || viewType == VIEW_TYPE_PRESET;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case VIEW_TYPE_CHECK:
                case VIEW_TYPE_PRESET:
                    view = new TextCheckCell(mContext);
                    break;
                case VIEW_TYPE_HINT:
                    view = new TextInfoPrivacyCell(mContext);
                    break;
                case VIEW_TYPE_HEADER:
                    view = new HeaderCell(mContext);
                    break;
                default:
                    view = new ShadowSectionCell(mContext);
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
                    cell.setTextAndCheck(LocaleController.getString(R.string.ForkCompressEnabled), SharedConfig.forkCompressEnabled, true);
                    break;
                }
                case VIEW_TYPE_HINT: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    if (position == 1) {
                        cell.setText(LocaleController.getString(R.string.ForkCompressInfo));
                    } else {
                        cell.setText(LocaleController.getString(R.string.ForkCompressVideoInfo));
                    }
                    break;
                }
                case VIEW_TYPE_HEADER: {
                    HeaderCell cell = (HeaderCell) holder.itemView;
                    if (position == 3) {
                        cell.setText(LocaleController.getString(R.string.ForkCompressPhotoHeader));
                    } else {
                        cell.setText(LocaleController.getString(R.string.ForkCompressVideoHeader));
                    }
                    break;
                }
                case VIEW_TYPE_PRESET: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    boolean photo = position < ROW_VIDEO_PRESET_START;
                    int start = photo ? ROW_PHOTO_PRESET_START : ROW_VIDEO_PRESET_START;
                    int preset = PRESET_ORDER[position - start];
                    int current = photo ? SharedConfig.forkCompressPhotoPreset : SharedConfig.forkCompressVideoPreset;
                    cell.setTextAndCheck(LocaleController.getString(presetString(preset)), current == preset, position - start < PRESET_ORDER.length - 1);
                    break;
                }
            }
        }
    }
}
