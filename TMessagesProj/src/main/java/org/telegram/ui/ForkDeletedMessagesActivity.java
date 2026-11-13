/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 *
 * TG fork: per-chat vault screen for the deleted-message retention
 * feature (Feature 5). Lists the copies kept on this device for one
 * opted-in chat — sender, date, text/caption and any media that was
 * already downloaded when the message was deleted.
 *
 * The screen deliberately offers NO actions on a retained copy other
 * than viewing its media inside the app and deleting the copy: there is
 * no reply, no forward, no copy-text, no share and no save-to-gallery
 * path, so a retained message can never become visible to anyone else.
 */

package org.telegram.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DeletedMessagesStorage;
import org.telegram.messenger.ForkDeletedMessagesController;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;

public class ForkDeletedMessagesActivity extends BaseFragment {

    private final long dialogId;

    private RecyclerListView listView;
    private ListAdapter adapter;
    private ArrayList<DeletedMessagesStorage.Entry> entries = new ArrayList<>();
    private boolean loaded;

    private MediaPlayer mediaPlayer;
    private int playingMessageId = -1;

    public ForkDeletedMessagesActivity(Bundle args) {
        super(args);
        dialogId = args.getLong("dialog_id", 0);
    }

    @Override
    protected boolean useSimpleActionBar() {
        return true;
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);
        simpleActionBar.setBackButtonImage(R.drawable.ic_ab_back);
        simpleActionBar.setTitle(LocaleController.getString(R.string.ForkDeletedMessagesTitle));
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
            // The cell's delete button lives in the right-hand zone and
            // handles its own clicks; ignore those here.
            if (x > view.getWidth() - AndroidUtilities.dp(48)) {
                return;
            }
            if (view instanceof RetainedCell) {
                onEntryClicked(((RetainedCell) view).entry);
            }
        });

        loadEntries();
        return fragmentView;
    }

    @Override
    public void onFragmentDestroy() {
        stopPlayback();
        super.onFragmentDestroy();
    }

    private void loadEntries() {
        final ForkDeletedMessagesController controller = ForkDeletedMessagesController.getInstance(currentAccount);
        controller.getQueue().postRunnable(() -> {
            final ArrayList<DeletedMessagesStorage.Entry> result = controller.getRetainedMessages(dialogId);
            AndroidUtilities.runOnUIThread(() -> {
                entries = result;
                loaded = true;
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
            });
        });
    }

    private File vaultFile(DeletedMessagesStorage.Entry entry) {
        if (entry.mediaPath == null) {
            return null;
        }
        return new File(DeletedMessagesStorage.getVaultMediaDir(currentAccount), entry.mediaPath);
    }

    // The only interactions a retained copy supports: view its media
    // inside the app, or delete the copy (the cell's delete button).
    private void onEntryClicked(DeletedMessagesStorage.Entry entry) {
        if (entry == null) {
            return;
        }
        File file = vaultFile(entry);
        if (file == null || !file.exists()) {
            return;
        }
        if (entry.mediaKind == DeletedMessagesStorage.MEDIA_PHOTO) {
            showImage(file);
        } else if (entry.mediaKind == DeletedMessagesStorage.MEDIA_DOCUMENT) {
            String name = entry.mediaName != null ? entry.mediaName.toLowerCase(Locale.US) : "";
            if (name.endsWith(".mp4") || name.endsWith(".mov") || name.endsWith(".mkv") || name.endsWith(".webm")) {
                showVideo(file);
            } else if (name.endsWith(".ogg") || name.endsWith(".opus") || name.endsWith(".m4a") || name.endsWith(".mp3") || name.endsWith(".wav")) {
                toggleAudio(entry, file);
            }
        }
    }

    private void showImage(File file) {
        if (getParentActivity() == null) {
            return;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        BitmapFactory.Options options = new BitmapFactory.Options();
        int sample = 1;
        int maxSide = 2048;
        while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) {
            sample *= 2;
        }
        options.inSampleSize = sample;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (bitmap == null) {
            return;
        }
        ImageView imageView = new ImageView(getParentActivity());
        imageView.setImageBitmap(bitmap);
        imageView.setAdjustViewBounds(true);
        int padding = AndroidUtilities.dp(16);
        imageView.setPadding(padding, padding, padding, padding);
        showDialog(new AlertDialog.Builder(getParentActivity())
                .setView(imageView)
                .setPositiveButton(LocaleController.getString(R.string.Close), null)
                .create());
    }

    private void showVideo(File file) {
        if (getParentActivity() == null) {
            return;
        }
        VideoView videoView = new VideoView(getParentActivity());
        videoView.setVideoPath(file.getAbsolutePath());
        MediaController mediaController = new MediaController(getParentActivity());
        mediaController.setAnchorView(videoView);
        videoView.setMediaController(mediaController);
        FrameLayout container = new FrameLayout(getParentActivity());
        container.addView(videoView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 320));
        AlertDialog dialog = new AlertDialog.Builder(getParentActivity())
                .setView(container)
                .setPositiveButton(LocaleController.getString(R.string.Close), null)
                .create();
        dialog.setOnDismissListener(d -> {
            try {
                videoView.stopPlayback();
            } catch (Exception ignore) {
            }
        });
        showDialog(dialog);
        videoView.start();
    }

    private void toggleAudio(DeletedMessagesStorage.Entry entry, File file) {
        if (playingMessageId == entry.messageId && mediaPlayer != null) {
            stopPlayback();
            return;
        }
        stopPlayback();
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(file.getAbsolutePath());
            mediaPlayer.setOnCompletionListener(mp -> stopPlayback());
            mediaPlayer.prepare();
            mediaPlayer.start();
            playingMessageId = entry.messageId;
        } catch (Exception e) {
            stopPlayback();
        }
    }

    private void stopPlayback() {
        playingMessageId = -1;
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
            } catch (Exception ignore) {
            }
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    private void confirmDeleteEntry(final DeletedMessagesStorage.Entry entry) {
        if (getParentActivity() == null) {
            return;
        }
        showDialog(new AlertDialog.Builder(getParentActivity())
                .setMessage(LocaleController.getString(R.string.ForkDeletedMessagesDeleteOne))
                .setPositiveButton(LocaleController.getString(R.string.Delete), (dialog, which) -> {
                    if (playingMessageId == entry.messageId) {
                        stopPlayback();
                    }
                    ForkDeletedMessagesController.getInstance(currentAccount).deleteRetained(entry.dialogId, entry.messageId, this::loadEntries);
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create());
    }

    private String senderName(DeletedMessagesStorage.Entry entry) {
        if (entry.fromId > 0) {
            TLRPC.User user = getMessagesController().getUser(entry.fromId);
            if (user != null) {
                return UserObject.getUserName(user);
            }
        } else if (entry.fromId < 0) {
            TLRPC.Chat chat = getMessagesController().getChat(-entry.fromId);
            if (chat != null) {
                return chat.title;
            }
        }
        return "";
    }

    // ---- list ---------------------------------------------------------------

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private static final int VIEW_TYPE_ENTRY = 0;
        private static final int VIEW_TYPE_HINT = 1;

        private final Context mContext;

        public ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public int getItemCount() {
            if (!loaded) {
                return 0;
            }
            return entries.isEmpty() ? 1 : entries.size();
        }

        @Override
        public int getItemViewType(int position) {
            return entries.isEmpty() ? VIEW_TYPE_HINT : VIEW_TYPE_ENTRY;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == VIEW_TYPE_ENTRY;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            if (viewType == VIEW_TYPE_HINT) {
                view = new TextInfoPrivacyCell(mContext);
            } else {
                view = new RetainedCell(mContext);
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() == VIEW_TYPE_HINT) {
                ((TextInfoPrivacyCell) holder.itemView).setText(LocaleController.getString(R.string.ForkDeletedMessagesEmpty));
            } else {
                ((RetainedCell) holder.itemView).bind(entries.get(position));
            }
        }
    }

    // One retained copy. No context menu, no long-press actions — the copy
    // can only be viewed or deleted, by design. Layout is a horizontal
    // LinearLayout (avatar | vertical column | delete button) so row
    // heights follow the content naturally.
    private class RetainedCell extends LinearLayout {

        public DeletedMessagesStorage.Entry entry;

        private final ImageView avatarView;
        private final AvatarDrawable avatarDrawable;
        private final TextView nameView;
        private final TextView metaView;
        private final TextView textView;
        private final FrameLayout mediaContainer;
        private final ImageView deleteView;

        public RetainedCell(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(6), AndroidUtilities.dp(10));

            avatarDrawable = new AvatarDrawable();
            avatarView = new ImageView(context);
            avatarView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(AndroidUtilities.dp(44), AndroidUtilities.dp(44));
            avatarParams.gravity = Gravity.TOP;
            addView(avatarView, avatarParams);

            LinearLayout column = new LinearLayout(context);
            column.setOrientation(VERTICAL);
            LinearLayout.LayoutParams columnParams = new LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f);
            columnParams.leftMargin = AndroidUtilities.dp(10);
            addView(column, columnParams);

            nameView = new TextView(context);
            nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            nameView.setTypeface(AndroidUtilities.bold());
            nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            nameView.setSingleLine(true);
            nameView.setEllipsize(TextUtils.TruncateAt.END);
            column.addView(nameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            metaView = new TextView(context);
            metaView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            metaView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            metaView.setSingleLine(true);
            LinearLayout.LayoutParams metaParams = LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT);
            metaParams.topMargin = AndroidUtilities.dp(2);
            column.addView(metaView, metaParams);

            textView = new TextView(context);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            LinearLayout.LayoutParams textParams = LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT);
            textParams.topMargin = AndroidUtilities.dp(3);
            column.addView(textView, textParams);

            mediaContainer = new FrameLayout(context);
            LinearLayout.LayoutParams mediaParams = LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT);
            mediaParams.topMargin = AndroidUtilities.dp(6);
            column.addView(mediaContainer, mediaParams);

            deleteView = new ImageView(context);
            deleteView.setImageResource(R.drawable.msg_delete);
            deleteView.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            deleteView.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8));
            deleteView.setOnClickListener(v -> {
                if (entry != null) {
                    confirmDeleteEntry(entry);
                }
            });
            LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(AndroidUtilities.dp(36), AndroidUtilities.dp(36));
            deleteParams.gravity = Gravity.TOP;
            addView(deleteView, deleteParams);
        }

        public void bind(DeletedMessagesStorage.Entry e) {
            entry = e;
            Context context = getContext();

            // Avatar: initials only (no async avatar pipeline in the vault).
            TLRPC.User user = null;
            TLRPC.Chat chat = null;
            if (e.fromId > 0) {
                user = getMessagesController().getUser(e.fromId);
            } else if (e.fromId < 0) {
                chat = getMessagesController().getChat(-e.fromId);
            }
            if (user != null) {
                avatarDrawable.setInfo(currentAccount, user);
                avatarView.setImageDrawable(avatarDrawable);
                avatarView.setVisibility(VISIBLE);
            } else if (chat != null) {
                avatarDrawable.setInfo(currentAccount, chat);
                avatarView.setImageDrawable(avatarDrawable);
                avatarView.setVisibility(VISIBLE);
            } else {
                avatarView.setVisibility(GONE);
            }

            nameView.setText(senderName(e));

            String deletedLabel = LocaleController.getString(R.string.ForkDeletedMessagesDeletedLabel);
            String date = LocaleController.formatDateChat(e.date);
            SpannableStringBuilder meta = new SpannableStringBuilder(deletedLabel + " · " + date);
            meta.setSpan(new ForegroundColorSpan(Theme.getColor(Theme.key_text_RedRegular)), 0, deletedLabel.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            metaView.setText(meta);

            if (!TextUtils.isEmpty(e.text)) {
                textView.setVisibility(VISIBLE);
                textView.setText(e.text);
            } else {
                textView.setVisibility(GONE);
            }

            mediaContainer.removeAllViews();
            File file = vaultFile(e);
            boolean hasFile = file != null && file.exists();
            if (e.mediaKind == DeletedMessagesStorage.MEDIA_PHOTO && hasFile) {
                BackupImageView imageView = new BackupImageView(context);
                imageView.setRoundRadius(AndroidUtilities.dp(6));
                imageView.setImage(ImageLocation.getForPath(file.getAbsolutePath()), "240_240", (android.graphics.drawable.Drawable) null, e);
                mediaContainer.addView(imageView, LayoutHelper.createFrame(180, 180, Gravity.LEFT | Gravity.TOP));
            } else if (e.mediaKind != DeletedMessagesStorage.MEDIA_NONE) {
                TextView fileView = new TextView(context);
                fileView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                fileView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
                StringBuilder sb = new StringBuilder();
                if (e.mediaName != null) {
                    sb.append(e.mediaName);
                }
                long size = hasFile ? e.mediaSize : e.mediaTotalSize;
                if (size > 0) {
                    if (sb.length() > 0) {
                        sb.append(" · ");
                    }
                    sb.append(AndroidUtilities.formatFileSize(size));
                }
                if (!hasFile) {
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                    sb.append(LocaleController.getString(R.string.ForkDeletedMessagesNotDownloaded));
                }
                fileView.setText(sb.toString());
                mediaContainer.addView(fileView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP));
            }
        }
    }
}
