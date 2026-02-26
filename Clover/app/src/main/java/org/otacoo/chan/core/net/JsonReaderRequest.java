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
package org.otacoo.chan.core.net;

import android.util.JsonReader;

import androidx.annotation.NonNull;

import org.otacoo.chan.utils.AndroidUtils;
import org.otacoo.chan.utils.IOUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

public abstract class JsonReaderRequest<T> implements Callback {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    protected final RequestListener<T> listener;

    public JsonReaderRequest(RequestListener<T> listener) {
        this.listener = listener;
    }

    @Override
    public void onFailure(@NonNull Call call, @NonNull IOException e) {
        if (call.isCanceled()) return;
        AndroidUtils.runOnUiThread(() -> listener.onError(e.getMessage()));
    }

    @Override
    public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
        if (call.isCanceled()) return;
        
        if (!response.isSuccessful()) {
            AndroidUtils.runOnUiThread(() -> listener.onError("HTTP " + response.code()));
            return;
        }

        byte[] data = response.body().bytes();

        // Handle common anti-bot/verification challenges
        if (data.length > 5) {
            String snippet = new String(data, 0, Math.min(data.length, 1024));
            String trimSnippet = snippet.trim();
            if (trimSnippet.startsWith("<") || trimSnippet.contains("<html") || trimSnippet.contains("<!DOCTYPE")) {
                String errorMsg;
                if (snippet.contains("PoWBlock") || snippet.contains("basedflare") || snippet.contains("Bot Protection")) {
                    errorMsg = "Verification required (PoWBlock). Please 'Login' to this site to solve the challenge.";
                } else if (snippet.contains("Terms of Service") || snippet.contains("TOS") || snippet.contains("Terms of Use")) {
                    errorMsg = "Terms of Service must be accepted. Please 'Login' to this site to accept them.";
                } else if (snippet.contains("cf-browser-verification") || snippet.contains("challenges.cloudflare.com") || snippet.contains("Cloudflare")) {
                    errorMsg = "Cloudflare verification required. Please 'Login' to this site to solve it.";
                } else {
                    String pageTitle = "";
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("<title>(.*?)</title>", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(snippet);
                    if (m.find()) {
                        pageTitle = " (Title: " + m.group(1).trim() + ")";
                    }
                    errorMsg = "Received HTML instead of JSON" + pageTitle + ". The site may be showing a challenge or error page. Use 'Login' to verify.";
                }
                AndroidUtils.runOnUiThread(() -> listener.onError(errorMsg));
                return;
            }
        }

        ByteArrayInputStream baos = new ByteArrayInputStream(data);
        JsonReader reader = new JsonReader(new InputStreamReader(baos, UTF8));

        try {
            T read = readJson(reader);
            AndroidUtils.runOnUiThread(() -> listener.onResponse(read));
        } catch (Exception e) {
            AndroidUtils.runOnUiThread(() -> listener.onError(e.getMessage()));
        } finally {
            IOUtils.closeQuietly(reader);
        }
    }

    public abstract T readJson(JsonReader reader) throws Exception;

    public interface RequestListener<T> {
        void onResponse(T response);
        void onError(String error);
    }
}
