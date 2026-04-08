package org.otacoo.chan.core.net;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;

import java.util.ArrayList;
import java.util.List;

/**
 * Central OkHttp CookieJar backed by a java.net.CookieManager shared with WebView sync.
 */
public class AppCookieJar implements CookieJar {
    private final java.net.CookieManager cookieManager;

    public AppCookieJar() {
        cookieManager = new java.net.CookieManager();
        cookieManager.setCookiePolicy(java.net.CookiePolicy.ACCEPT_ALL);
    }

    public java.net.CookieManager getCookieManager() {
        return cookieManager;
    }

    @Override
    public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
        String host = url.host();
        // 4chan.org cookies are owned entirely by Chan4CookieStore; never sync them here.
        if (host.endsWith("4chan.org") || host.endsWith("4channel.org")) return;

        try {
            java.net.URI uri = url.uri();
            for (Cookie cookie : cookies) {
                if ("inbound".equals(cookie.name())) continue;
                java.net.HttpCookie httpCookie = new java.net.HttpCookie(cookie.name(), cookie.value());
                httpCookie.setDomain(cookie.domain());
                httpCookie.setPath(cookie.path());
                httpCookie.setSecure(cookie.secure());
                httpCookie.setHttpOnly(cookie.httpOnly());
                if (cookie.expiresAt() > System.currentTimeMillis()) {
                    httpCookie.setMaxAge((cookie.expiresAt() - System.currentTimeMillis()) / 1000);
                }
                cookieManager.getCookieStore().add(uri, httpCookie);
            }
            // Mirror to WebView's CookieManager so the Cookie Manager UI stays in sync.
            syncToWebView(url, cookies);
        } catch (Exception e) {
            org.otacoo.chan.utils.Logger.e("AppCookieJar", "saveFromResponse failed", e);
        }
    }

    private void syncToWebView(HttpUrl url, List<Cookie> cookies) {
        org.otacoo.chan.utils.AndroidUtils.runOnUiThread(() -> {
            try {
                android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
                String baseUrl = url.scheme() + "://" + url.host();
                for (Cookie cookie : cookies) {
                    if ("inbound".equals(cookie.name())) continue;
                    StringBuilder sb = new StringBuilder();
                    sb.append(cookie.name()).append("=").append(cookie.value());
                    sb.append("; Domain=").append(cookie.domain());
                    sb.append("; Path=").append(cookie.path());
                    if (cookie.secure()) sb.append("; Secure");
                    if (cookie.httpOnly()) sb.append("; HttpOnly");
                    if (cookie.expiresAt() > System.currentTimeMillis()) {
                        sb.append("; Max-Age=").append((cookie.expiresAt() - System.currentTimeMillis()) / 1000);
                    }
                    cm.setCookie(baseUrl, sb.toString());
                }
                cm.flush();
            } catch (Exception e) {
                org.otacoo.chan.utils.Logger.e("AppCookieJar", "syncToWebView failed", e);
            }
        });
    }

    @Override
    public List<Cookie> loadForRequest(HttpUrl url) {
        try {
            java.net.URI uri = url.uri();
            List<Cookie> result = cookiesFromStore(uri, url);
            // 4chan.org cookies are owned entirely by Chan4CookieStore; never sync them here.
            if (result.isEmpty() && url.host().contains("8chan")) {
                org.otacoo.chan.core.di.NetModule.syncCookiesToJar(url.toString());
                result = cookiesFromStore(uri, url);
            }
            return result;
        } catch (Exception e) {
            org.otacoo.chan.utils.Logger.e("AppCookieJar", "loadForRequest failed", e);
        }
        return new ArrayList<>();
    }

    private List<Cookie> cookiesFromStore(java.net.URI uri, HttpUrl url) {
        List<Cookie> result = new ArrayList<>();
        List<java.net.HttpCookie> stored = cookieManager.getCookieStore().get(uri);
        for (java.net.HttpCookie hc : stored) {
            if ("inbound".equals(hc.getName())) continue;
            Cookie.Builder b = new Cookie.Builder()
                    .name(hc.getName())
                    .value(hc.getValue())
                    .domain(hc.getDomain() != null ? hc.getDomain() : url.host())
                    .path(hc.getPath() != null ? hc.getPath() : "/");
            if (hc.getMaxAge() > 0) b.expiresAt(System.currentTimeMillis() + hc.getMaxAge() * 1000);
            result.add(b.build());
        }
        return result;
    }
}
