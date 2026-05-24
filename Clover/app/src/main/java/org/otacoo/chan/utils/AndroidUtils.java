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
package org.otacoo.chan.utils;

import static org.otacoo.chan.ui.theme.ThemeHelper.theme;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.animation.LinearInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.browser.customtabs.CustomTabColorSchemeParams;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.app.ActivityManagerCompat;
import androidx.core.view.ViewCompat;

import com.google.android.material.snackbar.Snackbar;

import org.otacoo.chan.Chan;
import org.otacoo.chan.R;
import org.otacoo.chan.core.settings.ChanSettings;
import org.otacoo.chan.ui.theme.ThemeHelper;

import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.greenrobot.event.EventBus;

public class AndroidUtils {
    private static final String TAG = "AndroidUtils";

    private static final Map<String, Typeface> typefaceCache = new ConcurrentHashMap<>();

    public static Typeface ROBOTO_MEDIUM;
    public static Typeface ROBOTO_MEDIUM_ITALIC;
    public static Typeface ROBOTO_CONDENSED_REGULAR;

    @SuppressLint("StaticFieldLeak")
    private static Application application;
    private static ConnectivityManager connectivityManager;
    private static ActivityManager activityManager;

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static int activeSnackbars = 0;

    public static void init(Application application) {
        if (AndroidUtils.application == null) {
            AndroidUtils.application = application;

            ROBOTO_MEDIUM = getTypeface("Roboto-Medium.ttf");
            ROBOTO_MEDIUM_ITALIC = getTypeface("Roboto-MediumItalic.ttf");
            ROBOTO_CONDENSED_REGULAR = getTypeface("RobotoCondensed-Regular.ttf");

            connectivityManager = (ConnectivityManager)
                    application.getSystemService(Context.CONNECTIVITY_SERVICE);
            activityManager = (ActivityManager)
                    application.getSystemService(Context.ACTIVITY_SERVICE);
        }
    }

    public static Resources getRes() {
        return application.getResources();
    }

    public static Context getAppContext() {
        return application;
    }

    public static Context getUIContext() {
        Activity activity = Chan.getInstance().getTopActivity();
        if (activity != null) {
            return activity;
        }
        return getAppContext();
    }

    public static String getString(int res) {
        return getRes().getString(res);
    }

    @SuppressWarnings("deprecation")
    public static SharedPreferences getPreferences() {
        return PreferenceManager.getDefaultSharedPreferences(application);
    }

    public static SharedPreferences getPreferences(String name) {
        return application.getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    /**
     * Tries to open an app that can open the specified URL.<br>
     * If this app will open the link then show a chooser to the user without this app.<br>
     * Else allow the default logic to run with startActivity.
     *
     */
    public static String getYoutubeTitle(String videoUrl) {
        if (videoUrl == null || (!videoUrl.contains("youtube.com") && !videoUrl.contains("youtu.be"))) {
            return null;
        }
        try {
            String encodedUrl = java.net.URLEncoder.encode(videoUrl, "UTF-8");
            String oembedUrl = "https://www.youtube.com/oembed?url=" + encodedUrl + "&format=json";
            
            okhttp3.OkHttpClient client = org.otacoo.chan.Chan.injector().instance(okhttp3.OkHttpClient.class);
            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(oembedUrl)
                    .build();

            try (okhttp3.Response response = client.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    String json = response.body().string();
                    org.json.JSONObject obj = new org.json.JSONObject(json);
                    return obj.getString("title");
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }
    public static void openLink(String link) {
        PackageManager pm = getAppContext().getPackageManager();

        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(link));

        // Use queryIntentActivities to check if we are the default, but handle Android 11+ visibility
        List<ResolveInfo> resolveInfos = pm.queryIntentActivities(intent, 0);
        String myPackage = getAppContext().getPackageName();
        boolean thisAppIsDefault = false;
        
        ComponentName defaultComponent = intent.resolveActivity(pm);
        if (defaultComponent != null && defaultComponent.getPackageName().equals(myPackage)) {
            thisAppIsDefault = true;
        }

        if (!thisAppIsDefault) {
            openIntent(intent);
        } else {
            List<Intent> filteredIntents = new ArrayList<>(resolveInfos.size());
            for (ResolveInfo info : resolveInfos) {
                if (!info.activityInfo.packageName.equals(myPackage)) {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(link));
                    i.setPackage(info.activityInfo.packageName);
                    filteredIntents.add(i);
                }
            }

            if (!filteredIntents.isEmpty()) {
                Intent chooser = Intent.createChooser(filteredIntents.remove(filteredIntents.size() - 1), null);
                chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, filteredIntents.toArray(new Intent[0]));
                openIntent(chooser);
            } else {
                openIntentFailed();
            }
        }
    }

