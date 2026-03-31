/*
 * Clover - 4chan browser https://github.com/Floens/Clover/
 * Copyright (C) 2014  Floens
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.otacoo.chan.core.saver;

import static org.otacoo.chan.core.storage.Storage.filterName;
import static org.otacoo.chan.utils.AndroidUtils.getAppContext;
import static org.otacoo.chan.utils.AndroidUtils.getString;

import android.Manifest;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import org.otacoo.chan.Chan;
import org.otacoo.chan.R;
import org.otacoo.chan.core.cache.FileCache;
import org.otacoo.chan.core.cache.FileCacheListener;
import org.otacoo.chan.core.cache.FileCacheProvider;
import org.otacoo.chan.core.model.PostImage;
import org.otacoo.chan.core.settings.ChanSettings;
import org.otacoo.chan.core.storage.Storage;
import org.otacoo.chan.core.storage.StorageFile;
import org.otacoo.chan.core.receiver.SaveCancelReceiver;
import org.otacoo.chan.ui.activity.RuntimePermissionsHelper;
import org.otacoo.chan.ui.service.SavingNotification;
import org.otacoo.chan.utils.AndroidUtils;
import org.otacoo.chan.utils.IOUtils;
import org.otacoo.chan.utils.Logger;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.inject.Inject;
import javax.inject.Singleton;

import de.greenrobot.event.EventBus;

@Singleton
public class ImageSaver implements ImageSaveTask.ImageSaveTaskCallback {
    private static final String TAG = "ImageSaver";
    private static final int NOTIFICATION_ID = 3;
    private static final int MAX_NAME_LENGTH = 50;
    private final NotificationManager notificationManager;
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private int doneTasks = 0;
    private int totalTasks = 0;
    private long currentProgress = 0;
    private long currentProgressMax = 0;
    private Toast toast;

    private final Storage storage;
    private final FileCache fileCache;

    @SuppressWarnings("this-escape")
    @Inject
    public ImageSaver(Storage storage, FileCache fileCache) {
        this.storage = storage;
        this.fileCache = fileCache;

        EventBus.getDefault().register(this);
        notificationManager = (NotificationManager) getAppContext().getSystemService(Context.NOTIFICATION_SERVICE);
        ensureChannels();
    }

    public void onEvent(SavingNotification.SavingCancelRequestMessage message) {
        cancelAll();
    }

    private void ensureChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            SavingNotification.ensureChannelsStatic(notificationManager);
        }
    }

    public String getSafeNameForFolder(String name) {
        String filtered = filterName(name);
        return filtered.substring(0, Math.min(filtered.length(), MAX_NAME_LENGTH));
    }

    public void share(PostImage postImage) {
        fileCache.downloadFile(postImage.imageUrl.toString(), new FileCacheListener() {
            @Override
            public void onSuccess(File file) {
                // Perform file operations on a background thread to prevent UI lag
                executor.execute(() -> shareFileCacheImage(postImage, file));
            }
        });
    }

    private void shareFileCacheImage(PostImage postImage, File file) {
        // Create a temporary file with the original name in the same cache directory
        // to ensure FileProvider can handle it and the receiving app sees the correct filename.
        File sharedFolder = new File(file.getParentFile(), "shared");
        if (!sharedFolder.exists()) {
            sharedFolder.mkdirs();
        }

        File sharedFile = new File(sharedFolder, postImage.filename + "." + postImage.extension);

        try {
            IOUtils.copyFile(file, sharedFile);
            file = sharedFile;
        } catch (IOException e) {
            // Fallback to the hashed file if copy fails
            Logger.e(TAG, "Error copying file for sharing", e);
        }

        final File finalFile = file;
        AndroidUtils.runOnUiThread(() -> {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Uri fileUri = FileCacheProvider.getUriForFile(finalFile);
            intent.putExtra(Intent.EXTRA_STREAM, fileUri);

            if (postImage.type == PostImage.Type.MOVIE) {
                intent.setType("video/*");
            } else {
                intent.setType("image/" + postImage.extension);
            }

            // ClipData is required for FileProvider permissions on modern Android when using a chooser
            intent.setClipData(ClipData.newRawUri(null, fileUri));

            AndroidUtils.openIntent(Intent.createChooser(intent, getString(R.string.action_share)));
        });
    }

    public void addTask(final ImageSaveTask task, final String[] folders) {
        addTasks(Collections.singletonList(task), folders, null);
    }

    public void addTasks(final List<ImageSaveTask> tasks, final String[] folders, Runnable success) {
        storage.prepareForSave(folders, () -> {
            if (!needsRequestExternalStoragePermission()) {
                // Move the actual task queueing to the executor thread to avoid blocking UI with Storage.obtain... calls
                executor.execute(() -> {
                    queueTasks(tasks, folders);
                    if (success != null) {
                        AndroidUtils.runOnUiThread(success);
                    }
                });
            } else {
                requestPermission(granted -> {
                    if (granted) {
                        executor.execute(() -> queueTasks(tasks, folders));
                    } else {
                        showStatusToast(null, false);
                    }
                });
            }
        });
    }

    private void queueTasks(final List<ImageSaveTask> tasks, final String[] folders) {
        totalTasks = 0;

        for (ImageSaveTask task : tasks) {
            PostImage postImage = task.getPostImage();
            String name = ChanSettings.saveOriginalFilename.get() ? postImage.originalName : postImage.filename;

            StorageFile file;
            try {
                // This call can be slow because it involves SAF queries
                file = storage.obtainStorageFileForName(folders, name + "." + postImage.extension);
            } catch (Exception e) {
                Logger.e(TAG, "Error obtaining storage file", e);
                file = null;
            }

            if (file == null) {
                Logger.e(TAG, "Failed to obtain a StorageFile for " + name);
                continue;
            }
            task.setDestination(file);
            task.setShowToast(tasks.size() == 1);

            task.setCallback(this);

            totalTasks++;
            executor.execute(task);
        }

        AndroidUtils.runOnUiThread(this::updateNotification);
    }

    @Override
    public void imageSaveTaskProgress(ImageSaveTask task, long downloaded, long total) {
        currentProgress = downloaded;
        currentProgressMax = total;
        AndroidUtils.runOnUiThread(this::updateNotification);
    }

    @Override
    public void imageSaveTaskFinished(ImageSaveTask task, boolean success) {
        doneTasks++;
        currentProgress = 0;
        currentProgressMax = 0;
        if (doneTasks == totalTasks) {
            totalTasks = 0;
            doneTasks = 0;
        }
        
        AndroidUtils.runOnUiThread(() -> {
            updateNotification();

            if (task.isMakeBitmap()) {
                showImageSaved(task);
            }
            if (task.isShowToast()) {
                showStatusToast(task, success);
            }
        });
    }

    private void cancelAll() {
        executor.shutdownNow();
        executor = Executors.newSingleThreadExecutor();

        totalTasks = 0;
        doneTasks = 0;
        updateNotification();
    }

    private static final int PROGRESS_NOTIFICATION_ID = 2;

    private void updateNotification() {
        if (totalTasks == 0) {
            notificationManager.cancel(PROGRESS_NOTIFICATION_ID);
        } else {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(
                    getAppContext(), SavingNotification.CHANNEL_ID_PROGRESS);
            builder.setSmallIcon(R.drawable.ic_stat_notify);
            builder.setContentTitle(getString(R.string.image_save_notification_downloading));
            builder.setContentText(getString(R.string.image_save_notification_cancel));
            builder.setOngoing(true);

            if (currentProgressMax > 0) {
                builder.setProgress(1000, (int) ((currentProgress * 1000) / currentProgressMax), false);
            } else {
                builder.setProgress(totalTasks, doneTasks, false);
            }

            builder.setContentInfo(doneTasks + "/" + totalTasks);

            Intent cancelIntent = new Intent(getAppContext(), SaveCancelReceiver.class);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    getAppContext(), 0, cancelIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.setContentIntent(pendingIntent);

            notificationManager.notify(PROGRESS_NOTIFICATION_ID, builder.build());
        }
    }

    private void showImageSaved(ImageSaveTask task) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(getAppContext(), SavingNotification.CHANNEL_ID_SAVING);
        builder.setSmallIcon(R.drawable.ic_stat_notify);
        builder.setContentTitle(getString(R.string.image_save_saved));
        String savedAs = getAppContext().getString(R.string.image_save_as, task.getDestination().name());
        builder.setContentText(savedAs);
        builder.setPriority(NotificationCompat.PRIORITY_HIGH);
        builder.setStyle(new NotificationCompat.BigPictureStyle()
                .bigPicture(task.getBitmap())
                .setSummaryText(savedAs));

        notificationManager.notify(NOTIFICATION_ID, builder.build());
    }

    private void showStatusToast(ImageSaveTask task, boolean success) {
        if (toast != null) {
            toast.cancel();
        }

        String text = success ?
                getAppContext().getString(R.string.image_save_as, task.getDestination().name()) :
                getString(R.string.image_save_failed);
        toast = Toast.makeText(getAppContext(), text, Toast.LENGTH_LONG);
        toast.show();
    }

    private boolean needsRequestExternalStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return false;
        }

        return !Chan.getInstance().getRuntimePermissionsHelper().hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE);
    }

    private void requestPermission(RuntimePermissionsHelper.Callback callback) {
        Chan.getInstance().getRuntimePermissionsHelper().requestPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE, callback);
    }

    public String currentStorageName() {
        return storage.currentStorageName();
    }
}
