/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 *
 * TG fork: settings screen for download tuning (Feature 6). This tree
 * already ships upstream's download-improvement stack (adaptive chunk
 * sizes, byte-budget scheduling, pause/resume that survives restarts,
 * FLOOD_WAIT handled natively, auto-download presets), so the fork adds
 * only measurement and one polite safeguard: a per-file download speed
 * log kept on this device, and an escalating extra wait when the server
 * repeatedly throttles one large download (implemented in
 * FileLoadOperation; it only ever waits longer than stock). The rows at
 * the bottom simply point at the stock screens for the rest.
 */

package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DownloadLogStorage;
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
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Locale;

public class ForkDownloadSettingsActivity extends BaseFragment {

    private static final int TYPE_SWITCH = 0;
    private static final int TYPE_HINT = 1;
    private static final int TYPE_HEADER = 2;
    private static final int TYPE_SUMMARY = 3;
    private static final int TYPE_ENTRY = 4;
    private static final int TYPE_CLEAR = 5;
    private static final int TYPE_AUTODOWNLOAD = 6;
    private static final int TYPE_SHADOW = 7;

    private static final int MAX_SHOWN_ENTRIES = 100;

    private static class Row {
        int type;
        int stringRes;
        DownloadLogStorage.Entry entry;
    }

    private RecyclerListView listView;
    private ListAdapter adapter;
    private final ArrayList<Row> rows = new ArrayList<>();
    private long[] successTotals = new long[3];

