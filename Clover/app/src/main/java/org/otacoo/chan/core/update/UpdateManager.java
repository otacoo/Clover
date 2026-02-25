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
package org.otacoo.chan.core.update;

import static org.otacoo.chan.Chan.inject;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.text.TextUtils;

import androidx.core.content.FileProvider;

import com.android.volley.RequestQueue;

import org.otacoo.chan.BuildConfig;
import org.otacoo.chan.core.cache.FileCache;
import org.otacoo.chan.core.cache.FileCacheListener;
import org.otacoo.chan.core.net.UpdateApiRequest;
import org.otacoo.chan.core.settings.ChanSettings;
import org.otacoo.chan.utils.AndroidUtils;
import org.otacoo.chan.utils.IOUtils;
import org.otacoo.chan.utils.Logger;
import org.otacoo.chan.utils.Time;

import java.io.File;
import java.io.IOException;

import javax.inject.Inject;

import okhttp3.HttpUrl;

/**
 * Calls the update API and downloads and requests installs of APK files.
 * <p>The APK files are downloaded to a dedicated updates directory, and the default APK install
 * screen is launched after downloading.
 */
public class UpdateManager {
    public static final long DEFAULT_UPDATE_CHECK_INTERVAL_MS = 1000 * 60 * 60 * 24 * 5; // 5 days

    private static final String TAG = "UpdateManager";
    private static final String UPDATE_FILENAME = "update.apk";
    private static final String LEGACY_UPDATE_FILENAME = "Clover_update.apk";

    @Inject
    RequestQueue volleyRequestQueue;

    @Inject
    FileCache fileCache;

    private UpdateCallback callback;

    public UpdateManager(UpdateCallback callback) {
        inject(this);
        this.callback = callback;
    }

    public boolean isUpdatingAvailable() {
        return !TextUtils.isEmpty(BuildConfig.UPDATE_API_ENDPOINT);
    }

    public void runUpdateApi(final boolean manual) {
        if (!manual) {
            long lastUpdateTime = ChanSettings.updateCheckTime.get();
            long interval = ChanSettings.updateCheckInterval.get();
            long now = Time.get();
            long delta = (lastUpdateTime + interval) - now;
            if (delta > 0) {
                return;
            } else {
                ChanSettings.updateCheckTime.set(now);
            }
        }

        Logger.d(TAG, "Calling update API");
        volleyRequestQueue.add(new UpdateApiRequest(response -> {
            if (!processUpdateApiResponse(response) && manual) {
                callback.onManualCheckNone();
            }
        }, error -> {
            Logger.e(TAG, "Failed to process API call for updating", error);

            if (manual) {
                callback.onManualCheckFailed();
            }
        }));
    }

    private boolean processUpdateApiResponse(UpdateApiRequest.UpdateApiResponse response) {
        if (response.newerApiVersion) {
            Logger.e(TAG, "API endpoint reports a higher API version than we support, " +
                    "aborting update check.");

            // ignore
            return false;
        }

        if (response.checkIntervalMs != 0) {
            ChanSettings.updateCheckInterval.set(response.checkIntervalMs);
        }

        for (UpdateApiRequest.UpdateApiMessage message : response.messages) {
            if (processUpdateMessage(message)) {
                return true;
            }
        }

        return false;
    }

    private boolean processUpdateMessage(UpdateApiRequest.UpdateApiMessage message) {
        if (isMessageRelevantForThisVersion(message)) {
            if (message.type.equals(UpdateApiRequest.TYPE_UPDATE)) {
                if (message.apkUrl == null) {
                    Logger.i(TAG, "Update available but none for this build flavor.");
                    // Not for this flavor, discard.
                    return false;
                }

                Logger.i(TAG, "Update available (" + message.code + ") with url \"" +
                        message.apkUrl + "\".");
                callback.showUpdateAvailableDialog(message);
                return true;
            }
        }

        return false;
    }

    private boolean isMessageRelevantForThisVersion(UpdateApiRequest.UpdateApiMessage message) {
        if (message.code != 0) {
            if (message.code <= BuildConfig.VERSION_CODE) {
                Logger.d(TAG, "No newer version available (" +
                        BuildConfig.VERSION_CODE + " >= " + message.code + ").");
                // Our code is newer than the message
                return false;
            } else {
                return true;
            }
        } else if (message.hash != null) {
            return !message.hash.equals(BuildConfig.BUILD_HASH);
        } else {
            Logger.w(TAG, "No code or hash found");
            return false;
        }
    }

    /**
     * Install the APK file specified in {@code update}.
     *
     * @param update update with apk details.
     */
    public void doUpdate(Update update) {
        // Clean up legacy update file if it exists in Downloads
        File legacyFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), LEGACY_UPDATE_FILENAME);
        if (legacyFile.exists()) {
            legacyFile.delete();
        }

        fileCache.downloadFile(update.apkUrl.toString(), new FileCacheListener() {
            @Override
            public void onProgress(long downloaded, long total) {
                callback.onUpdateDownloadProgress(downloaded, total);
            }

            @Override
            public void onSuccess(File file) {
                callback.onUpdateDownloadSuccess();
                prepareAndInstall(file);
            }

            @Override
            public void onFail(boolean notFound) {
                callback.onUpdateDownloadFailed();
            }

            @Override
            public void onCancel() {
                callback.onUpdateDownloadFailed();
            }
        });
    }

    public void retry(Install install) {
        installApk(install);
    }

    private void prepareAndInstall(File downloadedFile) {
        Context context = AndroidUtils.getAppContext();
        File updatesDir = new File(context.getCacheDir(), "updates");
        if (!updatesDir.exists()) {
            updatesDir.mkdirs();
        }

        File installFile = new File(updatesDir, UPDATE_FILENAME);
        if (installFile.exists()) {
            installFile.delete();
        }

        try {
            IOUtils.copyFile(downloadedFile, installFile);
        } catch (IOException e) {
            Logger.e(TAG, "Failed to copy APK to updates directory", e);
            callback.onUpdateDownloadMoveFailed();
            return;
        }

        installApk(new Install(installFile));
    }

    private void installApk(Install install) {
        // First open the dialog that asks to retry and calls this method again.
        callback.openUpdateRetryDialog(install);

        Context context = AndroidUtils.getAppContext();
        if (install.installFile == null || !install.installFile.exists()) {
            Logger.e(TAG, "installApk: file does not exist");
            return;
        }

        Uri contentUri = FileProvider.getUriForFile(context,
                BuildConfig.APPLICATION_ID + ".fileprovider",
                install.installFile);

        // Then launch the APK install intent.
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setDataAndType(contentUri, "application/vnd.android.package-archive");

        callback.onUpdateOpenInstallScreen(intent);
    }

    public static class Update {
        private HttpUrl apkUrl;

        public Update(HttpUrl apkUrl) {
            this.apkUrl = apkUrl;
        }
    }

    public static class Install {
        private File installFile;

        public Install(File installFile) {
            this.installFile = installFile;
        }
    }

    public interface UpdateCallback {
        void onManualCheckNone();

        void onManualCheckFailed();

        void showUpdateAvailableDialog(UpdateApiRequest.UpdateApiMessage message);

        void onUpdateDownloadProgress(long downloaded, long total);

        void onUpdateDownloadSuccess();

        void onUpdateDownloadFailed();

        void onUpdateDownloadMoveFailed();

        void onUpdateOpenInstallScreen(Intent intent);

        void openUpdateRetryDialog(Install install);
    }
}
