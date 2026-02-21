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
package org.otacoo.chan.core.cache;

import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;

import androidx.annotation.AnyThread;
import androidx.annotation.MainThread;
import androidx.annotation.WorkerThread;

import org.otacoo.chan.core.settings.ChanSettings;
import org.otacoo.chan.utils.Logger;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.internal.Util;
import okio.Buffer;
import okio.BufferedSink;
import okio.Okio;
import okio.Source;

public class FileCacheDownloader implements Runnable {
    private static final String TAG = "FileCacheDownloader";
    private static final long BUFFER_SIZE = 8192;
    private static final long NOTIFY_SIZE = BUFFER_SIZE * 8;

    private final OkHttpClient httpClient;
    private final String url;
    private final File output;
    private final String userAgent;
    private final Handler handler;

    // Main thread only.
    private final Callback callback;
    private final List<FileCacheListener> listeners = new ArrayList<>();

    // Main and worker thread.
    private AtomicBoolean running = new AtomicBoolean(false);
    private AtomicBoolean cancel = new AtomicBoolean(false);
    private Future<?> future;

    // Worker thread.
    private Call call;
    private ResponseBody body;

    static FileCacheDownloader fromCallbackClientUrlOutputUserAgent(
            Callback callback, OkHttpClient httpClient, String url,
            File output, String userAgent) {
        return new FileCacheDownloader(callback, httpClient, url, output, userAgent);
    }

    private FileCacheDownloader(Callback callback, OkHttpClient httpClient,
                                String url, File output, String userAgent) {
        this.callback = callback;
        this.httpClient = httpClient;
        this.url = url;
        this.output = output;
        this.userAgent = userAgent;

        handler = new Handler(Looper.getMainLooper());
    }

    @MainThread
    public void execute(ExecutorService executor) {
        future = executor.submit(this);
    }

    @MainThread
    public String getUrl() {
        return url;
    }

    @AnyThread
    public Future<?> getFuture() {
        return future;
    }

    @MainThread
    public void addListener(FileCacheListener callback) {
        listeners.add(callback);
    }

    /**
     * Cancel this download.
     */
    @MainThread
    public void cancel() {
        if (cancel.compareAndSet(false, true)) {
            // Did not start running yet, mark finished here.
            if (!running.get()) {
                callback.downloaderFinished(this);
            }
        }
    }

    @AnyThread
    private void post(Runnable runnable) {
        handler.post(runnable);
    }

    @AnyThread
    private void log(String message) {
        Logger.d(TAG, logPrefix() + message);
    }

    @AnyThread
    private void log(String message, Exception e) {
        Logger.e(TAG, logPrefix() + message, e);
    }

    private String logPrefix() {
        return "[" + url.substring(0, Math.min(url.length(), 45)) + "] ";
    }

    @Override
    @WorkerThread
    public void run() {
        log("start");
        running.set(true);
        execute();
    }

    @WorkerThread
    private void execute() {
        Closeable sourceCloseable = null;
        Closeable sinkCloseable = null;

        try {
            checkCancel();

            ResponseBody body = getBody();

            Source source = body.source();
            sourceCloseable = source;

            BufferedSink sink = Okio.buffer(Okio.sink(output));
            sinkCloseable = sink;

            checkCancel();

            log("got input stream");

            pipeBody(source, sink);

            log("done");

            post(() -> {
                callback.downloaderAddedFile(output);
                callback.downloaderFinished(this);
                for (FileCacheListener callback : listeners) {
                    callback.onSuccess(output);
                    callback.onEnd();
                }
            });
        } catch (IOException e) {
            boolean isNotFound = false;
            boolean cancelled = false;
            if (e instanceof HttpCodeIOException) {
                int code = ((HttpCodeIOException) e).code;
                log("exception: http error, code: " + code, e);
                isNotFound = code == 404;
            } else if (e instanceof CancelException) {
                // Don't log the stack.
                log("exception: cancelled");
                cancelled = true;
            } else {
                log("exception", e);
            }

            final boolean finalIsNotFound = isNotFound;
            final boolean finalCancelled = cancelled;
            post(() -> {
                purgeOutput();
                for (FileCacheListener callback : listeners) {
                    if (finalCancelled) {
                        callback.onCancel();
                    } else {
                        callback.onFail(finalIsNotFound);
                    }

                    callback.onEnd();
                }
                callback.downloaderFinished(this);
            });
        } finally {
            Util.closeQuietly(sourceCloseable);
            Util.closeQuietly(sinkCloseable);

            if (call != null) {
                call.cancel();
            }

            if (body != null) {
                Util.closeQuietly(body);
            }
        }
    }

    @WorkerThread
    private ResponseBody getBody() throws IOException {
        return getBody(false);
    }

