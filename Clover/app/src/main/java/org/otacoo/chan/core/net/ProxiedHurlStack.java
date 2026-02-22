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

import com.android.volley.AuthFailureError;
import com.android.volley.Header;
import com.android.volley.Request;
import com.android.volley.toolbox.HttpResponse;
import com.android.volley.toolbox.HurlStack;

import org.otacoo.chan.core.settings.ChanSettings;
import org.otacoo.chan.utils.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ProxiedHurlStack extends HurlStack {
    private static final String TAG = "ProxiedHurlStack";
    private static final Pattern CHROME_VERSION = Pattern.compile("Chrome/(\\d+)");
    private final String userAgent;

    /**
     * Builds a Sec-Ch-Ua value whose brand version matches the Chrome version in
     * the User-Agent. Mismatched versions are a bot-detection fingerprint.
     */
    private static String buildSecChUa(String ua) {
        Matcher m = CHROME_VERSION.matcher(ua);
        if (m.find()) {
            String v = m.group(1);
            return "\"Not(A:Brand\";v=\"8\", \"Chromium\";v=\"" + v
                    + "\", \"Google Chrome\";v=\"" + v + "\"";
        }
        return "\"Not(A:Brand\";v=\"8\"";
    }

    public ProxiedHurlStack(String userAgent) {
        super();
        this.userAgent = userAgent;
    }

    @Override
    protected HttpURLConnection createConnection(URL url) throws IOException {
        Proxy proxy = ChanSettings.getProxy();
        HttpURLConnection connection = proxy != null
                ? (HttpURLConnection) url.openConnection(proxy)
                : (HttpURLConnection) url.openConnection();

        // Workaround: M-release HttpURLConnection ignores setFollowRedirects().
        // https://code.google.com/p/android/issues/detail?id=194495
        connection.setInstanceFollowRedirects(HttpURLConnection.getFollowRedirects());

        connection.setRequestProperty("User-Agent", userAgent);

        String urlString = Chan8RateLimit.rewriteToActiveDomain(url.toString());
        try {
            if (!urlString.equals(url.toString())) url = new java.net.URL(urlString);
        } catch (Exception ignored) {}

        if (Chan8RateLimit.is8chan(urlString)) {
            connection.setRequestProperty("Referer", url.getProtocol() + "://" + url.getHost() + "/");
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            connection.setRequestProperty("Sec-Fetch-Site", "same-origin");
            connection.setRequestProperty("Sec-Ch-Ua", buildSecChUa(userAgent));
            connection.setRequestProperty("Sec-Ch-Ua-Mobile", "?1");
            connection.setRequestProperty("Sec-Ch-Ua-Platform", "\"Android\"");
            if (urlString.contains("/.media/")) {
                connection.setRequestProperty("Accept", "image/webp,image/apng,image/*,*/*;q=0.8");
                connection.setRequestProperty("Sec-Fetch-Dest", "image");
                connection.setRequestProperty("Sec-Fetch-Mode", "no-cors");
            } else {
                connection.setRequestProperty("Sec-Fetch-Dest", "empty");
                connection.setRequestProperty("Sec-Fetch-Mode", "cors");
            }
        }

        String cookies = android.webkit.CookieManager.getInstance().getCookie(urlString);
        if (cookies != null && !cookies.isEmpty()) {
            connection.setRequestProperty("Cookie", cookies);
        }

        return connection;
    }

    @Override
    public HttpResponse executeRequest(Request<?> request, Map<String, String> additionalHeaders)
            throws IOException, AuthFailureError {
        HttpResponse response = super.executeRequest(request, additionalHeaders);
        String reqUrl = Chan8RateLimit.rewriteToActiveDomain(request.getUrl());

        if (!Chan8RateLimit.is8chan(reqUrl)) {
            return response;
        }

        // Detect PoWBlock fake-PNG: 8chan returns HTTP 200 for /.media/ with an HTML
        // challenge page instead of image data. Clear POW cookies so the user is re-prompted.
        if (Chan8RateLimit.isMedia(reqUrl) && response.getStatusCode() == 200) {
            String age = null, expires = null, cacheControl = null, contentType = null;
            for (Header h : response.getHeaders()) {
                switch (h.getName().toLowerCase()) {
                    case "age":           age = h.getValue(); break;
                    case "expires":       expires = h.getValue(); break;
                    case "cache-control": cacheControl = h.getValue(); break;
                    case "content-type":  contentType = h.getValue(); break;
                }
            }
            boolean isFakePng = "0".equals(age) && "0".equals(expires)
                    && cacheControl != null && cacheControl.contains("no-cache");
            if (isFakePng || (contentType != null && contentType.startsWith("text/html"))) {
                Logger.w(TAG, "PoWBlock fake-PNG for " + reqUrl + " — clearing PoW cookies");
                android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
                String base = "https://" + new java.net.URL(reqUrl).getHost() + "/";
                cm.setCookie(base, "POW_TOKEN=; Max-Age=0");
                cm.setCookie(base, "POW_ID=; Max-Age=0");
                cm.flush();
            }
        }

        if (response.getStatusCode() == 403) {
            StringBuilder hdrs = new StringBuilder();
            for (Header h : response.getHeaders()) hdrs.append(h.getName()).append(": ").append(h.getValue()).append("  ");
            Logger.d(TAG, "403 headers: " + hdrs.toString().trim());
            InputStream content = response.getContent();
            if (content != null) {
                try {
                    byte[] buf = new byte[512];
                    int n = content.read(buf);
                    if (n > 0) Logger.d(TAG, "403 body: " + new String(buf, 0, n, StandardCharsets.UTF_8));
                } catch (Exception ignored) {}
            }
        }

        return response;
    }
}
