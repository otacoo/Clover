package org.otacoo.chan.core.site.sites.chan8;

import android.view.View;

import com.google.android.material.snackbar.Snackbar;

import org.otacoo.chan.utils.AndroidUtils;

import java.lang.ref.WeakReference;

public final class Chan8PowNotifier {
    private static volatile WeakReference<View> rootViewRef = new WeakReference<>(null);
    private static volatile Snackbar activeSnackbar = null;
    private static volatile boolean powInProgress = false;
    // One-shot: fired (on a background thread) the next time verification succeeds.
    private static volatile Runnable pendingRetry = null;

    private Chan8PowNotifier() {}

    public static void setRootView(View v) {
        rootViewRef = new WeakReference<>(v);
    }

    public static boolean isPowInProgress() {
        return powInProgress;
    }

    public static void onPowStarted() {
        powInProgress = true;
    }

    public static void scheduleRetryOnNextSolve(Runnable retry) {
        pendingRetry = retry;
    }

    public static void onPowSolved() {
        powInProgress = false;
        Runnable retry = pendingRetry;
        pendingRetry = null;
        if (retry != null) {
            Thread t = new Thread(retry);
            t.setName("pow-retry");
            t.setDaemon(true);
            t.start();
        }
        AndroidUtils.runOnUiThread(() -> {
            dismissActive();
        });
    }

    public static void showRateLimit() {
        AndroidUtils.runOnUiThread(() -> {
            View root = rootViewRef.get();
            if (root == null) return;
            Snackbar sb = Snackbar.make(root,
                    "Rate limiting: too many requests, slow down please.",
                    Snackbar.LENGTH_SHORT);
            AndroidUtils.fixSnackbarText(root.getContext(), sb);
            sb.show();
        });
    }

    public static void onPowRateLimited() {
        powInProgress = false;
        AndroidUtils.runOnUiThread(() -> {
            dismissActive();
            View root = rootViewRef.get();
            if (root == null) return;
            Snackbar sb = Snackbar.make(root,
                    "8chan: rate limited (429) \u2014 too many requests, please wait.",
                    Snackbar.LENGTH_LONG);
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
                    "8chan POWBlock check failed \u2014 tap Login to verify manually.",
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
