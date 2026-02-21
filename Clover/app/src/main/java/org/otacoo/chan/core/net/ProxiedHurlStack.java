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

import com.android.volley.toolbox.HurlStack;

import org.otacoo.chan.core.settings.ChanSettings;

import android.webkit.CookieManager;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;

public class ProxiedHurlStack extends HurlStack {
    private final String userAgent;

    public ProxiedHurlStack(String userAgent) {
        super();
        this.userAgent = userAgent;
    }

    @Override
    protected HttpURLConnection createConnection(URL url) throws IOException {
        // Start the connection by specifying a proxy server
        Proxy proxy = ChanSettings.getProxy();
        HttpURLConnection connection;
        if (proxy != null) {
            connection = (HttpURLConnection) url.openConnection(proxy);
        } else {
            connection = (HttpURLConnection) url.openConnection();
        }

        // Use the same workaround as described in Volley's HurlStack:
        // Workaround for the M release HttpURLConnection not observing the
        // HttpURLConnection.setFollowRedirects() property.
        // https://code.google.com/p/android/issues/detail?id=194495
        connection.setInstanceFollowRedirects(HttpURLConnection.getFollowRedirects());

        connection.setRequestProperty("User-Agent", userAgent);

        // Copy cookies from CookieManager for bot protection (PoWBlock, TOS cookies, etc)
        String urlString = url.toString();
        
        // Add Referer for 8chan.moe to help bypass some bot protection variants
        if (urlString.contains("8chan.moe") || urlString.contains("8chan.st")) {
            connection.setRequestProperty("Referer", url.getProtocol() + "://" + url.getHost() + "/");
        }

        String cookies = android.webkit.CookieManager.getInstance().getCookie(urlString);

        // Add mandatory 8chan-specific cookies as fallback
        if (urlString.contains("8chan.moe") || urlString.contains("8chan.st")) {
            if (cookies == null || cookies.isEmpty()) {
                cookies = "TOS=1; POW_TOKEN=1";
            } else if (!cookies.contains("TOS")) {
                cookies += "; TOS=1";
            }
        }

        if (cookies != null && !cookies.isEmpty()) {
            connection.setRequestProperty("Cookie", cookies);
        }

        return connection;
    }
}
