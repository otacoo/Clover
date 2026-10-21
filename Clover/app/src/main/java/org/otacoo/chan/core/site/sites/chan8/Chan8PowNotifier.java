package org.otacoo.chan.core.site.sites.chan8;

import android.os.Handler;
import android.os.Looper;
import android.view.View;

import com.google.android.material.snackbar.Snackbar;

import org.otacoo.chan.utils.AndroidUtils;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicInteger;

public final class Chan8PowNotifier {
    private static volatile WeakReference<View> rootViewRef = new WeakReference<>(null);
    // The message that says the proof of work is being solved.
    private static volatile Snackbar solvingSnackbar = null;
    // The message that says something went wrong and the user has to do
    // something about it.
    private static volatile Snackbar activeSnackbar = null;
    // How many requests the user is waiting for. The message is only shown
    // while one of them is still going on.
    private static final AtomicInteger waitingCount = new AtomicInteger();
    // One-shot: fired (on a background thread) the next time verification succeeds.
    private static volatile Runnable pendingRetry = null;

    // The proof of work is solved in a moment, so the message is only shown
    // when it takes a while. Otherwise it would only flicker.
    private static final long SHOW_DELAY_MS = 5000;
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final Runnable showMessage = () -> {
        if (waitingCount.get() <= 0) return;
        View root = rootViewRef.get();
        if (root == null) return;
        AndroidUtils.runOnUiThread(() -> {
            View r = rootViewRef.get();
            if (r == null || waitingCount.get() <= 0) return;
            dismissSolving();
            Snackbar sb = Snackbar.make(r,
                    "Solving 8chan proof of work, please wait\u2026",
                    Snackbar.LENGTH_INDEFINITE);
            AndroidUtils.applyThemedStyle(sb, r);
            solvingSnackbar = sb;
            sb.show();
        });
    };

    private Chan8PowNotifier() {}

    public static void setRootView(View v) {
        rootViewRef = new WeakReference<>(v);
    }

    public static boolean isPowInProgress() {
        return waitingCount.get() > 0;
    }

    /**
     * A request the user is waiting for needs the proof of work. The message
     * is only shown when it takes a while.
     */
    public static void onPowStarted() {
        waitingCount.incrementAndGet();
        handler.removeCallbacks(showMessage);
        handler.postDelayed(showMessage, SHOW_DELAY_MS);
    }

    /**
     * The request is done. The message is only hidden when no other request is
     * waiting for it.
     */
    public static void onPowFinished() {
        int left = waitingCount.decrementAndGet();
        if (left < 0) {
            // More finished than started, the message is not wanted anyway.
            waitingCount.set(0);
            left = 0;
        }
        if (left > 0) return;
        handler.removeCallbacks(showMessage);
        AndroidUtils.runOnUiThread(Chan8PowNotifier::dismissSolving);
    }

    public static void scheduleRetryOnNextSolve(Runnable retry) {
        pendingRetry = retry;
    }

    public static void onPowSolved() {
        handler.removeCallbacks(showMessage);
        Runnable retry = pendingRetry;
        pendingRetry = null;
        if (retry != null) {
            Thread t = new Thread(retry);
            t.setName("pow-retry");
            t.setDaemon(true);
            t.start();
        }
        AndroidUtils.runOnUiThread(Chan8PowNotifier::dismissSolving);
    }

    public static void showRateLimit() {
        AndroidUtils.runOnUiThread(() -> {
            View root = rootViewRef.get();
            if (root == null) return;
            dismissAll();
            Snackbar sb = Snackbar.make(root,
                    "Rate limiting: too many requests, slow down please.",
                    Snackbar.LENGTH_SHORT);
            AndroidUtils.applyThemedStyle(sb, root);
            activeSnackbar = sb;
            sb.show();
        });
    }

    public static void onPowRateLimited() {
        handler.removeCallbacks(showMessage);
        AndroidUtils.runOnUiThread(() -> {
            dismissAll();
            View root = rootViewRef.get();
            if (root == null) return;
            Snackbar sb = Snackbar.make(root,
                    "8chan: rate limited (429) \u2014 too many requests, please wait.",
                    Snackbar.LENGTH_LONG);
            AndroidUtils.applyThemedStyle(sb, root);
            activeSnackbar = sb;
            sb.show();
        });
    }

    public static void onPowFailed() {
        handler.removeCallbacks(showMessage);
        AndroidUtils.runOnUiThread(() -> {
            dismissAll();
            View root = rootViewRef.get();
            if (root == null) return;
            Snackbar sb = Snackbar.make(root,
                    "8chan POWBlock check failed \u2014 tap Login to verify manually.",
                    Snackbar.LENGTH_LONG);
            AndroidUtils.applyThemedStyle(sb, root);
            activeSnackbar = sb;
            sb.show();
        });
    }

    private static void dismissSolving() {
        Snackbar s = solvingSnackbar;
        if (s != null) {
            s.dismiss();
            solvingSnackbar = null;
        }
    }

    private static void dismissAll() {
        dismissSolving();
        Snackbar s = activeSnackbar;
        if (s != null) {
            s.dismiss();
            activeSnackbar = null;
        }
    }
}
