package org.otacoo.chan.core.di;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

import java.util.List;

public class Chan8PowInterceptor implements Interceptor {
    private static final String POW_BYPASS_HEADER = "X-Clover-8chan-Bypass";
    private static volatile java.util.concurrent.CountDownLatch _latch = null;
    private static volatile okhttp3.OkHttpClient nonRedirectClient = null;

    // Minimum gap between consecutive 8chan requests to avoid triggering the rate limiter.
    private static final long MIN_REQUEST_INTERVAL_MS = 800L;
    private static final java.util.concurrent.atomic.AtomicLong lastRequestMs =
            new java.util.concurrent.atomic.AtomicLong(0L);

    /** Enforces MIN_REQUEST_INTERVAL_MS between outgoing 8chan requests via CAS spin. */
    private static void throttle() {
        while (true) {
            long now = System.currentTimeMillis();
            long last = lastRequestMs.get();
            long wait = MIN_REQUEST_INTERVAL_MS - (now - last);
            if (wait <= 0) {
                if (lastRequestMs.compareAndSet(last, now)) return;
            } else {
                try { Thread.sleep(wait); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

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

        // Throttle all outgoing 8chan requests (except internal bypass/retry ones) so
        // pin-watcher batch updates don't trigger the server's rate limiter.
        if (req.url().host().contains("8chan") && req.header(POW_BYPASS_HEADER) == null) {
            throttle();
        }

        Response resp = chain.proceed(req);

        // Handle 429 rate-limit: back off and retry once.
        if (resp.code() == 429 && req.url().host().contains("8chan")) {
            String retryAfterHeader = resp.header("Retry-After");
            long sleepMs = 5_000L;
            if (retryAfterHeader != null) {
                try { sleepMs = Long.parseLong(retryAfterHeader.trim()) * 1000L; } catch (NumberFormatException ignored) {}
            }
            sleepMs = Math.min(sleepMs, 30_000L);
            org.otacoo.chan.utils.Logger.w("Chan8PowInterceptor", "429 rate-limited; sleeping " + sleepMs + "ms before retry");
            resp.close();
            try { Thread.sleep(sleepMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            lastRequestMs.set(System.currentTimeMillis());
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
        if (!xBlockRequired && !is403Pow) {
            return resp;
        }

        // For X-PoWBlock-Status the challenge HTML is in this response body.
        // For 403 we fetch it separately inside the acquired block.
        String html = xBlockRequired ? resp.peekBody(64 * 1024).string() : null;

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
                if (is403Pow) resp.close(); // abandon 403; wait for solver thread to refresh POW
                // Wait for the thread that is performing POW to finish
                try {
                    localLatch.await();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                return chain.proceed(req);
            }

            if (is403Pow) {
                // Expired POW_TOKEN — clear it from the cookie store so the server
                // treats us as a new visitor, then fetch a fresh challenge via captcha.js.
                resp.close();
                clearPowCookieStore(req.url().host());
                html = fetchPowChallenge(req.url());
                if (html == null) {
                    org.otacoo.chan.utils.Logger.w("Chan8PowInterceptor", "403 POW: could not retrieve fresh challenge");
                    org.otacoo.chan.core.net.Chan8PowNotifier.onPowFailed();
                    return chain.proceed(req.newBuilder().header(POW_BYPASS_HEADER, "1").build());
                }
            }

            String token = NetModule.firstMatch(html, "<pre\\s+id\\s*=\\s*['\"]?c['\"]?[^>]*>([^<]+)</pre>");
            Integer difficulty = NetModule.extractPowDifficulty(html);

            if (token == null || difficulty == null) {
                org.otacoo.chan.utils.Logger.w("Chan8PowInterceptor", "POW required but challenge parse failed; user must Login via WebView");
                org.otacoo.chan.core.net.Chan8PowNotifier.onPowFailed();
                if (is403Pow) return chain.proceed(req.newBuilder().header(POW_BYPASS_HEADER, "1").build());
                return resp;
            }

            org.otacoo.chan.core.net.Chan8PowNotifier.onPowStarted();

            long t0 = System.currentTimeMillis();
            org.otacoo.chan.core.net.pow.Chan8ProofOfWork solver = new org.otacoo.chan.core.net.pow.Chan8ProofOfWork(token, difficulty, 256);
            Integer solution = solver.find();
            long elapsed = System.currentTimeMillis() - t0;
            if (solution == null) {
                org.otacoo.chan.utils.Logger.w("Chan8PowInterceptor", "POW solver failed after " + elapsed + "ms");
                org.otacoo.chan.core.net.Chan8PowNotifier.onPowFailed();
                if (is403Pow) return chain.proceed(req.newBuilder().header(POW_BYPASS_HEADER, "1").build());
                return resp;
            }

            // For the 403/expired-token case the challenge came from captcha.js, so the
            // solution must be submitted back to captcha.js, not to blockBypass.js.
            String submitPath = is403Pow ? "/captcha.js" : req.url().encodedPath();
            String submitUrl = req.url().scheme() + "://" + req.url().host() + submitPath + "?pow=" + solution + "&t=" + token;
            org.otacoo.chan.utils.Logger.w("Chan8PowInterceptor", "POW submit URL: " + (submitUrl.length() > 120 ? submitUrl.substring(0, 120) + "…" : submitUrl));

            resp.close();
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
            org.otacoo.chan.utils.Logger.w("Chan8PowInterceptor", "POW submit response: code=" + submitCode);
            // If we get rate-limited on the POW submission, abandon and return the original 403/required response.
            if (submitCode == 429) {
                submitResp.close();
                org.otacoo.chan.core.net.Chan8PowNotifier.onPowFailed();
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
            if (!setCookies.isEmpty()) {
                org.otacoo.chan.utils.Logger.w("Chan8PowInterceptor", "POW submit Set-Cookie count=" + setCookies.size());
            }
            String completeStatus = submitResp.header("X-PoWBlock-Status");
            if (completeStatus == null) completeStatus = submitResp.header("X-PoW-Status");
            if (completeStatus != null && !completeStatus.equalsIgnoreCase("complete")) {
                org.otacoo.chan.utils.Logger.w("Chan8PowInterceptor", "POW submit 302 missing X-PoWBlock-Status: complete (got: " + completeStatus + ")");
            }
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
                    // pick first TOS* key
                    for (String k : cookieMap.keySet()) {
                        if (k.startsWith("TOS")) {
                            toPersist.put(k, cookieMap.get(k));
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
                                org.otacoo.chan.utils.Logger.w("Chan8PowInterceptor", "Persisted POW cookies to site settings for " + site.name());
                            }
                        }
                    } catch (Exception ignored) {
                        org.otacoo.chan.utils.Logger.w("Chan8PowInterceptor", "persist site settings failed: " + ignored.getMessage());
                    }
                }
            } catch (Exception ignored) {}

            org.otacoo.chan.core.net.Chan8PowNotifier.onPowSolved();
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
                org.otacoo.chan.utils.Logger.i("Chan8PowInterceptor", "fetchPowChallenge: challenge obtained from captcha.js");
                return body;
            }
            org.otacoo.chan.utils.Logger.w("Chan8PowInterceptor", "fetchPowChallenge: captcha.js returned no challenge (X-PoWBlock-Status=" + powH + ")");
            return null;
        } catch (Exception e) {
            org.otacoo.chan.utils.Logger.e("Chan8PowInterceptor", "fetchPowChallenge error: " + e.getMessage());
            return null;
        }
    }
}
