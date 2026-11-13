/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 *
 * TG fork: settings screen for the deleted-message vault (Feature 5).
 * Holds the master switch (default OFF, behind a one-time disclosure),
 * the storage cap for copied media, current on-device usage, the
 * purge-all action, and the list of chats currently opted in (turning a
 * chat off here purges its retained copies, like in the chat itself).
 * All data stays on this device; nothing here talks to the server.
 */

package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ForkDeletedMessagesController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
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

public class ForkDeletedMessagesSettingsActivity extends BaseFragment {

    private static final int TYPE_SWITCH = 0;
    private static final int TYPE_HINT = 1;
    private static final int TYPE_HEADER = 2;
    private static final int TYPE_CAP = 3;
    private static final int TYPE_USAGE = 4;
    private static final int TYPE_PURGE = 5;
    private static final int TYPE_CHAT = 6;
    private static final int TYPE_SHADOW = 7;

    private static final int[] CAP_PRESETS_MB = new int[]{0, 50, 100, 200, 500};

    private static class Row {
        int type;
        int capMb;
        long dialogId;
        int stringRes;
    }

    private RecyclerListView listView;
    private ListAdapter adapter;
    private final ArrayList<Row> rows = new ArrayList<>();
    private int usageCount;
    private long usageBytes;

    public ForkDeletedMessagesSettingsActivity() {
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
        simpleActionBar.setTitle(LocaleController.getString(R.string.ForkDeletedMessagesSettings));
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
                    onToggleSwitch(view);
                    break;
                case TYPE_CAP:
                    SharedConfig.setForkDeletedMessagesCapMb(row.capMb);
                    ForkDeletedMessagesController.getInstance(currentAccount).enforceCapAsync();
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                    break;
                case TYPE_PURGE:
                    confirmPurgeAll();
                    break;
                case TYPE_CHAT:
                    confirmDisableChat(row.dialogId);
                    break;
            }
        });

        buildRows();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshUsageAndRows();
    }

    private void onToggleSwitch(View view) {
        if (SharedConfig.forkDeletedMessagesEnabled) {
            SharedConfig.setForkDeletedMessagesEnabled(false);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(false);
            }
            return;
        }
        if (!SharedConfig.forkDeletedMessagesConsent) {
            if (getParentActivity() == null) {
                return;
            }
            showDialog(new AlertDialog.Builder(getParentActivity())
                    .setTitle(LocaleController.getString(R.string.ForkDeletedMessagesConsentTitle))
                    .setMessage(LocaleController.getString(R.string.ForkDeletedMessagesInfo))
                    .setPositiveButton(LocaleController.getString(R.string.OK), (dialog, which) -> {
                        SharedConfig.setForkDeletedMessagesConsent(true);
                        SharedConfig.setForkDeletedMessagesEnabled(true);
                        if (adapter != null) {
                            adapter.notifyDataSetChanged();
                        }
                    })
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                    .create());
            return;
        }
        SharedConfig.setForkDeletedMessagesEnabled(true);
        if (view instanceof TextCheckCell) {
            ((TextCheckCell) view).setChecked(true);
        }
    }

    private void confirmPurgeAll() {
        if (getParentActivity() == null) {
            return;
        }
        showDialog(new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.ForkDeletedMessagesPurgeAll))
                .setMessage(LocaleController.getString(R.string.ForkDeletedMessagesPurgeConfirm))
                .setPositiveButton(LocaleController.getString(R.string.Delete), (dialog, which) ->
                        ForkDeletedMessagesController.getInstance(currentAccount).purgeAll(this::refreshUsageAndRows))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create());
    }

    private void confirmDisableChat(final long dialogId) {
        if (getParentActivity() == null) {
            return;
        }
        showDialog(new AlertDialog.Builder(getParentActivity())
                .setMessage(LocaleController.getString(R.string.ForkDeletedMessagesDisableChatConfirm))
                .setPositiveButton(LocaleController.getString(R.string.OK), (dialog, which) -> {
                    ForkDeletedMessagesController.getInstance(currentAccount).setOptedIn(dialogId, false);
                    refreshUsageAndRows();
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create());
    }

    private void refreshUsageAndRows() {
        final ForkDeletedMessagesController controller = ForkDeletedMessagesController.getInstance(currentAccount);
        controller.getQueue().postRunnable(() -> {
            final int count = controller.getTotalCount();
            final long bytes = controller.getTotalMediaSize();
            AndroidUtilities.runOnUIThread(() -> {
                usageCount = count;
                usageBytes = bytes;
                buildRows();
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
            });
        });
    }

    private void buildRows() {
        rows.clear();
        Row row;

        row = new Row();
        row.type = TYPE_SWITCH;
        rows.add(row);

        row = new Row();
        row.type = TYPE_HINT;
        row.stringRes = R.string.ForkDeletedMessagesInfo;
        rows.add(row);

        row = new Row();
        row.type = TYPE_SHADOW;
        rows.add(row);

        row = new Row();
        row.type = TYPE_HEADER;
        row.stringRes = R.string.ForkDeletedMessagesCapHeader;
        rows.add(row);

        for (int i = 0; i < CAP_PRESETS_MB.length; i++) {
            row = new Row();
            row.type = TYPE_CAP;
            row.capMb = CAP_PRESETS_MB[i];
            rows.add(row);
        }

        row = new Row();
        row.type = TYPE_SHADOW;
        rows.add(row);

        row = new Row();
        row.type = TYPE_USAGE;
        rows.add(row);

        row = new Row();
        row.type = TYPE_PURGE;
        rows.add(row);

        row = new Row();
        row.type = TYPE_SHADOW;
        rows.add(row);

        row = new Row();
        row.type = TYPE_HEADER;
        row.stringRes = R.string.ForkDeletedMessagesChatsHeader;
        rows.add(row);

        ArrayList<Long> dialogs = ForkDeletedMessagesController.getInstance(currentAccount).getOptedInDialogs();
        if (dialogs.isEmpty()) {
            row = new Row();
            row.type = TYPE_HINT;
            row.stringRes = R.string.ForkDeletedMessagesNoChats;
            rows.add(row);
        } else {
            for (int i = 0, size = dialogs.size(); i < size; i++) {
                row = new Row();
                row.type = TYPE_CHAT;
                row.dialogId = dialogs.get(i);
                rows.add(row);
            }
        }
    }

    private String dialogName(long dialogId) {
        if (dialogId > 0) {
            TLRPC.User user = getMessagesController().getUser(dialogId);
            if (user != null) {
                return UserObject.getUserName(user);
            }
        } else if (dialogId < 0) {
            TLRPC.Chat chat = getMessagesController().getChat(-dialogId);
            if (chat != null) {
                return chat.title;
            }
        }
        return Long.toString(dialogId);
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
            return viewType == TYPE_SWITCH || viewType == TYPE_CAP || viewType == TYPE_PURGE || viewType == TYPE_CHAT;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case TYPE_SWITCH:
                case TYPE_CAP:
                case TYPE_CHAT:
                    view = new TextCheckCell(mContext);
                    break;
                case TYPE_HINT:
                case TYPE_USAGE:
                    view = new TextInfoPrivacyCell(mContext);
                    break;
                case TYPE_HEADER:
                    view = new HeaderCell(mContext);
                    break;
                case TYPE_PURGE:
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
                    cell.setTextAndCheck(LocaleController.getString(R.string.ForkDeletedMessagesEnabled), SharedConfig.forkDeletedMessagesEnabled, true);
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
                case TYPE_CAP: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    String label = row.capMb == 0
                            ? LocaleController.getString(R.string.ForkDeletedMessagesTextOnly)
                            : AndroidUtilities.formatFileSize(row.capMb * 1024L * 1024L);
                    cell.setTextAndCheck(label, SharedConfig.forkDeletedMessagesCapMb == row.capMb, true);
                    break;
                }
                case TYPE_USAGE: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    cell.setText(LocaleController.formatString("ForkDeletedMessagesUsage", R.string.ForkDeletedMessagesUsage, usageCount, AndroidUtilities.formatFileSize(usageBytes)));
                    break;
                }
                case TYPE_PURGE: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    cell.setText(LocaleController.getString(R.string.ForkDeletedMessagesPurgeAll), false);
                    break;
                }
                case TYPE_CHAT: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    cell.setTextAndCheck(dialogName(row.dialogId), true, true);
                    break;
                }
            }
        }
    }
}
