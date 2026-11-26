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
package org.otacoo.chan.core.site.common;

import androidx.annotation.Nullable;

import org.otacoo.chan.core.site.Site;
import org.otacoo.chan.core.site.http.HttpCall;
import org.otacoo.chan.core.site.http.ProgressRequestBody;

import java.io.File;
import java.util.Locale;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.RequestBody;

public abstract class MultipartHttpCall extends HttpCall {
    private final MultipartBody.Builder formBuilder;

    private HttpUrl url;
    private String customReferer;

    public MultipartHttpCall(Site site) {
        super(site);

        formBuilder = new MultipartBody.Builder();
        formBuilder.setType(MultipartBody.FORM);
    }

    public MultipartHttpCall url(HttpUrl url) {
        this.url = url;
        return this;
    }

    public MultipartHttpCall referer(String referer) {
        this.customReferer = referer;
        return this;
    }

    public MultipartHttpCall parameter(String name, String value) {
        formBuilder.addFormDataPart(name, value);
        return this;
    }

    public MultipartHttpCall fileParameter(String name, String filename, File file) {
        String mimeType = guessMimeType(filename);
        formBuilder.addFormDataPart(name, filename, RequestBody.create(
                file, MediaType.parse(mimeType)
        ));
        return this;
    }

    public static String guessMimeType(String filename) {
        if (filename == null) {
            return "application/octet-stream";
        }

        String lower = filename.toLowerCase(Locale.ENGLISH);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }

    @Override
    public void setup(
            Request.Builder requestBuilder,
            @Nullable ProgressRequestBody.ProgressRequestListener progressListener
    ) {
        requestBuilder.url(url);
        
        String referer = customReferer;
        if (referer == null) {
            // Default: use base URL if no custom Referer provided
            referer = url.scheme() + "://" + url.host();
            if (url.port() != 80 && url.port() != 443) {
                referer += ":" + url.port();
            }
        }
        requestBuilder.addHeader("Referer", referer);
        
        // Browsers add Origin to POST requests, help evade bot protections
        String origin = url.scheme() + "://" + url.host();
        if (url.port() != 80 && url.port() != 443) {
            origin += ":" + url.port();
        }
        requestBuilder.addHeader("Origin", origin);

        requestBuilder.post(formBuilder.build());
    }
}
