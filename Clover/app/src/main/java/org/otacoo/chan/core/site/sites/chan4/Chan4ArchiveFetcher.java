/*
 * Clover - 4chan browser
 * Copyright (C) 2014  Floens https://github.com/Floens/Clover/
 * Copyright (C) 2026  otacoo https://github.com/otacoo/Clover/
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
package org.otacoo.chan.core.site.sites.chan4;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.otacoo.chan.Chan;
import org.otacoo.chan.core.manager.ArchivesManager;
import org.otacoo.chan.core.model.Post;
import org.otacoo.chan.core.model.orm.Loadable;
import org.otacoo.chan.core.site.parser.PostParser;
import org.otacoo.chan.ui.activity.ActivityResultHelper;
import org.otacoo.chan.utils.AndroidUtils;
import org.otacoo.chan.utils.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Fetches a 404'd thread's posts from fuuka/foolfuuka archives.
 * <p>
 * Archives are tried in order (the last working one for this thread first).
 * A plain OkHttp request is attempted first; when the archive answers with a
 * Cloudflare challenge, the request is retried inside a hidden WebView: the
 * managed Turnstile challenge auto-solves there and the API fetch then runs
 * in the WebView's own network session, where the cf_clearance cookie and the
 * browser fingerprint actually match.
 */
public class Chan4ArchiveFetcher {
    private static final String TAG = "Chan4ArchiveFetcher";
    private static final long FETCH_TIMEOUT_MS = 20000;
    private static final int MAX_ARCHIVE_ATTEMPTS = 5;

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final Map<String, String> rememberedArchives = new HashMap<>();
    private static final Set<String> webViewOnlyDomains = new HashSet<>();

    public interface Callback {
        void onSuccess(List<Post> posts);

        void onFailure(String message);
    }

    /**
     * Presented on the main thread when a Cloudflare challenge cannot be
     * solved automatically: the caller should show the user a WebView on the
     * archive domain so the challenge can be solved manually, then call
     * {@code onUnlocked} (challenge solved) or {@code onCancelled}.
     */
    public interface UnlockPresenter {
        void present(String domain, Runnable onUnlocked, Runnable onCancelled);
    }

    private static class ChallengeException extends IOException {
        ChallengeException(String domain) {
            super("Cloudflare challenge");
        }
    }

    /**
     * Fetches the archived posts of a thread, iterating the board's archives
     * (last working one first). Cloudflare-protected archives are fetched
     * through a hidden WebView session; when the challenge can't auto-solve,
     * {@code presenter} is used to ask the user to solve it manually.
     */
    public static void fetchThreadPosts(Loadable loadable, Callback callback, UnlockPresenter presenter) {
        EXECUTOR.execute(() -> fetchThreadPostsBackground(loadable, callback, presenter));
    }

    public static void fetchThreadPosts(Loadable loadable, Callback callback) {
        fetchThreadPosts(loadable, callback, null);
    }

