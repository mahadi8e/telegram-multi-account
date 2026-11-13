/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 *
 * TG fork: management screen for device-only ("local") folders and pins.
 * Contains the feature's master switch (SharedConfig.localFoldersEnabled)
 * and the list of local folders with create / edit / delete / drag-reorder.
 * Everything here operates on LocalFoldersController only — no server calls.
 */

package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LocalFolder;
import org.telegram.messenger.LocalFoldersController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

public class LocalFiltersSetupActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private RecyclerListView listView;
    private ListAdapter adapter;
    private ItemTouchHelper itemTouchHelper;
    private final ArrayList<LocalFolder> folders = new ArrayList<>();

    private static final int VIEW_TYPE_CHECK = 0;
    private static final int VIEW_TYPE_HINT = 1;
    private static final int VIEW_TYPE_HEADER = 2;
    private static final int VIEW_TYPE_FOLDER = 3;
    private static final int VIEW_TYPE_BUTTON = 4;
    private static final int VIEW_TYPE_SHADOW = 5;

    private static final int FOLDER_ROW_START = 3;

    public LocalFiltersSetupActivity() {
        super();
    }

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        getNotificationCenter().addObserver(this, NotificationCenter.localFoldersUpdated);
        reloadFolders();
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.localFoldersUpdated);
        super.onFragmentDestroy();
    }

    @Override
    protected boolean useSimpleActionBar() {
        return true;
    }

    private void reloadFolders() {
        folders.clear();
        folders.addAll(LocalFoldersController.getInstance(currentAccount).getFolders());
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);
        simpleActionBar.setBackButtonImage(R.drawable.ic_ab_back);
        simpleActionBar.setTitle(LocaleController.getString(R.string.LocalFoldersAndPins));
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
        itemTouchHelper = new ItemTouchHelper(new TouchHelperCallback());
        itemTouchHelper.attachToRecyclerView(listView);
        listView.setOnItemClickListener((view, position, x, y) -> {
            if (adapter == null || position < 0 || position >= adapter.getItemCount()) {
                return;
            }
            int viewType = adapter.getItemViewType(position);
            if (viewType == VIEW_TYPE_CHECK) {
                SharedConfig.toggleLocalFoldersEnabled();
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(SharedConfig.localFoldersEnabled);
                }
                // Let the dialogs screen rebuild its tab strip.
                getNotificationCenter().postNotificationName(NotificationCenter.localFoldersUpdated);
            } else if (viewType == VIEW_TYPE_FOLDER) {
                LocalFolder folder = getFolderAt(position);
                if (folder != null) {
                    presentFragment(new LocalFilterCreateActivity(folder.id, 0));
                }
            } else if (viewType == VIEW_TYPE_BUTTON) {
                presentFragment(new LocalFilterCreateActivity(0, 0));
            }
        });
        listView.setOnItemLongClickListener((view, position) -> {
            if (adapter == null || position < 0 || position >= adapter.getItemCount()) {
                return false;
            }
            if (adapter.getItemViewType(position) == VIEW_TYPE_FOLDER) {
                LocalFolder folder = getFolderAt(position);
                if (folder != null) {
                    showDeleteAlert(folder);
                    return true;
                }
            }
            return false;
        });

        return fragmentView;
    }

    private LocalFolder getFolderAt(int position) {
        int index = position - FOLDER_ROW_START;
        if (index < 0 || index >= folders.size()) {
            return null;
        }
        return folders.get(index);
    }

    private void showDeleteAlert(LocalFolder folder) {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.LocalFolderDelete));
        builder.setMessage(LocaleController.getString(R.string.LocalFolderDeleteAlert));
        builder.setPositiveButton(LocaleController.getString(R.string.Delete), (dialog, which) ->
                LocalFoldersController.getInstance(currentAccount).deleteFolder(folder.id));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    @Override
    public void onResume() {
        super.onResume();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.localFoldersUpdated) {
            reloadFolders();
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context mContext;

        public ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public int getItemCount() {
            // check + hint + header + folders + shadow + create button
            return FOLDER_ROW_START + folders.size() + 2;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0) {
                return VIEW_TYPE_CHECK;
            } else if (position == 1) {
                return VIEW_TYPE_HINT;
            } else if (position == 2) {
                return VIEW_TYPE_HEADER;
            } else if (position < FOLDER_ROW_START + folders.size()) {
                return VIEW_TYPE_FOLDER;
            } else if (position == FOLDER_ROW_START + folders.size()) {
                return VIEW_TYPE_SHADOW;
            }
            return VIEW_TYPE_BUTTON;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int viewType = holder.getItemViewType();
            return viewType == VIEW_TYPE_CHECK || viewType == VIEW_TYPE_FOLDER || viewType == VIEW_TYPE_BUTTON;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case VIEW_TYPE_CHECK:
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
                    view = new TextCell(mContext);
                    break;
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            int viewType = holder.getItemViewType();
            boolean lastFolder = position == FOLDER_ROW_START + folders.size() - 1;
            switch (viewType) {
                case VIEW_TYPE_CHECK: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    cell.setTextAndCheck(LocaleController.getString(R.string.LocalFoldersEnabled), SharedConfig.localFoldersEnabled, true);
                    break;
                }
                case VIEW_TYPE_HINT: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    cell.setText(LocaleController.getString(R.string.LocalFoldersInfo));
                    break;
                }
                case VIEW_TYPE_HEADER: {
                    HeaderCell cell = (HeaderCell) holder.itemView;
                    cell.setText(LocaleController.getString(R.string.LocalFolders));
                    break;
                }
                case VIEW_TYPE_FOLDER: {
                    LocalFolder folder = getFolderAt(position);
                    TextCell cell = (TextCell) holder.itemView;
                    if (folder != null) {
                        cell.setTextAndValue(folder.name, LocaleController.formatString("LocalFolderChatsCount", R.string.LocalFolderChatsCount, folder.dialogIds.size()), !lastFolder);
                    }
                    break;
                }
                case VIEW_TYPE_BUTTON: {
                    TextCell cell = (TextCell) holder.itemView;
                    cell.setTextAndIcon(LocaleController.getString(R.string.LocalFolderNew), R.drawable.msg_addfolder, false);
                    break;
                }
            }
        }

        // Called by the drag callback; only the local copy is reordered here,
        // the new order is persisted once the drag ends (persistOrder()).
        public void swapFolders(int fromPosition, int toPosition) {
            int fromIndex = fromPosition - FOLDER_ROW_START;
            int toIndex = toPosition - FOLDER_ROW_START;
            if (fromIndex < 0 || toIndex < 0 || fromIndex >= folders.size() || toIndex >= folders.size()) {
                return;
            }
            LocalFolder folder = folders.remove(fromIndex);
            folders.add(toIndex, folder);
            notifyItemMoved(fromPosition, toPosition);
        }

        public void persistOrder() {
            LocalFoldersController.getInstance(currentAccount).setFoldersOrder(new ArrayList<>(folders));
        }
    }

    public class TouchHelperCallback extends ItemTouchHelper.Callback {

        @Override
        public boolean isLongPressDragEnabled() {
            return true;
        }

        @Override
        public int getMovementFlags(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
            if (viewHolder.getItemViewType() != VIEW_TYPE_FOLDER) {
                return makeMovementFlags(0, 0);
            }
            return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
        }

        @Override
        public boolean onMove(RecyclerView recyclerView, RecyclerView.ViewHolder source, RecyclerView.ViewHolder target) {
            if (target.getItemViewType() != VIEW_TYPE_FOLDER) {
                return false;
            }
            adapter.swapFolders(source.getAdapterPosition(), target.getAdapterPosition());
            return true;
        }

        @Override
        public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {

        }

        @Override
        public void clearView(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
            super.clearView(recyclerView, viewHolder);
            adapter.persistOrder();
        }
    }
}
