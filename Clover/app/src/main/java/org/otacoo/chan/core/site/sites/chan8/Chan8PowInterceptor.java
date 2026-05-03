package org.otacoo.chan.core.site.sites.chan8;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

import org.otacoo.chan.core.di.NetModule;
import org.otacoo.chan.utils.Logger;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class Chan8PowInterceptor implements Interceptor {
    private static final String TAG = "Chan8PowInterceptor";
    private static final String POW_BYPASS_HEADER = "X-Clover-8chan-Bypass";
    private static final int TIMEOUT = 30000;
    private static volatile java.util.concurrent.CountDownLatch _latch = null;
    private static volatile okhttp3.OkHttpClient nonRedirectClient = null;

    private static okhttp3.OkHttpClient getNonRedirectClient() {
        if (nonRedirectClient != null) return nonRedirectClient;
        synchronized (Chan8PowInterceptor.class) {
            if (nonRedirectClient != null) return nonRedirectClient;
            okhttp3.CookieJar jar = new okhttp3.CookieJar() {
                @Override
                public void saveFromResponse(okhttp3.HttpUrl url, List<okhttp3.Cookie> cookies) {
                    java.net.CookieManager cm = NetModule.getSharedCookieManager();
                    if (cm == null) return;
                    try {
                        java.net.URI uri = url.uri();
                        for (okhttp3.Cookie c : cookies) {
                            if ("inbound".equals(c.name())) continue;
                            java.net.HttpCookie hc = new java.net.HttpCookie(c.name(), c.value());
                            hc.setDomain(c.domain());
                            hc.setPath(c.path());
                            hc.setSecure(c.secure());
                            hc.setHttpOnly(c.httpOnly());
                            if (c.expiresAt() > System.currentTimeMillis()) {
                                hc.setMaxAge((c.expiresAt() - System.currentTimeMillis()) / 1000);
                            }
                            cm.getCookieStore().add(uri, hc);
                        }
                    } catch (Exception ignored) {}
                }

                @Override
                public List<okhttp3.Cookie> loadForRequest(okhttp3.HttpUrl url) {
                    List<okhttp3.Cookie> result = new java.util.ArrayList<>();
                    java.net.CookieManager cm = NetModule.getSharedCookieManager();
                    if (cm == null) return result;
                    try {
                        java.net.URI uri = url.uri();
                        List<java.net.HttpCookie> httpCookies = cm.getCookieStore().get(uri);
                        for (java.net.HttpCookie hc : httpCookies) {
                            if ("inbound".equals(hc.getName())) continue;
                            okhttp3.Cookie.Builder b = new okhttp3.Cookie.Builder()
                                    .name(hc.getName()).value(hc.getValue())
                                    .domain(hc.getDomain() != null ? hc.getDomain() : url.host())
                                    .path(hc.getPath() != null ? hc.getPath() : "/");
                            if (hc.getMaxAge() > 0) b.expiresAt(System.currentTimeMillis() + hc.getMaxAge() * 1000);
                            result.add(b.build());
                        }
                    } catch (Exception ignored) {}
                    return result;
                }
            };

            nonRedirectClient = new okhttp3.OkHttpClient.Builder()
                    .connectTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                    .readTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                    .writeTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .cookieJar(jar)
                    .build();
            return nonRedirectClient;
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public Response intercept(Chain chain) throws java.io.IOException {
        Request req = chain.request();

        Response resp;
        try {
            resp = chain.proceed(req);
        } catch (java.io.IOException ioEx) {
            // Network-level failure (DNS, refused connection, timeout).
            // If this is an unforced 8chan request, switch to the fallback domain and retry once.
            String host = req.url().host();
            if (host.contains("8chan") && req.header(POW_BYPASS_HEADER) == null
                    && org.otacoo.chan.core.site.sites.chan8.Chan8RateLimit.getForcedDomain() == null) {
                org.otacoo.chan.core.site.sites.chan8.Chan8RateLimit.notifyDomainUnreachable(host);
                String newDomain = org.otacoo.chan.core.site.sites.chan8.Chan8RateLimit.getActiveDomain();
                if (!newDomain.equals(host)) {
                    // The active domain changed — rewrite the URL and retry on the new domain.
                    String newUrl = req.url().toString().replace(host, newDomain);
                    Logger.w(TAG, "IOException on " + host + "; retrying on " + newDomain);
                    Request retryReq = req.newBuilder().url(newUrl).build();
                    return chain.proceed(retryReq);
                }
            }
            throw ioEx;
        }

        // Handle 429 rate-limit: back off and retry once.
        if (resp.code() == 429 && req.url().host().contains("8chan")) {
            String retryAfterHeader = resp.header("Retry-After");
            long sleepMs = 5_000L;
            if (retryAfterHeader != null) {
                try { sleepMs = Long.parseLong(retryAfterHeader.trim()) * 1000L; } catch (NumberFormatException ignored) {}
            }
            sleepMs = Math.min(sleepMs, 30_000L);
            Logger.w(TAG, "429 rate-limited; sleeping " + sleepMs + "ms before retry");
            Chan8PowNotifier.showRateLimit();
            resp.close();
            try { Thread.sleep(sleepMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            resp = chain.proceed(req);
        }

        if (!req.url().host().contains("8chan") || req.header(POW_BYPASS_HEADER) != null) {
            return resp;
        }

        String powStatus = resp.header("X-PoWBlock-Status");
        if (powStatus == null) powStatus = resp.header("X-PoW-Status");
        boolean xBlockRequired = powStatus != null && powStatus.equalsIgnoreCase("required");
        // Also handle Varnish 403 "Proof of Work required" (expired POW_TOKEN).
        boolean is403Pow = !xBlockRequired && resp.code() == 403
                && resp.peekBody(256).string().contains("Proof of Work required");
        // Also handle 200 HTML responses where no PoWBlock header was sent: any 8chan JSON
        // endpoint returning HTML is a POW/TOS interstitial — JSON never starts with '<'.
        boolean is200HtmlPow = false;
        if (!xBlockRequired && !is403Pow && resp.code() == 200) {
            String preview = resp.peekBody(16).string().trim();
            is200HtmlPow = preview.startsWith("<");
        }
        if (!xBlockRequired && !is403Pow && !is200HtmlPow) {
            return resp;
        }

        // For X-PoWBlock-Status and 200-HTML cases, the challenge may be in the response body.
        // For 403 and 200-HTML-without-token we fetch it separately inside the acquired block.
        String html = (xBlockRequired || is200HtmlPow) ? resp.peekBody(64 * 1024).string() : null;

        // Synchronize POW bypass so only one thread computes/submits the solution
        java.util.concurrent.CountDownLatch localLatch;
        boolean acquired;
        synchronized (Chan8PowInterceptor.class) {
            if (_latch == null) {
                _latch = new java.util.concurrent.CountDownLatch(1);
                localLatch = _latch;
                acquired = true;
            } else {
                localLatch = _latch;
                acquired = false;
            }
        }

        try {
            if (!acquired) {
                if (is403Pow || is200HtmlPow) resp.close(); // abandon; wait for solver thread
                // Wait for the thread that is performing POW to finish
                try {
                    localLatch.await();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                return chain.proceed(req);
            }

            // useCaptchaJsSubmit: true when the challenge came from /captcha.js rather than
            // being embedded in the original response body (affects the submit URL).
            boolean useCaptchaJsSubmit = false;

            if (is403Pow) {
                // Expired POW_TOKEN — clear it from the cookie store so the server
                // treats us as a new visitor, then fetch a fresh challenge via captcha.js.
                resp.close();
                clearPowCookieStore(req.url().host());
                html = fetchPowChallenge(req.url());
                if (html == null) {
                    Logger.w(TAG, "403 POW: could not retrieve fresh challenge");
                    Chan8PowNotifier.onPowFailed();
                    return chain.proceed(req.newBuilder().header(POW_BYPASS_HEADER, "1").build());
                }
                useCaptchaJsSubmit = true;
            } else if (is200HtmlPow) {
                // The body is a challenge or TOS/interstitial page.
                // Try to parse a challenge token from it first.  If none is present
                // (e.g. TOS acceptance page), fetch a fresh challenge via captcha.js.
                String tokenCheck = NetModule.firstMatch(html,
                        "<pre\\s+id\\s*=\\s*['\"]?c['\"]?[^>]*>([^<]+)</pre>");
                resp.close();
                if (tokenCheck == null) {
                    // Logger.w(TAG, "200 HTML POW: no challenge token in body, fetching from captcha.js");
                    clearPowCookieStore(req.url().host());
                    html = fetchPowChallenge(req.url());
                    if (html == null) {
                        Logger.w(TAG, "200 HTML POW: could not retrieve fresh challenge");
                        Chan8PowNotifier.onPowFailed();
                        return chain.proceed(req.newBuilder().header(POW_BYPASS_HEADER, "1").build());
                    }
                    useCaptchaJsSubmit = true;
                }
            }

            String token = NetModule.firstMatch(html, "<pre\\s+id\\s*=\\s*['\"]?c['\"]?[^>]*>([^<]+)</pre>");
            Integer difficulty = NetModule.extractPowDifficulty(html);

            if (token == null || difficulty == null) {
                Logger.w(TAG, "POW required but challenge parse failed; user must Login via WebView");
                Chan8PowNotifier.onPowFailed();
                if (is403Pow || is200HtmlPow) return chain.proceed(req.newBuilder().header(POW_BYPASS_HEADER, "1").build());
                return resp;
            }

            Chan8PowNotifier.onPowStarted();

            int algorithm = NetModule.extractPowAlgorithm(html);
            // Logger.w(TAG, "POW algorithm=" + algorithm + " difficulty=" + difficulty);
            long t0 = System.currentTimeMillis();
            Chan8ProofOfWork solver = new Chan8ProofOfWork(token, difficulty, algorithm);
            Integer solution = solver.find();
            long elapsed = System.currentTimeMillis() - t0;
            if (solution == null) {
                Logger.w(TAG, "POW solver failed after " + elapsed + "ms");
                Chan8PowNotifier.onPowFailed();
                if (is403Pow || is200HtmlPow) return chain.proceed(req.newBuilder().header(POW_BYPASS_HEADER, "1").build());
                return resp;
            }

            // Submit back to the endpoint that issued the challenge.
            // – xBlockRequired or is200HtmlPow-with-body: same path as the original request
            // – is403Pow or is200HtmlPow-fallback-via-captcha.js: /captcha.js
            String submitPath = useCaptchaJsSubmit ? "/captcha.js" : req.url().encodedPath();
            String submitUrl = req.url().scheme() + "://" + req.url().host() + submitPath + "?pow=" + solution + "&t=" + token;
            // Logger.w(TAG, "POW submit URL: " + (submitUrl.length() > 120 ? submitUrl.substring(0, 120) + "…" : submitUrl));

            if (!is200HtmlPow) resp.close(); // already closed for 200 HTML inside the block above
            // Clear stale POW cookies (e.g. from a restored backup) before submitting the
            // solution, so the server starts a clean session from the new tokens.
            clearPowCookieStore(req.url().host());

            Request submitReq = req.newBuilder()
                    .url(submitUrl)
                    .header("Referer", req.url().scheme() + "://" + req.url().host() + "/")
                    .removeHeader("Cookie")
                    .header(POW_BYPASS_HEADER, "1")
                    .build();

            Response submitResp = getNonRedirectClient().newCall(submitReq).execute();
            int submitCode = submitResp.code();
            // Logger.w(TAG, "POW submit response: code=" + submitCode);
            // If we get rate-limited on the POW submission, abandon and return the original 403/required response.
            if (submitCode == 429) {
                submitResp.close();
                Chan8PowNotifier.onPowRateLimited();
                return new okhttp3.Response.Builder()
                        .request(req)
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(429)
                        .message("Too Many Requests")
                        .body(okhttp3.ResponseBody.create(
                                okhttp3.MediaType.parse("text/plain; charset=utf-8"),
                                "Rate limited"))
                        .build();
            }

            List<String> setCookies = submitResp.headers("Set-Cookie");
            // if (!setCookies.isEmpty()) {
            //     Logger.w(TAG, "POW submit Set-Cookie count=" + setCookies.size());
            // }
            String completeStatus = submitResp.header("X-PoWBlock-Status");
            if (completeStatus == null) completeStatus = submitResp.header("X-PoW-Status");
            // if (completeStatus != null && !completeStatus.equalsIgnoreCase("complete")) {
            //     Logger.w(TAG, "POW submit 302 missing X-PoWBlock-Status: complete (got: " + completeStatus + ")");
            // }
            submitResp.close();

            // Persist POW_TOKEN/POW_ID into site user settings if present
            try {
                java.util.Map<String, String> cookieMap = new java.util.HashMap<>();
                for (String header : setCookies) {
                    String[] parts = header.split(";", 2);
                    String[] nv = parts[0].split("=", 2);
                    if (nv.length == 2) {
                        cookieMap.put(nv[0].trim(), nv[1].trim());
                    }
                }

                // Only persist important cookies
                java.util.Map<String, String> toPersist = new java.util.HashMap<>();
                if (cookieMap.containsKey("POW_TOKEN")) toPersist.put("POW_TOKEN", cookieMap.get("POW_TOKEN"));
                if (cookieMap.containsKey("POW_ID")) toPersist.put("POW_ID", cookieMap.get("POW_ID"));
                if (cookieMap.containsKey("TOS") || cookieMap.keySet().stream().anyMatch(k -> k.startsWith("TOS"))) {
                    // pick first TOS* key; also record the key name so Chan8 can restore it on startup
                    for (String k : cookieMap.keySet()) {
                        if (k.startsWith("TOS")) {
                            toPersist.put(k, cookieMap.get(k));
                            toPersist.put("_TOS_KEY", k);
                            break;
                        }
                    }
                }

                if (!toPersist.isEmpty()) {
                    try {
                        org.otacoo.chan.Chan injectorOwner = org.otacoo.chan.Chan.getInstance();
                        if (injectorOwner != null && org.otacoo.chan.Chan.injector() != null) {
                            org.otacoo.chan.core.site.SiteResolver resolver = org.otacoo.chan.Chan.injector().instance(org.otacoo.chan.core.site.SiteResolver.class);
                            org.otacoo.chan.core.site.Site site = resolver.findSiteForUrl(req.url().toString());
                            if (site != null) {
                                org.otacoo.chan.core.settings.json.JsonSettings js = org.otacoo.chan.core.settings.json.JsonSettingsUtil.fromMap(toPersist);
                                org.otacoo.chan.core.site.SiteService svc = org.otacoo.chan.Chan.injector().instance(org.otacoo.chan.core.site.SiteService.class);
                                svc.updateUserSettings(site, js);
                                // Logger.w(TAG, "Persisted POW cookies to site settings for " + site.name());
                            }
                        }
                    } catch (Exception ignored) {
                        Logger.w(TAG, "persist site settings failed: " + ignored.getMessage());
                    }
                }
            } catch (Exception ignored) {}

            Chan8PowNotifier.onPowSolved();
            Request retryReq = req.newBuilder().removeHeader("Cookie").header(POW_BYPASS_HEADER, "1").build();
            return chain.proceed(retryReq);
        } finally {
            // Release waiting threads
            synchronized (Chan8PowInterceptor.class) {
                if (acquired && _latch != null) {
                    _latch.countDown();
                    _latch = null;
                }
            }
        }
    }

    /**
     * Clears expired POW_TOKEN and POW_ID from java.net cookie store for the given host.
     * After clearing, the next request appears as a "new visitor" to POWBlock, which
     * returns a solvable challenge instead of an immediate 403.
     */
    private static void clearPowCookieStore(String host) {
        java.net.CookieManager cm = NetModule.getSharedCookieManager();
        if (cm == null) return;
        try {
            for (java.net.URI u : new java.util.ArrayList<>(cm.getCookieStore().getURIs())) {
                if (u.getHost() == null || !u.getHost().contains(host)) continue;
                for (java.net.HttpCookie c : new java.util.ArrayList<>(cm.getCookieStore().get(u))) {
                    String n = c.getName();
                    if ("POW_TOKEN".equals(n) || "POW_ID".equals(n)) {
                        cm.getCookieStore().remove(u, c);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    /**
     * Fetches a fresh POW challenge HTML by hitting captcha.js without POW cookies.
     * captcha.js is Varnish-whitelisted; when no POW session is present the 8chan layer
     * responds with the POWBlock challenge page (X-PoWBlock-Status: required).
     * Returns the HTML body, or null if the challenge could not be obtained.
     */
    private static String fetchPowChallenge(okhttp3.HttpUrl originUrl) {
        try {
            String base = originUrl.scheme() + "://" + originUrl.host();
            Request r = new Request.Builder().url(base + "/captcha.js").build();
            Response cr = getNonRedirectClient().newCall(r).execute();
            String powH = cr.header("X-PoWBlock-Status");
            String body = cr.body() != null ? cr.body().string() : "";
            cr.close();
            if ("required".equalsIgnoreCase(powH)) {
                // Logger.i(TAG, "fetchPowChallenge: challenge obtained from captcha.js");
                return body;
            }
            Logger.w(TAG, "fetchPowChallenge: captcha.js returned no challenge (X-PoWBlock-Status=" + powH + ")");
            return null;
        } catch (Exception e) {
            Logger.e(TAG, "fetchPowChallenge error: " + e.getMessage());
            return null;
        }
    }
}
