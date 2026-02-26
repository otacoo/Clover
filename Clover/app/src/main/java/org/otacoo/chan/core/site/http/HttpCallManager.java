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
package org.otacoo.chan.core.site.http;


import androidx.annotation.Nullable;

import org.otacoo.chan.core.di.UserAgentProvider;
import org.otacoo.chan.core.site.Site;
import org.otacoo.chan.core.site.SiteRequestModifier;

import javax.inject.Inject;
import javax.inject.Singleton;

import okhttp3.OkHttpClient;
import okhttp3.Request;

/**
 * Manages the {@link HttpCall} executions.
 */
@Singleton
public class HttpCallManager {
    private UserAgentProvider userAgentProvider;
    private OkHttpClient client;

    @Inject
    public HttpCallManager(UserAgentProvider userAgentProvider, OkHttpClient okHttpClient) {
        this.userAgentProvider = userAgentProvider;
        this.client = okHttpClient;
    }

    public void makeHttpCall(
            HttpCall httpCall,
            HttpCall.HttpCallback<? extends HttpCall> callback
    ) {
        makeHttpCall(httpCall, callback, null);
    }

    public void makeHttpCall(
            HttpCall httpCall,
            HttpCall.HttpCallback<? extends HttpCall> callback,
            @Nullable ProgressRequestBody.ProgressRequestListener progressListener
    ) {
        httpCall.setCallback(callback);

        Request.Builder requestBuilder = new Request.Builder();

        if (httpCall.getUrl() != null) {
            requestBuilder.url(httpCall.getUrl());
        }

        Site site = httpCall.site;
        httpCall.setup(requestBuilder, progressListener);

        if (site != null) {
            final SiteRequestModifier siteRequestModifier = site.requestModifier();
            if (siteRequestModifier != null) {
                siteRequestModifier.modifyHttpCall(httpCall, requestBuilder);
            }
        }

        // User-Agent is now handled by ChanInterceptor in OkHttpClient
        Request request = requestBuilder.build();

        client.newCall(request).enqueue(httpCall);
    }
}
