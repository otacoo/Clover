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

import androidx.annotation.NonNull;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.otacoo.chan.utils.AndroidUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

public abstract class HtmlReaderRequest<T> implements Callback {
    protected final RequestListener<T> listener;

    public HtmlReaderRequest(RequestListener<T> listener) {
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

        try {
            byte[] data = response.body().bytes();
            String charset = response.body().contentType() != null ? 
                    response.body().contentType().charset().name() : "UTF-8";
            
            Document document = Jsoup.parse(new ByteArrayInputStream(data), charset, call.request().url().toString());

            T result = readDocument(document);

            AndroidUtils.runOnUiThread(() -> listener.onResponse(result));
        } catch (IOException e) {
            AndroidUtils.runOnUiThread(() -> listener.onError(e.getMessage()));
        }
    }

    public abstract T readDocument(Document document) throws IOException;

    public interface RequestListener<T> {
        void onResponse(T response);
        void onError(String error);
    }
}