    public ForkDownloadSettingsActivity() {
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
        simpleActionBar.setTitle(LocaleController.getString(R.string.ForkDownloadSettings));
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
            if (adapter == null || position < 0 || position >= rows.size()) {
                return;
            }
            Row row = rows.get(position);
            switch (row.type) {
                case TYPE_SWITCH:
                    SharedConfig.setForkDownloadLogEnabled(!SharedConfig.forkDownloadLogEnabled);
                    if (view instanceof TextCheckCell) {
                        ((TextCheckCell) view).setChecked(SharedConfig.forkDownloadLogEnabled);
                    }
                    break;
                case TYPE_CLEAR:
                    confirmClearLog();
                    break;
                case TYPE_AUTODOWNLOAD:
                    // TG fork: Wi-Fi prefetch is stock auto-download; open its
                    // Wi-Fi preset screen instead of building a second system
                    presentFragment(new DataAutoDownloadActivity(1));
                    break;
            }
        });

        buildRows();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        buildRows();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void confirmClearLog() {
        if (getParentActivity() == null) {
            return;
        }
        showDialog(new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.ForkDownloadClearLog))
                .setMessage(LocaleController.getString(R.string.ForkDownloadClearLogConfirm))
                .setPositiveButton(LocaleController.getString(R.string.Delete), (dialog, which) -> {
                    DownloadLogStorage.getInstance(currentAccount).clear();
                    buildRows();
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create());
    }

    private void buildRows() {
        rows.clear();
        Row row;

        row = new Row();
        row.type = TYPE_SWITCH;
        rows.add(row);

        row = new Row();
        row.type = TYPE_HINT;
        row.stringRes = R.string.ForkDownloadLogInfo;
        rows.add(row);

        row = new Row();
        row.type = TYPE_SHADOW;
        rows.add(row);

        row = new Row();
        row.type = TYPE_HEADER;
        row.stringRes = R.string.ForkDownloadLogHeader;
        rows.add(row);

        DownloadLogStorage storage = DownloadLogStorage.getInstance(currentAccount);
        ArrayList<DownloadLogStorage.Entry> entries = storage.loadRecent(MAX_SHOWN_ENTRIES);
        successTotals = storage.getSuccessTotals();

        row = new Row();
        row.type = TYPE_SUMMARY;
        rows.add(row);

        for (int i = 0; i < entries.size(); i++) {
            row = new Row();
            row.type = TYPE_ENTRY;
            row.entry = entries.get(i);
            rows.add(row);
        }

        if (!entries.isEmpty()) {
            row = new Row();
            row.type = TYPE_CLEAR;
            rows.add(row);
        }

        row = new Row();
        row.type = TYPE_SHADOW;
        rows.add(row);

        row = new Row();
        row.type = TYPE_HEADER;
        row.stringRes = R.string.ForkDownloadStockHeader;
        rows.add(row);

        row = new Row();
        row.type = TYPE_AUTODOWNLOAD;
        rows.add(row);

        row = new Row();
        row.type = TYPE_HINT;
        row.stringRes = R.string.ForkDownloadAutoDownloadInfo;
        rows.add(row);

        row = new Row();
        row.type = TYPE_HINT;
        row.stringRes = R.string.ForkDownloadQueueInfo;
        rows.add(row);

        row = new Row();
        row.type = TYPE_HINT;
        row.stringRes = R.string.ForkDownloadBackoffInfo;
        rows.add(row);
    }

    private static String formatDuration(long durationMs) {
        if (durationMs < 1000) {
            return durationMs + " ms";
        } else if (durationMs < 60_000) {
            return String.format(Locale.US, "%.1f s", durationMs / 1000.0);
        }
        long seconds = durationMs / 1000;
        return String.format(Locale.US, "%d m %02d s", seconds / 60, seconds % 60);
    }

    private String entryValue(DownloadLogStorage.Entry entry) {
        StringBuilder sb = new StringBuilder();
        if (!entry.success) {
            sb.append(LocaleController.getString(entry.reason == 1 ? R.string.ForkDownloadStatusCancelled : R.string.ForkDownloadStatusFailed));
            sb.append(" · ");
        }
        sb.append(AndroidUtilities.formatFileSize(entry.sizeBytes));
        sb.append(" · ");
        sb.append(formatDuration(entry.durationMs));
        if (entry.success) {
            sb.append(" · ");
            sb.append(AndroidUtilities.formatFileSize(entry.avgBytesPerSec));
            sb.append("/s");
        }
        return sb.toString();
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context mContext;

        public ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).type;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int viewType = holder.getItemViewType();
            return viewType == TYPE_SWITCH || viewType == TYPE_CLEAR || viewType == TYPE_AUTODOWNLOAD;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case TYPE_SWITCH:
                    view = new TextCheckCell(mContext);
                    break;
                case TYPE_HINT:
                case TYPE_SUMMARY:
                    view = new TextInfoPrivacyCell(mContext);
                    break;
                case TYPE_HEADER:
                    view = new HeaderCell(mContext);
                    break;
                case TYPE_ENTRY:
                case TYPE_CLEAR:
                case TYPE_AUTODOWNLOAD:
                    view = new TextSettingsCell(mContext);
                    break;
                default:
                    view = new ShadowSectionCell(mContext);
                    break;
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            Row row = rows.get(position);
            switch (row.type) {
                case TYPE_SWITCH: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    cell.setTextAndCheck(LocaleController.getString(R.string.ForkDownloadLogEnabled), SharedConfig.forkDownloadLogEnabled, true);
                    break;
                }
                case TYPE_HINT: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    cell.setText(LocaleController.getString(row.stringRes));
                    break;
                }
                case TYPE_HEADER: {
                    HeaderCell cell = (HeaderCell) holder.itemView;
                    cell.setText(LocaleController.getString(row.stringRes));
                    break;
                }
                case TYPE_SUMMARY: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    long count = successTotals[0];
                    long totalDuration = successTotals[2];
                    if (count <= 0 || totalDuration <= 0) {
                        cell.setText(LocaleController.getString(R.string.ForkDownloadLogEmpty));
                    } else {
                        long avgBytesPerSec = successTotals[1] * 1000 / totalDuration;
                        cell.setText(LocaleController.formatString("ForkDownloadLogSummary", R.string.ForkDownloadLogSummary,
                                (int) count, AndroidUtilities.formatFileSize(avgBytesPerSec) + "/s"));
                    }
                    break;
                }
                case TYPE_ENTRY: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    cell.setTextAndValue(row.entry.fileName, entryValue(row.entry), true);
                    break;
                }
                case TYPE_CLEAR: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    cell.setText(LocaleController.getString(R.string.ForkDownloadClearLog), false);
                    break;
                }
                case TYPE_AUTODOWNLOAD: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    cell.setText(LocaleController.getString(R.string.ForkDownloadAutoDownloadRow), true);
                    break;
                }
            }
        }
    }
}