    public static void openLinkInBrowser(Activity activity, String link) {
        // Hack that's sort of the same as openLink
        // The link won't be opened in a custom tab if this app is the default handler for that link.
        // Manually check if this app opens it instead of a custom tab, and use the logic of
        // openLink to avoid that and show a chooser instead.
        boolean openWithCustomTabs = true;
        Intent urlIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(link));
        PackageManager pm = getAppContext().getPackageManager();
        ComponentName resolvedActivity = urlIntent.resolveActivity(pm);
        if (resolvedActivity != null) {
            openWithCustomTabs = !resolvedActivity.getPackageName().equals(getAppContext().getPackageName());
        }

        if (openWithCustomTabs) {
            CustomTabsIntent tabsIntent = new CustomTabsIntent.Builder()
                    .setDefaultColorSchemeParams(new CustomTabColorSchemeParams.Builder()
                            .setToolbarColor(theme().primaryColor.color)
                            .build())
                    .build();
            try {
                tabsIntent.launchUrl(activity, Uri.parse(link));
            } catch (ActivityNotFoundException e) {
                // Can't check it beforehand so catch the exception
                openIntentFailed();
            }
        } else {
            openLink(link);
        }
    }

    public static void shareLink(String link) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, link);
        Intent chooser = Intent.createChooser(intent, getRes().getString(R.string.action_share));
        openIntent(chooser);
    }

    public static void openIntent(Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        
        // Special case for APK installation to ensure it's handled by the package installer
        if ("application/vnd.android.package-archive".equals(intent.getType())) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }

        try {
            getAppContext().startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Logger.e(TAG, "No activity found to handle intent: " + intent, e);
            openIntentFailed();
        } catch (SecurityException e) {
            Logger.e(TAG, "Security exception while starting intent: " + intent, e);
            openIntentFailed();
        }
    }

    private static void openIntentFailed() {
        showThemedSnackbar(R.string.open_link_failed, Snackbar.LENGTH_LONG);
    }

    public static int getAttrColor(Context context, int attr) {
        int themeColor = ThemeHelper.getInstance().getTheme().getColorForAttr(attr);
        if (themeColor != 0) return themeColor;

        TypedArray typedArray = context.getTheme().obtainStyledAttributes(new int[]{attr});
        int color = typedArray.getColor(0, 0);
        typedArray.recycle();
        return color;
    }

    public static Drawable getAttrDrawable(Context context, int attr) {
        TypedArray typedArray = context.obtainStyledAttributes(new int[]{attr});
        Drawable drawable = typedArray.getDrawable(0);
        typedArray.recycle();
        return drawable;
    }

    public static boolean isTablet(Context context) {
        return context.getResources().getBoolean(R.bool.is_tablet);
    }

    public static int getDimen(Context context, int dimen) {
        return context.getResources().getDimensionPixelSize(dimen);
    }

    public static int dp(float dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getRes().getDisplayMetrics());
    }

    public static int sp(float sp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, getRes().getDisplayMetrics());
    }

    public static Typeface getTypeface(String name) {
        return typefaceCache.computeIfAbsent(name,
                key -> Typeface.createFromAsset(getRes().getAssets(), "font/" + key));
    }

    /**
     * Causes the runnable to be added to the message queue. The runnable will
     * be run on the ui thread.
     */
    public static void runOnUiThread(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            mainHandler.post(runnable);
        }
    }

    public static void runOnUiThread(Runnable runnable, long delay) {
        mainHandler.postDelayed(runnable, delay);
    }

    public static void requestKeyboardFocus(Dialog dialog, final View view) {
        view.requestFocus();
        dialog.setOnShowListener(dialog1 -> requestKeyboardFocus(view));
    }

    public static void requestKeyboardFocus(final View view) {
        InputMethodManager inputManager = (InputMethodManager) view.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (inputManager != null) {
            inputManager.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    public static void hideKeyboard(View view) {
        if (view == null || view.getWindowToken() == null) {
            return;
        }
        InputMethodManager inputManager = (InputMethodManager) view.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (inputManager != null) {
            inputManager.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    public static void requestViewAndKeyboardFocus(final View view) {
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        view.requestFocus();

        Runnable showKeyboard = () -> {
            InputMethodManager inputManager =
                    (InputMethodManager) view.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (inputManager != null) {
                inputManager.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
            }
        };

        if (view.hasWindowFocus()) {
            showKeyboard.run();
        } else {
            view.getViewTreeObserver().addOnWindowFocusChangeListener(new ViewTreeObserver.OnWindowFocusChangeListener() {
                @Override
                public void onWindowFocusChanged(boolean hasFocus) {
                    if (hasFocus) {
                        showKeyboard.run();
                        view.getViewTreeObserver().removeOnWindowFocusChangeListener(this);
                    }
                }
            });
        }
    }

    //https://programming.guide/java/formatting-byte-size-to-human-readable-format.html
    public static String getReadableFileSize(long bytes) {
        long absB = bytes == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(bytes);
        if (absB < 1024) {
            return bytes + " B";
        }
        long value = absB;
        CharacterIterator ci = new StringCharacterIterator("KMGTPE");
        for (int i = 40; i >= 0 && absB > 0xfffccccccccccccL >> i; i -= 10) {
            value >>= 10;
            ci.next();
        }
        value *= Long.signum(bytes);
        return String.format(Locale.ENGLISH, "%.1f %ciB", value / 1024.0, ci.current());
    }

    public static CharSequence ellipsize(CharSequence text, int max) {
        if (text.length() <= max) {
            return text;
        } else {
            return text.subSequence(0, max) + "\u2026";
        }
    }

    public interface OnMeasuredCallback {
        /**
         * Called when the layout is done.
         *
         * @param view same view as the argument.
         * @return true to continue with rendering, false to cancel and redo the layout.
         */
        boolean onMeasured(View view);
    }

    /**
     * Waits for a measure. Calls callback immediately if the view width and height are more than 0.
     * Otherwise it registers an onpredrawlistener.
     * <b>Warning: the view you give must be attached to the view root!</b>
     */
    public static void waitForMeasure(final View view, final OnMeasuredCallback callback) {
        if (view.getWindowToken() == null) {
            // If you call getViewTreeObserver on a view when it's not attached to a window will result in the creation of a temporarily viewtreeobserver.
            // This is almost always not what you want.
            throw new IllegalArgumentException("The view given to waitForMeasure is not attached to the window and does not have a ViewTreeObserver.");
        }

        waitForLayoutInternal(true, view.getViewTreeObserver(), view, callback);
    }

    /**
     * Always registers an onpredrawlistener.
     * <b>Warning: the view you give must be attached to the view root!</b>
     */
    public static void waitForLayout(final View view, final OnMeasuredCallback callback) {
        if (view.getWindowToken() == null) {
            // See comment above
            throw new IllegalArgumentException("The view given to waitForLayout is not attached to the window and does not have a ViewTreeObserver.");
        }

        waitForLayoutInternal(false, view.getViewTreeObserver(), view, callback);
    }

    /**
     * Always registers an onpredrawlistener. The given ViewTreeObserver will be used.
     */
    public static void waitForLayout(final ViewTreeObserver viewTreeObserver, final View view, final OnMeasuredCallback callback) {
        waitForLayoutInternal(false, viewTreeObserver, view, callback);
    }

    private static void waitForLayoutInternal(boolean returnIfNotZero, final ViewTreeObserver viewTreeObserver, final View view, final OnMeasuredCallback callback) {
        int width = view.getWidth();
        int height = view.getHeight();

        if (returnIfNotZero && width > 0 && height > 0) {
            callback.onMeasured(view);
        } else {
            viewTreeObserver.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    ViewTreeObserver usingViewTreeObserver = viewTreeObserver;
                    if (viewTreeObserver != view.getViewTreeObserver()) {
                        Logger.e(TAG, "view.getViewTreeObserver() is another viewtreeobserver! replacing with the new one");
                        usingViewTreeObserver = view.getViewTreeObserver();
                    }

                    if (usingViewTreeObserver.isAlive()) {
                        usingViewTreeObserver.removeOnPreDrawListener(this);
                    } else {
                        Logger.e(TAG, "ViewTreeObserver not alive, could not remove onPreDrawListener! This will probably not end well");
                    }

                    boolean ret;
                    try {
                        ret = callback.onMeasured(view);
                    } catch (Exception e) {
                        Logger.i(TAG, "Exception in onMeasured", e);
                        throw e;
                    }

                    if (!ret) {
                        Logger.d(TAG, "waitForLayout requested a re-layout by returning false");
                    }

                    return ret;
                }
            });
        }
    }

    public static void setRoundItemBackground(View view) {
        view.setBackground(getAttrDrawable(view.getContext(), android.R.attr.selectableItemBackgroundBorderless));
    }

    public static List<View> findViewsById(ViewGroup root, int id) {
        List<View> views = new ArrayList<>();
        int childCount = root.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = root.getChildAt(i);
            if (child instanceof ViewGroup) {
                views.addAll(findViewsById((ViewGroup) child, id));
            }

            if (child.getId() == id) {
                views.add(child);
            }
        }

        return views;
    }

    public static boolean removeFromParentView(View view) {
        if (view.getParent() instanceof ViewGroup && ((ViewGroup) view.getParent()).indexOfChild(view) >= 0) {
            ((ViewGroup) view.getParent()).removeView(view);
            return true;
        } else {
            return false;
        }
    }

    public static void setElevation(View view, float elevation) {
        view.setElevation(elevation);
    }

    public static void fixSnackbarText(Context context, Snackbar snackbar) {
        TextView textView = snackbar.getView().findViewById(com.google.android.material.R.id.snackbar_text);
        if (textView != null) {
            textView.setTextColor(0xffffffff);
        }
        snackbar.setActionTextColor(0xffffffff);
    }

    public static void showThemedSnackbar(int messageRes, int duration) {
        showThemedSnackbar(getString(messageRes), duration);
    }

    public static void showThemedSnackbar(String message, int duration) {
        showThemedSnackbar(null, message, duration, 0, null);
    }

    public static void showThemedSnackbar(View anchor, int messageRes, int duration) {
        showThemedSnackbar(anchor, getString(messageRes), duration);
    }

    public static void showThemedSnackbar(View anchor, String message, int duration) {
        showThemedSnackbar(anchor, message, duration, 0, null);
    }

    public static void showThemedSnackbar(View anchor, int messageRes, int duration, int actionRes, View.OnClickListener actionListener) {
        showThemedSnackbar(anchor, getString(messageRes), duration, actionRes, actionListener);
    }

    public static void showThemedSnackbar(View anchor, String message, int duration, int actionRes, View.OnClickListener actionListener) {
        View bestAnchor = anchor;
        if (bestAnchor == null) {
            Activity activity = Chan.getInstance().getTopActivity();
            if (activity != null) {
                ViewGroup content = activity.findViewById(android.R.id.content);
                if (content != null && content.getChildCount() > 0) {
                    bestAnchor = content.getChildAt(0);
                } else {
                    bestAnchor = content;
                }
            }
        }
        
        if (bestAnchor == null) return;
        
        Snackbar snackbar = Snackbar.make(bestAnchor, message, duration);
        if (actionRes != 0) {
            snackbar.setAction(actionRes, actionListener);
        }
        applyThemedStyle(snackbar, bestAnchor);
        snackbar.show();
    }

    public static void showThemedSnackbar(String message, int duration, int actionRes, View.OnClickListener actionListener) {
        showThemedSnackbar(null, message, duration, actionRes, actionListener);
    }

    public static boolean isAnySnackbarShowing() {
        return activeSnackbars > 0;
    }

    public static void notifySnackbarShowing(boolean showing) {
        if (showing) {
            activeSnackbars++;
        } else {
            activeSnackbars = Math.max(0, activeSnackbars - 1);
        }
        EventBus.getDefault().post(new SnackbarEvent(activeSnackbars > 0));
    }

    public static void applyThemedStyle(Snackbar snackbar, final View anchor) {
        final View snackbarView = snackbar.getView();
        final Context context = snackbarView.getContext();

        // Background and rounded corners
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        int bgColor = ThemeHelper.theme().isLightTheme ? ThemeHelper.theme().primaryColor.color : 0xFF323232;
        bg.setColor(bgColor);
        
        // Make the SnackbarLayout transparent and apply background to its inner content
        snackbarView.setBackgroundColor(Color.TRANSPARENT);
        androidx.core.view.ViewCompat.setElevation(snackbarView, 0);

        if (snackbarView instanceof ViewGroup && ((ViewGroup) snackbarView).getChildCount() > 0) {
            View contentView = ((ViewGroup) snackbarView).getChildAt(0);
            contentView.setBackground(bg);
            androidx.core.view.ViewCompat.setElevation(contentView, dp(6));
        } else {
            snackbarView.setBackground(bg);
        }

        snackbarView.setFitsSystemWindows(false);

        // Make SnackbarLayout span the full screen width and sit flush to the edges
        ViewGroup.LayoutParams params = snackbarView.getLayoutParams();
        params.width = ViewGroup.LayoutParams.MATCH_PARENT;
        snackbarView.setMinimumWidth(0);

        int gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
        if (params instanceof FrameLayout.LayoutParams) {
            ((FrameLayout.LayoutParams) params).gravity = gravity;
            ((FrameLayout.LayoutParams) params).setMargins(0, 0, 0, 0);
        } else if (params instanceof CoordinatorLayout.LayoutParams) {
            ((CoordinatorLayout.LayoutParams) params).gravity = gravity;
            ((CoordinatorLayout.LayoutParams) params).setMargins(0, 0, 0, 0);
        } else if (params instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) params).gravity = gravity;
            ((LinearLayout.LayoutParams) params).setMargins(0, 0, 0, 0);
        }
        snackbarView.setLayoutParams(params);

        int navBarHeight = 0;
        androidx.core.view.WindowInsetsCompat rootInsets = androidx.core.view.ViewCompat.getRootWindowInsets(anchor != null ? anchor : snackbarView);
        if (rootInsets != null) {
            navBarHeight = rootInsets.getSystemWindowInsetBottom();
        } else {
            int resourceId = context.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
            if (resourceId > 0) {
                navBarHeight = context.getResources().getDimensionPixelSize(resourceId);
            }
        }

        int newBottomMargin = dp(12);
        if (ChanSettings.toolbarBottom.get()) {
            newBottomMargin += context.getResources().getDimensionPixelSize(R.dimen.toolbar_height);
        }

        if (anchor != null) {
            int[] location = new int[2];
            anchor.getLocationOnScreen(location);
            int anchorBottom = location[1] + anchor.getHeight();

            int screenHeight = anchor.getRootView().getHeight();
            if (screenHeight == 0) {
                android.view.WindowManager wm = (android.view.WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
                if (wm != null) {
                    android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
                    wm.getDefaultDisplay().getRealMetrics(metrics);
                    screenHeight = metrics.heightPixels;
                }
            }

            int anchorDistanceFromBottom = screenHeight - anchorBottom;
            int targetMargin = navBarHeight + dp(12);
            if (ChanSettings.toolbarBottom.get()) {
                targetMargin += context.getResources().getDimensionPixelSize(R.dimen.toolbar_height);
            }

            int marginToApply = targetMargin - anchorDistanceFromBottom;
            if (marginToApply > newBottomMargin) {
                newBottomMargin = marginToApply;
            }
        }

        // Apply visual margins using padding on the transparent SnackbarLayout
        int sidePadding = dp(12);
        if (context.getResources().getConfiguration().smallestScreenWidthDp >= 600) {
            int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
            sidePadding = Math.max(dp(12), (screenWidth - dp(480)) / 2);
        }
        snackbarView.setPadding(sidePadding, dp(12), sidePadding, newBottomMargin);

        // Block Snackbar from erasing our margins with its default listener by consuming the insets
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(snackbarView, (v, insets) -> insets.consumeSystemWindowInsets());

        fixSnackbarText(context, snackbar);

        snackbar.addCallback(new Snackbar.Callback() {
            @Override
            public void onShown(Snackbar sb) {
                notifySnackbarShowing(true);
            }

            @Override
            public void onDismissed(Snackbar sb, int event) {
                notifySnackbarShowing(false);
            }
        });
    }

    public static class SnackbarEvent {
        public final boolean showing;
        public SnackbarEvent(boolean showing) { this.showing = showing; }
    }

    public static ConnectivityManager getConnectivityManager() {
        return connectivityManager;
    }

    public static boolean isConnected(int type) {
        Network network = connectivityManager.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
        if (capabilities == null) return false;
        if (type == 1) { // ConnectivityManager.TYPE_WIFI
            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } else if (type == 0) { // ConnectivityManager.TYPE_MOBILE
            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
        } else {
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        }
    }

    public static boolean enableHighEndAnimations() {
        return !ActivityManagerCompat.isLowRamDevice(activityManager);
    }

    @SuppressWarnings("deprecation")
    public static void animateStatusBar(Window window, boolean in, final int originalColor, int duration) {
        ValueAnimator statusBar = ValueAnimator.ofFloat(in ? 0f : 0.5f, in ? 0.5f : 0f);
        statusBar.addUpdateListener(animation -> {
            float progress = (float) animation.getAnimatedValue();
            if (progress == 0f) {
                window.setStatusBarColor(originalColor);
            } else {
                int r = (int) ((1f - progress) * Color.red(originalColor));
                int g = (int) ((1f - progress) * Color.green(originalColor));
                int b = (int) ((1f - progress) * Color.blue(originalColor));
                window.setStatusBarColor(Color.argb(255, r, g, b));
            }
        });
        statusBar.setDuration(duration).setInterpolator(new LinearInterpolator());
        statusBar.start();
    }
}
