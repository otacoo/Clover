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

import org.otacoo.chan.core.di.UserAgentProvider;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

public class ChanInterceptor implements Interceptor {
    private final UserAgentProvider userAgentProvider;

    public ChanInterceptor(UserAgentProvider userAgentProvider) {
        this.userAgentProvider = userAgentProvider;
    }

    @NonNull
    @Override
    public Response intercept(@NonNull Interceptor.Chain chain) throws IOException {
        Request originalRequest = chain.request();

        Request.Builder builder = originalRequest.newBuilder()
                .header("User-Agent", userAgentProvider.getUserAgent());

        // Add standard browser Accept header if not present
        if (originalRequest.header("Accept") == null) {
            builder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8");
        }

        return chain.proceed(builder.build());
    }
}
