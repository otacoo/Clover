package org.otacoo.chan.core.net;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;

import org.otacoo.chan.core.site.sites.chan8.Chan8RateLimit;

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
                if (cookie.persistent() && cookie.expiresAt() <= System.currentTimeMillis()) {
                    for (java.net.HttpCookie stale
                            : new java.util.ArrayList<>(cookieManager.getCookieStore().get(uri))) {
                        if (cookie.name().equals(stale.getName())) {
                            try {
                                cookieManager.getCookieStore().remove(uri, stale);
                            } catch (Exception ignored) {}
                        }
                    }
                    continue;
                }
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
                String existing = cm.getCookie(baseUrl);
                boolean changed = false;
                
                for (Cookie cookie : cookies) {
                    if ("inbound".equals(cookie.name())) continue;
                    
                    String nv = cookie.name() + "=" + cookie.value();
                    if (existing != null) {
                        boolean found = false;
                        for (String part : existing.split(";")) {
                            if (part.trim().equals(nv)) {
                                found = true;
                                break;
                            }
                        }
                        if (found) continue;
                    }

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
                    changed = true;
                }
                if (changed) {
                    cm.flush();
                }
            } catch (Exception e) {
                org.otacoo.chan.utils.Logger.e("AppCookieJar", "syncToWebView failed", e);
            }
        });
    }

    @Override
    public List<Cookie> loadForRequest(HttpUrl url) {
        try {
            java.net.URI uri = url.uri();
            boolean is4chan = url.host().endsWith("4chan.org") || url.host().endsWith("4channel.org");
            if (is4chan) return new ArrayList<>();
            List<Cookie> result = cookiesFromStore(uri, url);
            // 4chan.org cookies are owned entirely by Chan4CookieStore; never sync them here.
            if (result.isEmpty() && Chan8RateLimit.is8chanHost(url.host())) {
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
            String domain = hc.getDomain();
            if (domain != null && !domainMatches(url.host(), domain)) continue;
            Cookie.Builder b = new Cookie.Builder()
                    .name(hc.getName())
                    .value(hc.getValue())
                    .domain(domain != null ? domain : url.host())
                    .path(hc.getPath() != null ? hc.getPath() : "/");
            if (hc.getSecure()) b.secure();
            if (hc.isHttpOnly()) b.httpOnly();
            if (hc.getMaxAge() > 0) b.expiresAt(System.currentTimeMillis() + hc.getMaxAge() * 1000);
            result.add(b.build());
        }
        return result;
    }

    // java.net matches cookie domains without a dot boundary, so a cookie of
    // "8chan.moe" would also be sent to "not8chan.moe".
    public static boolean domainMatches(String host, String domain) {
        if (domain == null || domain.isEmpty()) return true;
        String d = domain.startsWith(".") ? domain.substring(1) : domain;
        String h = host.toLowerCase();
        String lower = d.toLowerCase();
        return h.equals(lower) || h.endsWith("." + lower);
    }
}
