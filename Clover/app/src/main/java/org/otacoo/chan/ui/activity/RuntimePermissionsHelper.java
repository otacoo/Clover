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
package org.otacoo.chan.ui.activity;

import static org.otacoo.chan.utils.AndroidUtils.getAppContext;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.otacoo.chan.R;
import org.otacoo.chan.utils.AndroidUtils;

public class RuntimePermissionsHelper {
    private static final int RUNTIME_PERMISSION_RESULT_ID = 3;

    private final ActivityCompat.OnRequestPermissionsResultCallback callbackActivity;

    private CallbackHolder pendingCallback;

    public RuntimePermissionsHelper(ActivityCompat.OnRequestPermissionsResultCallback callbackActivity) {
        this.callbackActivity = callbackActivity;
    }

    public boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(getAppContext(), permission) == PackageManager.PERMISSION_GRANTED;
    }

    public void requestPermission(String permission, Callback callback) {
        if (pendingCallback == null) {
            pendingCallback = new CallbackHolder();
            pendingCallback.callback = callback;
            pendingCallback.permission = permission;

            ActivityCompat.requestPermissions((Activity) callbackActivity, new String[]{permission}, RUNTIME_PERMISSION_RESULT_ID);

        }
    }

    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == RUNTIME_PERMISSION_RESULT_ID && pendingCallback != null) {
            boolean granted = false;

            for (int i = 0; i < permissions.length; i++) {
                String permission = permissions[i];
                if (permission.equals(pendingCallback.permission) && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    granted = true;
                    break;
                }
            }

            pendingCallback.callback.onRuntimePermissionResult(granted);
            pendingCallback = null;
        }
    }

    public void showPermissionRequiredDialog(final Context context, String title, String message, final PermissionRequiredDialogCallback callback) {
        new AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(false)
                .setNeutralButton(R.string.permission_app_settings, (dialog, which) -> {
                    callback.retryPermissionRequest();
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + context.getPackageName()));
                    AndroidUtils.openIntent(intent);
                })
                .setPositiveButton(R.string.permission_grant, (dialog, which) -> callback.retryPermissionRequest())
                .show();
    }

    public interface PermissionRequiredDialogCallback {
        void retryPermissionRequest();
    }

    private static class CallbackHolder {
        private Callback callback;
        private String permission;
    }

    public interface Callback {
        void onRuntimePermissionResult(boolean granted);
    }
}