    private static void fetchThreadPostsBackground(Loadable loadable, Callback callback, UnlockPresenter presenter) {
        String boardCode = loadable.boardCode;
        List<ArchivesManager.ArchiveSite> archives = ArchivesManager.getInstance().archiveSitesForBoard(boardCode);
        if (archives.isEmpty()) {
            postFailure(callback, "No archives available for this board");
            return;
        }

        // The last archive that worked for this thread goes first.
        String key = boardCode + ":" + loadable.no;
        String remembered = rememberedArchives.get(key);
        List<ArchivesManager.ArchiveSite> ordered = new ArrayList<>();
        if (remembered != null) {
            for (ArchivesManager.ArchiveSite site : archives) {
                if (site.domain.equals(remembered)) {
                    ordered.add(site);
                }
            }
        }
        for (ArchivesManager.ArchiveSite site : archives) {
            if (!site.domain.equals(remembered)) {
                ordered.add(site);
            }
        }

        int attempts = Math.min(ordered.size(), MAX_ARCHIVE_ATTEMPTS);
        String lastError = null;
        boolean unlockCancelled = false;
        for (int i = 0; i < attempts; i++) {
            ArchivesManager.ArchiveSite site = ordered.get(i);
            String json;
            try {
                json = fetchJsonForSite(site, boardCode, loadable.no);
            } catch (ChallengeException e) {
                // The hidden WebView couldn't auto-solve the challenge: ask
                // the user to solve it in a visible WebView and retry.
                if (presenter == null || unlockCancelled || !presentUnlockAndWait(presenter, site.domain)) {
                    unlockCancelled = true;
                    lastError = site.name + ": " + e.getMessage();
                    continue;
                }
                try {
                    json = fetchJsonInWebView(site, boardCode, loadable.no);
                } catch (Exception e2) {
                    lastError = site.name + ": " + e2.getMessage();
                    continue;
                }
            } catch (Exception e) {
                lastError = site.name + ": " + e.getMessage();
                Logger.w(TAG, "Archive " + site.name + " failed: " + e.getMessage());
                continue;
            }

            try {
                List<Post> posts = parsePosts(loadable, site, json);
                if (posts.isEmpty()) {
                    throw new IOException("Archive returned no posts");
                }
                rememberedArchives.put(key, site.domain);
                postSuccess(callback, posts);
                return;
            } catch (Exception e) {
                lastError = site.name + ": " + e.getMessage();
                Logger.w(TAG, "Archive " + site.name + " failed: " + e.getMessage());
            }
        }

        postFailure(callback, "Could not fetch thread from archives" +
                (lastError != null ? " (" + lastError + ")" : ""));
    }

