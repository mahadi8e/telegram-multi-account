/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 *
 * TG fork: create / edit screen for one device-only ("local") folder.
 * A local folder has a name and an explicit list of chats, edited with the
 * stock chat picker (UsersSelectActivity). All data goes to
 * LocalFoldersController only — nothing is sent to Telegram servers.
 */

package org.telegram.ui;

import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LocalFolder;
import org.telegram.messenger.LocalFoldersController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleActionBarDoneButton;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.UserCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

public class LocalFilterCreateActivity extends BaseFragment {

    private final int folderId; // <= 0 means "new folder"
    private final long initialDialogId;

    private EditText nameEditText;
    private RecyclerListView listView;
    private ListAdapter adapter;
    private SimpleActionBarDoneButton doneItem;

    private String initialName = "";
    private final ArrayList<Long> memberIds = new ArrayList<>();

    private static final int VIEW_TYPE_ADD = 0;
    private static final int VIEW_TYPE_CHAT = 1;
    private static final int VIEW_TYPE_HINT = 2;
    private static final int VIEW_TYPE_DELETE = 3;

    private static final int done_button = 1;

    public LocalFilterCreateActivity(int folderId, long initialDialogId) {
        super();
        this.folderId = folderId;
        this.initialDialogId = initialDialogId;
    }

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        if (folderId > 0) {
            LocalFolder folder = LocalFoldersController.getInstance(currentAccount).getFolder(folderId);
            if (folder != null) {
                initialName = folder.name != null ? folder.name : "";
                memberIds.addAll(folder.dialogIds);
            }
        }
        if (initialDialogId != 0 && !memberIds.contains(initialDialogId)) {
            memberIds.add(initialDialogId);
        }
        return true;
    }

    @Override
    protected boolean useSimpleActionBar() {
        return true;
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);
        simpleActionBar.setBackButtonImage(R.drawable.ic_ab_back);
        simpleActionBar.setTitle(LocaleController.getString(folderId > 0 ? R.string.LocalFolder : R.string.LocalFolderNew));
        simpleActionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == done_button) {
                    processDone();
                }
            }
        });
        doneItem = simpleActionBar.createDoneButton(done_button, LocaleController.getString(folderId > 0 ? R.string.Save : R.string.Create));

        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        fragmentView = linearLayout;

        nameEditText = new EditText(context);
        nameEditText.setTextSize(16);
        nameEditText.setSingleLine(true);
        nameEditText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        nameEditText.setHint(LocaleController.getString(R.string.LocalFolderNameHint));
        nameEditText.setText(initialName);
        nameEditText.setSelection(nameEditText.getText().length());
        nameEditText.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        nameEditText.setHintTextColor(getThemedColor(Theme.key_windowBackgroundWhiteHintText));
        nameEditText.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        nameEditText.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), 0);
        linearLayout.addView(nameEditText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 54, 0, 0, 0, 0));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        linearLayout.addView(listView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1.0f));
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setOnItemClickListener((view, position, x, y) -> {
            if (adapter == null || position < 0 || position >= adapter.getItemCount()) {
                return;
            }
            int viewType = adapter.getItemViewType(position);
            if (viewType == VIEW_TYPE_ADD) {
                selectChats();
            } else if (viewType == VIEW_TYPE_CHAT) {
                int index = position - 1;
                if (index >= 0 && index < memberIds.size()) {
                    memberIds.remove(index);
                    adapter.notifyDataSetChanged();
                }
            } else if (viewType == VIEW_TYPE_DELETE) {
                showDeleteAlert();
            }
        });

        return fragmentView;
    }

    private void selectChats() {
        int flags = MessagesController.DIALOG_FILTER_FLAG_CONTACTS |
                MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS |
                MessagesController.DIALOG_FILTER_FLAG_GROUPS |
                MessagesController.DIALOG_FILTER_FLAG_CHANNELS |
                MessagesController.DIALOG_FILTER_FLAG_BOTS;
        UsersSelectActivity fragment = new UsersSelectActivity(true, new ArrayList<>(memberIds), flags);
        fragment.noChatTypes = true;
        fragment.setDelegate((ids, newFlags) -> {
            memberIds.clear();
            memberIds.addAll(ids);
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        });
        presentFragment(fragment);
    }

    private void showDeleteAlert() {
        if (getParentActivity() == null || folderId <= 0) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.LocalFolderDelete));
        builder.setMessage(LocaleController.getString(R.string.LocalFolderDeleteAlert));
        builder.setPositiveButton(LocaleController.getString(R.string.Delete), (dialog, which) -> {
            LocalFoldersController.getInstance(currentAccount).deleteFolder(folderId);
            finishFragment();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void processDone() {
        String name = nameEditText != null && nameEditText.getText() != null ? nameEditText.getText().toString().trim() : "";
        if (name.length() == 0) {
            if (nameEditText != null) {
                nameEditText.requestFocus();
            }
            return;
        }
        LocalFoldersController controller = LocalFoldersController.getInstance(currentAccount);
        if (folderId <= 0) {
            LocalFolder folder = controller.createFolder(name);
            if (folder != null) {
                controller.setFolderChats(folder.id, new ArrayList<>(memberIds));
            }
        } else {
            controller.renameFolder(folderId, name);
            controller.setFolderChats(folderId, new ArrayList<>(memberIds));
        }
        finishFragment();
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context mContext;

        public ListAdapter(Context context) {
            mContext = context;
        }

        private int hintPosition() {
            return memberIds.isEmpty() ? -1 : 1 + memberIds.size();
        }

        private int deletePosition() {
            if (folderId <= 0) {
                return -1;
            }
            return 1 + memberIds.size() + (memberIds.isEmpty() ? 0 : 1);
        }

        @Override
        public int getItemCount() {
            int count = 1 + memberIds.size();
            if (!memberIds.isEmpty()) {
                count++;
            }
            if (folderId > 0) {
                count++;
            }
            return count;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0) {
                return VIEW_TYPE_ADD;
            }
            if (position == hintPosition()) {
                return VIEW_TYPE_HINT;
            }
            if (position == deletePosition()) {
                return VIEW_TYPE_DELETE;
            }
            return VIEW_TYPE_CHAT;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() != VIEW_TYPE_HINT;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case VIEW_TYPE_CHAT:
                    view = new UserCell(mContext, 6, 0, false);
                    break;
                case VIEW_TYPE_HINT:
                    view = new TextInfoPrivacyCell(mContext);
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
                case VIEW_TYPE_ADD: {
                    TextCell cell = (TextCell) holder.itemView;
                    cell.setTextAndIcon(LocaleController.getString(R.string.LocalFolderAddChats), R.drawable.msg_addfolder, true);
                    break;
                }
                case VIEW_TYPE_CHAT: {
                    UserCell cell = (UserCell) holder.itemView;
                    int index = position - 1;
                    long dialogId = memberIds.get(index);
                    boolean divider = index != memberIds.size() - 1;
                    bindChatCell(cell, dialogId, divider);
                    break;
                }
                case VIEW_TYPE_HINT: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    cell.setText(LocaleController.getString(R.string.LocalFolderRemoveChatHint));
                    break;
                }
                case VIEW_TYPE_DELETE: {
                    TextCell cell = (TextCell) holder.itemView;
                    cell.setTextColor(getThemedColor(Theme.key_text_RedRegular));
                    cell.setText(LocaleController.getString(R.string.LocalFolderDelete), false);
                    break;
                }
            }
        }

        private void bindChatCell(UserCell cell, long dialogId, boolean divider) {
            if (DialogObject.isEncryptedDialog(dialogId)) {
                TLRPC.EncryptedChat encryptedChat = getMessagesController().getEncryptedChat(DialogObject.getEncryptedChatId(dialogId));
                TLRPC.User user = encryptedChat != null ? getMessagesController().getUser(encryptedChat.user_id) : null;
                cell.setData(user, encryptedChat, user != null ? ContactsController.formatName(user.first_name, user.last_name) : "", null, 0, divider);
            } else if (dialogId > 0) {
                TLRPC.User user = getMessagesController().getUser(dialogId);
                cell.setData(user, user != null ? ContactsController.formatName(user.first_name, user.last_name) : "", null, 0, divider);
            } else if (dialogId < 0) {
                TLRPC.Chat chat = getMessagesController().getChat(-dialogId);
                cell.setData(chat, chat != null && chat.title != null ? chat.title : "", null, 0, divider);
            } else {
                cell.setData(null, "", null, 0, divider);
            }
        }
    }
}
