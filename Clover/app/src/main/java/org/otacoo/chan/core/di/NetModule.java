package org.otacoo.chan.core.di;

import android.content.Context;

import org.codejargon.feather.Provides;
import org.otacoo.chan.core.cache.FileCache;
import org.otacoo.chan.core.net.ChanInterceptor;
import org.otacoo.chan.core.settings.ChanSettings;
import org.otacoo.chan.utils.Logger;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.inject.Singleton;

import okhttp3.Authenticator;
import okhttp3.Credentials;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.dnsoverhttps.DnsOverHttps;
import org.otacoo.chan.core.net.AppCookieJar;
import org.otacoo.chan.core.site.sites.chan8.Chan8PowInterceptor;

public class NetModule {
    private static final long FILE_CACHE_DISK_SIZE = 50 * 1024 * 1024;
    private static final String FILE_CACHE_NAME = "filecache";
    private static final int TIMEOUT = 30000;
    private static final String TAG = "NetModule";

    // expose the internal java.net.CookieManager so callers can mirror
    // WebView cookies (which are otherwise inaccessible when HttpOnly).
    private static java.net.CookieManager sharedCookieManager;

    public static java.net.CookieManager getSharedCookieManager() {
        return sharedCookieManager;
    }

    // Syncs WebView cookies for {url} into the java.net CookieStore (including HttpOnly).
    // Attention: THIS IS FOR 8chan / Lynxchan ONLY. 4chan.org cookies are owned by Chan4CookieStore.java
    public static void syncCookiesToJar(String url) {
        if (sharedCookieManager == null) return;
        // Ensure CookieManager calls happen on WebView thread (main thread).
        if (!org.otacoo.chan.ui.view.AuthWebView.isOnWebViewThread()) {
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            org.otacoo.chan.utils.AndroidUtils.runOnUiThread(() -> {
                doSyncCookie(url);
                latch.countDown();
            });
            try {
                latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
            }
        } else {
            doSyncCookie(url);
        }
    }

    private static void doSyncCookie(String url) {
        android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
        // Sync cookies for the exact URL.
        String raw = cm.getCookie(url);
        // Logger.i("NetModule", "doSyncCookie raw=" + raw + " for url=" + url);
        syncRaw(url, raw);
        // Also try the root of the same host in case cookies are set with path=/ and
        // some devices only return them when queried at the root.
        try {
            java.net.URI uri = new java.net.URI(url);
            if (uri.getPath() != null && !uri.getPath().equals("/") && !uri.getPath().isEmpty()) {
                String root = uri.getScheme() + "://" + uri.getHost() + "/";
                String rootRaw = cm.getCookie(root);
                // Logger.i("NetModule", "doSyncCookie raw=" + rootRaw + " for root=" + root);
                syncRaw(root, rootRaw);
            }
        } catch (Exception ignored) {}
    }

    /**
     * Returns true if {@code name} is an essential cookie for 8chan browsing.
     */
    public static boolean is8chanEssentialCookie(String name) {
        if (name == null) return false;
        return name.equals("POW_TOKEN")
                || name.equals("POW_ID")
                || name.startsWith("TOS")
                || name.equals("bypass");
    }

    /** Parse "name=value; …" and store each cookie in the jar for its source domain only. */
    private static void syncRaw(String url, String raw) {
        if (raw == null || raw.isEmpty()) return;
        try {
            java.net.URI uri = new java.net.URI(url);
            String host = uri.getHost();
            String[] parts = raw.split(";\\s*");
            for (String part : parts) {
                int eq = part.indexOf('=');
                if (eq <= 0) continue;
                String name = part.substring(0, eq).trim();
                String value = part.substring(eq + 1).trim();

                // 'inbound' is a transient POWBlock redirect cookie; forwarding it re-triggers the challenge.
                if (name.equals("inbound")) {
                    //Logger.i("NetModule", "skipping transient cookie '" + name + "' for " + host);
                    continue;
                }

                // captcha cookies are managed by OkHttp; skip the stale WebView copy.
                if (name.equals("captchaid") || name.equals("captchaexpiration")) {
                    // Logger.d("NetModule", "skipping captcha session cookie '" + name + "' for " + host);
                    continue;
                }

                java.net.HttpCookie hc = new java.net.HttpCookie(name, value);
                hc.setDomain(host);
                hc.setPath("/");
                sharedCookieManager.getCookieStore().add(uri, hc);
            }
        } catch (Exception e) {
            Logger.w("NetModule", "syncRaw failed", e);
        }
    }

