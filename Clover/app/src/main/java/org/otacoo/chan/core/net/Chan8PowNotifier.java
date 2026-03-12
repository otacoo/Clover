package org.otacoo.chan.core.net;

import android.view.View;

import com.google.android.material.snackbar.Snackbar;

import org.otacoo.chan.utils.AndroidUtils;

import java.lang.ref.WeakReference;

public final class Chan8PowNotifier {
    private static volatile WeakReference<View> rootViewRef = new WeakReference<>(null);
    private static volatile Snackbar activeSnackbar = null;
    private static volatile boolean powInProgress = false;

    private Chan8PowNotifier() {}

    public static void setRootView(View v) {
        rootViewRef = new WeakReference<>(v);
    }

    public static boolean isPowInProgress() {
        return powInProgress;
    }

    public static void onPowStarted() {
        powInProgress = true;
        AndroidUtils.runOnUiThread(() -> {
            View root = rootViewRef.get();
            if (root == null) return;
            dismissActive();
            Snackbar sb = Snackbar.make(root, "Solving POWBlock check\u2026", Snackbar.LENGTH_INDEFINITE);
            AndroidUtils.fixSnackbarText(root.getContext(), sb);
            sb.show();
            activeSnackbar = sb;
        });
    }

    public static void onPowSolved() {
        powInProgress = false;
        AndroidUtils.runOnUiThread(() -> {
            dismissActive();
            View root = rootViewRef.get();
            if (root == null) return;
            Snackbar sb = Snackbar.make(root, "8chan POWBlock check complete.", 3000);
            AndroidUtils.fixSnackbarText(root.getContext(), sb);
            sb.show();
        });
    }

    public static void onPowFailed() {
        powInProgress = false;
        AndroidUtils.runOnUiThread(() -> {
            dismissActive();
            View root = rootViewRef.get();
            if (root == null) return;
            Snackbar sb = Snackbar.make(root,
                    "8chan POWBLock check failed \u2014 tap Login to verify manually.",
                    Snackbar.LENGTH_LONG);
            AndroidUtils.fixSnackbarText(root.getContext(), sb);
            sb.show();
        });
    }

    private static void dismissActive() {
        Snackbar s = activeSnackbar;
        if (s != null) {
            s.dismiss();
            activeSnackbar = null;
        }
    }
}