    @WorkerThread
    private ResponseBody getBody(boolean isRetry) throws IOException {
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .header("User-Agent", userAgent);

        if (url.contains("8chan.moe") || url.contains("8chan.st")) {
            requestBuilder.header("Referer", getBaseUrl(url));
        }

        // Copy cookies from CookieManager for bot protection (PoWBlock, TOS cookies, etc)
        String cookies = android.webkit.CookieManager.getInstance().getCookie(url);
        
        // Add mandatory 8chan-specific cookies as fallback
        if (url.contains("8chan.moe") || url.contains("8chan.st")) {
            if (cookies == null || cookies.isEmpty()) {
                cookies = "TOS=1; POW_TOKEN=1"; // Add generic dummy if missing
            } else if (!cookies.contains("TOS")) {
                cookies += "; TOS=1";
            }
        }

        if (cookies != null && !cookies.isEmpty()) {
            requestBuilder.header("Cookie", cookies);
        }

        Request request = requestBuilder.build();

        OkHttpClient client = httpClient.newBuilder()
                .proxy(ChanSettings.getProxy())
                .build();
        
        call = client.newCall(request);

        Response response = call.execute();
        
        // Detect 8chan.moe PoW block on images/media
        if (response.isSuccessful() && (url.contains("8chan.moe") || url.contains("8chan.st"))) {
            String age = response.header("Age");
            String expires = response.header("Expires");
            String cacheControl = response.header("Cache-Control");
            String contentType = response.header("Content-Type");
            
            boolean isPoWBlock = ("0".equals(age) && "0".equals(expires) && cacheControl != null && cacheControl.contains("no-cache")) ||
                                (contentType != null && contentType.contains("text/html"));
            
            if (isPoWBlock && !isRetry) {
                log("Detected 8chan.moe PoW block on media (ContentType: " + contentType + "), attempting session refresh...");
                response.close();
                
                // Perform a GET to the root to trigger/refresh session
                Request rootRequest = new Request.Builder()
                        .url(getBaseUrl(url))
                        .header("User-Agent", userAgent)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                        .header("Referer", getBaseUrl(url))
                        .header("Cookie", cookies)
                        .build();
                try {
                    Response rootResponse = client.newCall(rootRequest).execute();
                    // Some sites might set new cookies during this root request
                    List<String> cookieHeaders = rootResponse.headers("Set-Cookie");
                    rootResponse.close();
                    for (String header : cookieHeaders) {
                        android.webkit.CookieManager.getInstance().setCookie(getBaseUrl(url), header);
                    }
                } catch (IOException e) {
                    log("Session refresh failed", e);
                }
                
                // Retry the original request
                return getBody(true);
            } else if (isPoWBlock && isRetry) {
                log("Failed to bypass PoW block even after retry. ContentType: " + contentType);
                response.close();
                throw new IOException("Failed to bypass 8chan.moe PoW block after session refresh retry");
            }
        }

        if (!response.isSuccessful()) {
            throw new HttpCodeIOException(response.code());
        }

        checkCancel();

        body = response.body();
        if (body == null) {
            throw new IOException("body == null");
        }

        checkCancel();

        return body;
    }

    private String getBaseUrl(String urlString) {
        try {
            java.net.URL url = new java.net.URL(urlString);
            return url.getProtocol() + "://" + url.getHost() + "/";
        } catch (Exception e) {
            return urlString;
        }
    }

    @WorkerThread
    private void pipeBody(Source source, BufferedSink sink) throws IOException {
        long contentLength = body.contentLength();

        long read;
        long total = 0;
        long notifyTotal = 0;

        Buffer buffer = new Buffer();

        while ((read = source.read(buffer, BUFFER_SIZE)) != -1) {
            sink.write(buffer, read);
            total += read;

            if (total >= notifyTotal + NOTIFY_SIZE) {
                notifyTotal = total;
                log("progress " + (total / (float) contentLength));
                postProgress(total, contentLength <= 0 ? total : contentLength);
            }

            checkCancel();
        }

        Util.closeQuietly(source);
        Util.closeQuietly(sink);

        call = null;
        Util.closeQuietly(body);
        body = null;
    }

    @WorkerThread
    private void checkCancel() throws IOException {
        if (cancel.get()) {
            throw new CancelException();
        }
    }

    @WorkerThread
    private void purgeOutput() {
        if (output.exists()) {
            final boolean deleteResult = output.delete();

            if (!deleteResult) {
                log("could not delete the file in purgeOutput");
            }
        }
    }

    @WorkerThread
    private void postProgress(final long downloaded, final long total) {
        post(() -> {
            for (FileCacheListener callback : listeners) {
                callback.onProgress(downloaded, total);
            }
        });
    }

    private static class CancelException extends IOException {
        public CancelException() {
        }
    }

    private static class HttpCodeIOException extends IOException {
        private int code;

        public HttpCodeIOException(int code) {
            this.code = code;
        }
    }

    public interface Callback {
        void downloaderFinished(FileCacheDownloader fileCacheDownloader);

        void downloaderAddedFile(File file);
    }
}