    // Blocks the worker thread while the visible unlock screen is shown.
    private static boolean presentUnlockAndWait(UnlockPresenter presenter, String domain) {
        final Object lock = new Object();
        final boolean[] unlocked = {false};
        final boolean[] done = {false};
        AndroidUtils.runOnUiThread(() -> presenter.present(domain,
                () -> {
                    synchronized (lock) {
                        unlocked[0] = true;
                        done[0] = true;
                        lock.notifyAll();
                    }
                },
                () -> {
                    synchronized (lock) {
                        done[0] = true;
                        lock.notifyAll();
                    }
                }));
        synchronized (lock) {
            while (!done[0]) {
                try {
                    lock.wait(500);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        return unlocked[0];
    }

    // Tries a plain OkHttp request first; on failure (usually a Cloudflare
    // challenge) the request is retried inside a hidden WebView whose network
    // session owns the challenge clearance.
    private static String fetchJsonForSite(ArchivesManager.ArchiveSite site, String boardCode, int threadNo) throws Exception {
        if (!webViewOnlyDomains.contains(site.domain)) {
            try {
                return fetchJsonPlain(site, boardCode, threadNo);
            } catch (Exception firstError) {
                Logger.w(TAG, "Archive " + site.name + " plain request failed (" +
                        firstError.getMessage() + "), retrying in WebView...");
            }
        }
        String json = fetchJsonInWebView(site, boardCode, threadNo);
        webViewOnlyDomains.add(site.domain);
        return json;
    }

    private static String fetchJsonPlain(ArchivesManager.ArchiveSite site, String boardCode, int threadNo) throws Exception {
        String url = "https://" + site.domain + "/_/api/chan/thread/?board=" + boardCode + "&num=" + threadNo;

        OkHttpClient client = Chan.injector().instance(OkHttpClient.class);
        OkHttpClient timeoutClient = client.newBuilder()
                .callTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build();

        Request.Builder requestBuilder = new Request.Builder().url(url);
        applyWebViewSession(requestBuilder, site.domain);
        Request request = requestBuilder.build();

        Response response = timeoutClient.newCall(request).execute();
        try {
            if (response.code() == 403 || response.code() == 429 || response.code() == 503) {
                throw new IOException("HTTP " + response.code());
            }
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }
            String body = response.body() != null ? response.body().string() : "";
            if (isChallenge(body)) {
                throw new IOException("Cloudflare challenge");
            }
            return body;
        } finally {
            response.close();
        }
    }

    private static boolean isChallenge(String body) {
        String trimmed = body.trim();
        if (trimmed.startsWith("<")) return true;
        return trimmed.contains("challenges.cloudflare.com")
                || trimmed.contains("cf-browser-verification")
                || trimmed.contains("Cloudflare");
    }

    // Reads the WebView cookie jar for this domain and adds the cookies plus
    // the WebView user agent to the plain request; some archives accept a
    // cookie carried over this way.
    private static void applyWebViewSession(Request.Builder requestBuilder, String domain) {
        try {
            String cookies = CookieManager.getInstance().getCookie("https://" + domain + "/");
            if (cookies != null && !cookies.isEmpty()) {
                requestBuilder.header("Cookie", cookies);
            }
        } catch (Exception e) {
            Logger.e(TAG, "Could not read WebView cookies", e);
        }
        try {
            String ua = WebSettings.getDefaultUserAgent(AndroidUtils.getAppContext());
            if (ua != null && !ua.isEmpty()) {
                requestBuilder.header("User-Agent", ua);
            }
        } catch (Exception e) {
            Logger.e(TAG, "Could not read WebView user agent", e);
        }
    }

    private static List<Post> parsePosts(Loadable loadable, ArchivesManager.ArchiveSite site, String json) throws Exception {
        Logger.d(TAG, "parsePosts: domain=" + site.domain + " jsonLen=" + json.length()
                + " head=" + json.substring(0, Math.min(200, json.length())).replace('\n', ' '));
        FuukaPostParser.Result result = FuukaPostParser.parse(json, loadable.board, site.domain, loadable.no);
        if (result.posts.isEmpty()) {
            throw new IOException("No posts in response");
        }

        // Reuse the site's comment parser so links/spans behave like normal posts.
        PostParser parser = loadable.getSite().chanReader().getParser();
        Set<Integer> internalIds = new HashSet<>();
        for (Post.Builder builder : result.posts) {
            internalIds.add(builder.id);
        }

        List<Post> posts = new ArrayList<>(result.posts.size());
        for (Post.Builder builder : result.posts) {
            Post post = parser.parse(null, builder, new PostParser.Callback() {
                @Override
                public boolean isSaved(int postNo) {
                    return false;
                }

                @Override
                public boolean isInternal(int postNo) {
                    return internalIds.contains(postNo);
                }
            });
            if (post != null) {
                // Posts the archive itself marks as deleted render greyed,
                // like cached posts deleted from the live thread.
                if (result.deletedNos.contains(post.no)) {
                    post.deleted.set(true);
                }
                posts.add(post);
            }
        }
        return posts;
    }

    private static void postSuccess(Callback callback, List<Post> posts) {
        AndroidUtils.runOnUiThread(() -> callback.onSuccess(posts));
    }

    private static void postFailure(Callback callback, String message) {
        AndroidUtils.runOnUiThread(() -> callback.onFailure(message));
    }

    // ---------- WebView-session fetch ----------

    /**
     * Navigates a hidden WebView to the archive's API URL. Unlike a JS
     * fetch(), a navigation runs Cloudflare's challenge JavaScript, which
     * auto-solves the managed challenge and redirects to the real JSON; the
     * resulting page text is read back.
     */
    private static String fetchJsonInWebView(ArchivesManager.ArchiveSite site, String boardCode, int threadNo) throws Exception {
        String domain = site.domain;
        String apiUrl = "https://" + domain + "/_/api/chan/thread/?board=" + boardCode + "&num=" + threadNo;

        final Object lock = new Object();
        final boolean[] done = {false};
        final String[] result = {null};

        AndroidUtils.runOnUiThread(() -> {
            Activity activity = findActivity();
            if (activity == null) {
                synchronized (lock) {
                    done[0] = true;
                    lock.notifyAll();
                }
                return;
            }

            final WebView webView;
            final Handler handler = new Handler(Looper.getMainLooper());
            try {
                webView = new WebView(activity);
                WebSettings settings = webView.getSettings();
                settings.setJavaScriptEnabled(true);
                settings.setDomStorageEnabled(true);
                CookieManager.getInstance().setAcceptCookie(true);
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
            } catch (Exception e) {
                Logger.e(TAG, "WebView creation failed", e);
                synchronized (lock) {
                    done[0] = true;
                    lock.notifyAll();
                }
                return;
            }

            // Attach behind the existing UI with a realistic size so the
            // managed challenge doesn't treat the WebView as headless.
            try {
                ViewGroup content = activity.getWindow().getDecorView().findViewById(android.R.id.content);
                content.addView(webView, 0, new ViewGroup.LayoutParams(
                        AndroidUtils.dp(300), AndroidUtils.dp(400)));
            } catch (Exception e) {
                activity.addContentView(webView, new ViewGroup.LayoutParams(1, 1));
            }

            final long[] start = {System.currentTimeMillis()};

            final Runnable finish = new Runnable() {
                @Override
                public void run() {
                    if (done[0]) return;
                    done[0] = true;
                    try {
                        webView.stopLoading();
                        webView.setWebViewClient(null);
                        ((ViewGroup) webView.getParent()).removeView(webView);
                        webView.destroy();
                    } catch (Exception ignored) {
                    }
                    synchronized (lock) {
                        lock.notifyAll();
                    }
                }
            };

            final Runnable readPage = new Runnable() {
                @Override
                public void run() {
                    if (done[0]) return;
                    if (System.currentTimeMillis() - start[0] > FETCH_TIMEOUT_MS) {
                        finish.run();
                        return;
                    }
                    webView.evaluateJavascript(
                            "(function(){return document.body ? document.body.innerText : '';})()",
                            value -> {
                                if (done[0]) return;
                                String text = null;
                                try {
                                    text = new JSONArray("[" + value + "]").getString(0);
                                } catch (Exception ignored) {
                                }
                                if (text == null || text.isEmpty()) {
                                    handler.postDelayed(this, 1000);
                                    return;
                                }
                                if (isChallenge(text)) {
                                    // Challenge page: its JS may still redirect
                                    // to the real content; keep polling.
                                    handler.postDelayed(this, 1000);
                                } else {
                                    Logger.d(TAG, "fetchJsonInWebView read: domain=" + domain
                                            + " len=" + text.length()
                                            + " head=" + text.substring(0, Math.min(120, text.length()))
                                                    .replace('\n', ' '));
                                    result[0] = text;
                                    finish.run();
                                }
                            });
                }
            };

            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    Logger.d(TAG, "fetchJsonInWebView onPageFinished: " + url);
                    if (!done[0]) {
                        handler.postDelayed(readPage, 300);
                    }
                }
            });

            webView.loadUrl(apiUrl);
            handler.postDelayed(readPage, 2000);
        });

        synchronized (lock) {
            while (!done[0]) {
                lock.wait(500);
            }
        }

        String value = result[0];
        Logger.d(TAG, "fetchJsonInWebView: domain=" + domain + " resultLen="
                + (value != null ? value.length() : -1));
        if (value == null || value.isEmpty()) {
            throw new IOException("Archive returned empty response");
        }
        if (isChallenge(value)) {
            throw new ChallengeException(domain);
        }
        return value;
    }

    private static Activity findActivity() {
        try {
            List<Activity> activities = ((ActivityResultHelper.ApplicationActivitiesProvider)
                    AndroidUtils.getAppContext()).getActivities();
            for (int i = activities.size() - 1; i >= 0; i--) {
                Activity activity = activities.get(i);
                if (!activity.isFinishing() && activity.getWindow() != null) {
                    return activity;
                }
            }
        } catch (Exception e) {
            Logger.e(TAG, "findActivity failed", e);
        }
        return null;
    }
}