    /** Build raw cookie header for {@code url} from the CookieStore, excluding 'inbound'. */
    static String buildRawCookieHeader(HttpUrl url) {
        if (sharedCookieManager == null) return null;
        try {
            java.net.URI uri = url.uri();
            java.util.List<java.net.HttpCookie> cookies =
                    sharedCookieManager.getCookieStore().get(uri);
            if (cookies == null || cookies.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            for (java.net.HttpCookie hc : cookies) {
                if ("inbound".equals(hc.getName())) continue;
                if (sb.length() > 0) sb.append("; ");
                sb.append(hc.getName()).append("=").append(hc.getValue());
            }
            return sb.length() > 0 ? sb.toString() : null;
        } catch (Exception e) {
            Logger.w("NetModule", "buildRawCookieHeader failed", e);
            return null;
        }
    }

    public static String getRawCookieHeader(String url) {
        if (url == null || url.isEmpty()) return null;
        HttpUrl httpUrl = HttpUrl.parse(url);
        if (httpUrl == null) return null;
        return buildRawCookieHeader(httpUrl);
    }

    public static boolean has8chanSessionCookies(String url) {
        String raw = getRawCookieHeader(url);
        if (raw == null || raw.isEmpty()) return false;
        return raw.contains("POW_TOKEN=") && raw.contains("POW_ID=");
    }

    public static String firstMatch(String text, String regex) {
        if (text == null || regex == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile(regex, java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(text);
        if (!m.find()) return null;
        String v = m.groupCount() >= 1 ? m.group(1) : null;
        return v != null ? v.trim() : null;
    }

    public static String extractPowToken(String html) {
        if (html == null) return null;
        String[] patterns = new String[] {
                // POWBlock v1.6 uses <pre id=c style=display:none>TOKEN</pre>
                // The [^>]* allows any extra attributes between the id and >
                "<pre\\s+id\\s*=\\s*['\"]?c['\"]?[^>]*>([^<]+)</pre>",
                "['\"]c['\"]\\s*[:=]\\s*['\"]([^'\"]+)['\"]",
                "[?&]t=([A-Za-z0-9%._~+\\-=/|]+)",
                "\\bt\\s*[:=]\\s*['\"]([A-Za-z0-9%._~+\\-=/|]+)['\"]"
        };
        for (String pattern : patterns) {
            String value = firstMatch(html, pattern);
            if (value != null && !value.isEmpty()) return value;
        }
        return null;
    }

    public static Integer extractPowDifficulty(String html) {
        if (html == null) return null;
        String[] patterns = new String[] {
                // POWBlock v1.6: <pre id=d style=display:none>17</pre>
                "<pre\\s+id\\s*=\\s*['\"]?d['\"]?[^>]*>(\\d+)</pre>",
                "['\"]d['\"]\\s*[:=]\\s*(\\d+)",
                "bits\\s*[=:]\\s*(\\d+)",
                "difficulty\\s*[=:]\\s*(\\d+)"
        };
        for (String pattern : patterns) {
            String value = firstMatch(html, pattern);
            if (value != null) {
                try {
                    return Integer.parseInt(value);
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    // Returns 256 or 512 depending on the algorithm specified in the POWBlock challenge HTML.
    public static int extractPowAlgorithm(String html) {
        if (html == null) return 256;
        String[] patterns = new String[] {
                // POWBlock: <pre id=a style=display:none>512</pre>
                "<pre\\s+id\\s*=\\s*['\"]?a['\"]?[^>]*>(\\d+)</pre>",
                "['\"]a['\"]\\s*[:=]\\s*(\\d+)",
                "algorithm\\s*[=:]\\s*(\\d+)",
                "alg\\s*[=:]\\s*(\\d+)"
        };
        for (String pattern : patterns) {
            String value = firstMatch(html, pattern);
            if (value != null) {
                if ("512".equals(value.trim())) return 512;
                if ("256".equals(value.trim())) return 256;
            }
        }
        return 256; // default
    }

    @Provides
    @Singleton
    public OkHttpClient provideOkHttpClient(UserAgentProvider userAgentProvider) {
        AppCookieJar cookieJar = new AppCookieJar();
        // expose the cookie manager for WebView sync and other helpers
        try {
            sharedCookieManager = cookieJar.getCookieManager();
        } catch (Exception ignored) {}

        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                .readTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                .writeTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                .cookieJar(cookieJar)
                .addInterceptor(new ChanInterceptor(userAgentProvider))
                // Application interceptor handles automatic 8chan POW bypass
                .addInterceptor(new Chan8PowInterceptor())
                .addNetworkInterceptor(new okhttp3.Interceptor() {
                    @Override
                    public okhttp3.Response intercept(okhttp3.Interceptor.Chain chain) throws java.io.IOException {
                        okhttp3.Request req = chain.request();

                        // For 8chan: replace OkHttp's cookie header with raw values from the shared CookieStore.
                        // This preserves HttpOnly cookies and avoids OkHttp's quoting/encoding differences.
                        if (req.url().host().contains("8chan")) {
                            try {
                                String manualCookie = buildRawCookieHeader(req.url());
                                if (manualCookie != null && !manualCookie.isEmpty()) {
                                    req = req.newBuilder().header("Cookie", manualCookie).build();
                                }
                            } catch (Exception ignored) {}

                            // inject Referer for CDN requests that lack one
                            if (req.header("Referer") == null) {
                                String referer = req.url().scheme() + "://" + req.url().host() + "/";
                                req = req.newBuilder().header("Referer", referer).build();
                            }

                            // use image Accept header for media requests
                            String reqPath = req.url().encodedPath();
                            if (reqPath.startsWith("/.media/") || reqPath.startsWith("/.static/")) {
                                req = req.newBuilder()
                                        .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                                        .build();
                            }
                        }

                        return chain.proceed(req);
                    }
                });

        builder.proxySelector(new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                Proxy proxy = ChanSettings.getProxy();
                if (proxy != null) {
                    return Collections.singletonList(proxy);
                }
                return Collections.singletonList(Proxy.NO_PROXY);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            }
        });

        builder.proxyAuthenticator((route, response) -> {
            String username = ChanSettings.getProxyUsername();
            String password = ChanSettings.getProxyPassword();
            if (username.isEmpty()) return null; // no auth configured
            if (response.request().header("Proxy-Authorization") != null) return null; // already tried, avoid loop
            return response.request().newBuilder()
                    .header("Proxy-Authorization", Credentials.basic(username, password))
                    .build();
        });

        final Dns dohDns;
        try {
            OkHttpClient dnsClient = new OkHttpClient.Builder().build();
            dohDns = new DnsOverHttps.Builder()
                    .client(dnsClient)
                    .url(HttpUrl.parse("https://cloudflare-dns.com/dns-query"))
                    .bootstrapDnsHosts(Arrays.asList(
                            InetAddress.getByName("162.159.36.1"),
                            InetAddress.getByName("162.159.46.1"),
                            InetAddress.getByName("1.1.1.1"),
                            InetAddress.getByName("1.0.0.1"),
                            InetAddress.getByName("162.159.132.53"),
                            InetAddress.getByName("2606:4700:4700::1111"),
                            InetAddress.getByName("2606:4700:4700::1001"),
                            InetAddress.getByName("2606:4700:4700::0064"),
                            InetAddress.getByName("2606:4700:4700::6400")
                    ))
                    .build();
        } catch (UnknownHostException e) {
            throw new RuntimeException("Failed to build DnsOverHttps (bootstrap IPs should never fail)", e);
        }
        builder.dns(hostname -> {
            if (!ChanSettings.dnsOverHttps.get()) {
                return Dns.SYSTEM.lookup(hostname);
            }
            try {
                return dohDns.lookup(hostname);
            } catch (IllegalArgumentException e) {
                // DnsOverHttps.isPrivateHost crashes on short hostnames (localhost, "" etc.)
                return Dns.SYSTEM.lookup(hostname);
            }
        });

        return builder.build();
    }

    @Provides
    @Singleton
    public FileCache provideFileCache(Context applicationContext, UserAgentProvider userAgentProvider, OkHttpClient okHttpClient) {
        return new FileCache(new File(getCacheDir(applicationContext), FILE_CACHE_NAME), FILE_CACHE_DISK_SIZE, userAgentProvider.getUserAgent(), okHttpClient);
    }

    private File getCacheDir(Context applicationContext) {
        // See also res/xml/filepaths.xml for the fileprovider.
        if (applicationContext.getExternalCacheDir() != null) {
            return applicationContext.getExternalCacheDir();
        } else {
            return applicationContext.getCacheDir();
        }
    }
}
