package org.otacoo.chan.core.site.sites.chan8;

import androidx.annotation.NonNull;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.otacoo.chan.Chan;
import org.otacoo.chan.core.di.NetModule;
import org.otacoo.chan.core.net.BackgroundRequest;
import org.otacoo.chan.utils.Logger;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class Chan8PowInterceptor implements Interceptor {
    private static final String TAG = "Chan8PowInterceptor";
    public static final String SILENT_POW_HEADER = "X-Clover-Silent-PoW";
    private static final String POW_BYPASS_HEADER = "X-Clover-8chan-Bypass";
    // Marks the request that asks the site whether the session is still good.
    // It is not a use of the site, so it does not count as one.
    public static final String POW_PROBE_HEADER = "X-Clover-PoW-Probe";
private static final Pattern POW_TOKEN_PATTERN =
        Pattern.compile("<pre\\s+id\\s*=\\s*['\"]?c['\"]?[^>]*>([^<]+)</pre>", Pattern.CASE_INSENSITIVE);
    private static final int TIMEOUT = 30000;
    // 8chan refuses proof of work submissions for a while when too many of
    // them are sent, the app has to stop sending them then.
    private static final long SUBMISSION_BACKOFF_MS = 60 * 1000;
    // 8chan does not accept a solution that is sent too soon after the
    // challenge was given. The app solves the challenge a lot faster than a
    // browser does, so the solution is held back a moment before it is sent.
    private static final long MIN_SOLVE_TIME_MS = 2000;
    // A refused solution does not use up the challenge, so it can be sent
    // again a little later.
    private static final long RETRY_DELAY_MS = 2500;
    private static final int SUBMIT_ATTEMPTS = 2;
    private static volatile long submissionsBlockedUntil = 0;
    private static volatile boolean backoffReported = false;
    // When the site was last asked for something, so that the proof of work
    // is only renewed while the site is in use.
    private static volatile long lastUseTime = 0;
    // The site counts as in use for this long after it was last asked for
    // something.
    private static final long USE_WINDOW_MS = 10 * 60 * 1000;
    private static volatile java.util.concurrent.CountDownLatch _latch = null;
    private static volatile okhttp3.OkHttpClient nonRedirectClient = null;

    private static okhttp3.OkHttpClient getNonRedirectClient() {
        if (nonRedirectClient != null) return nonRedirectClient;
        synchronized (Chan8PowInterceptor.class) {
            if (nonRedirectClient != null) return nonRedirectClient;
            okhttp3.CookieJar jar = new okhttp3.CookieJar() {
                @Override
                public void saveFromResponse(@NonNull okhttp3.HttpUrl url, @NonNull List<okhttp3.Cookie> cookies) {
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

                @NonNull
                @Override
                public List<okhttp3.Cookie> loadForRequest(@NonNull okhttp3.HttpUrl url) {
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

            OkHttpClient main = Chan.injector().instance(OkHttpClient.class);
            nonRedirectClient = main.newBuilder()
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

    @NonNull
    @Override
    @SuppressWarnings("deprecation")
    public Response intercept(Chain chain) throws java.io.IOException {
        Request req = chain.request();
        // A request that is made in the background (the board list, the
        // automatic refreshing of a thread) must not show anything to the user.
        boolean silentPow = req.header(SILENT_POW_HEADER) != null
                || req.tag(BackgroundRequest.class) != null;
        // The site is being used now. The probe that asks whether the session
        // is still good does not count, it would keep the site alive for ever.
        if (Chan8RateLimit.is8chanHost(req.url().host())
                && req.header(POW_BYPASS_HEADER) == null
                && req.header(POW_PROBE_HEADER) == null) {
            lastUseTime = System.currentTimeMillis();
        }

        Response resp;
        boolean clearedPowCookies = false;
        try {
            resp = chain.proceed(req);
        } catch (java.io.IOException ioEx) {
            // A request the app cancelled (the user left the thread, the
            // download was stopped) says nothing about the site being down.
            if (chain.call().isCanceled()) {
                throw ioEx;
            }
            // Network-level failure (DNS, refused connection, timeout).
            // If this is an unforced 8chan request, switch to the fallback domain and retry once.
            String host = req.url().host();
            if (Chan8RateLimit.is8chanHost(host) && req.header(POW_BYPASS_HEADER) == null
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

        // Network-level failure (HTTP 5xx server errors).
        // If this is an unforced 8chan request, switch to the fallback domain and retry once.
        if (resp.code() >= 500 && Chan8RateLimit.is8chanHost(req.url().host()) && req.header(POW_BYPASS_HEADER) == null
                && org.otacoo.chan.core.site.sites.chan8.Chan8RateLimit.getForcedDomain() == null) {
            String host = req.url().host();
            org.otacoo.chan.core.site.sites.chan8.Chan8RateLimit.notifyDomainUnreachable(host);
            String newDomain = org.otacoo.chan.core.site.sites.chan8.Chan8RateLimit.getActiveDomain();
            if (!newDomain.equals(host)) {
                resp.close();
                String newUrl = req.url().toString().replace(host, newDomain);
                Logger.w(TAG, "HTTP " + resp.code() + " on " + host + "; retrying on " + newDomain);
                Request retryReq = req.newBuilder().url(newUrl).build();
                resp = chain.proceed(retryReq);
            }
        }

        // Handle 429 rate-limit: fail fast instead of sleeping on the shared
        // dispatcher thread (a sleep here would stall all app requests).
        // The snackbar informs the user; they can retry manually.
        if (resp.code() == 429 && Chan8RateLimit.is8chanHost(req.url().host())) {
            Logger.w(TAG, "429 rate-limited " + req.method() + " " + req.url().host() + req.url().encodedPath() + "; failing fast");
            if (!silentPow) {
                Chan8PowNotifier.showRateLimit();
            }
            resp.close();
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

        if (!Chan8RateLimit.is8chanHost(req.url().host()) || req.header(POW_BYPASS_HEADER) != null) {
            return resp;
        }

        String powStatus;
        boolean xBlockRequired;
        boolean is403Pow;
        boolean is200HtmlPow;
        while (true) {
            powStatus = resp.header("X-PoWBlock-Status");
            if (powStatus == null) powStatus = resp.header("X-PoW-Status");
            xBlockRequired = powStatus != null && powStatus.equalsIgnoreCase("required");
            // Also handle Varnish 403 "Proof of Work required" (expired POW_TOKEN)
            // and the POWBlock 403 page ("403 PB", "Guru Meditation").
            is403Pow = false;
            if (!xBlockRequired && resp.code() == 403) {
                String preview = resp.peekBody(256).string();
                is403Pow = preview.contains("Proof of Work required") || preview.contains("403 PB");
            }
            // Also handle 200 HTML responses where no PoWBlock header was sent: any 8chan JSON
            // endpoint returning HTML is a POW/TOS interstitial — JSON never starts with '<'.
            is200HtmlPow = false;
            if (!xBlockRequired && !is403Pow && resp.code() == 200) {
                String contentType = resp.header("Content-Type");
                String preview = resp.peekBody(16).string().trim();
                is200HtmlPow = preview.startsWith("<")
                        || (contentType != null && contentType.contains("text/html"));
                if (is200HtmlPow) {
                    Logger.i(TAG, "POW check: code=" + resp.code() + " type=" + contentType
                            + " head=" + preview.replace("\n", " "));
                }
            }
            if (!xBlockRequired && !is403Pow && !is200HtmlPow) {
                return resp;
            }
            // A stale POW token makes the site answer with 403 and without a
            // challenge. Dropping the token and asking again gets a fresh
            // challenge, which the site sends along with the page.
            if (is403Pow && !clearedPowCookies) {
                Logger.w(TAG, "403 POW: dropping the stale token and asking again");
                resp.close();
                clearPowCookieStore(req.url().host());
                clearedPowCookies = true;
                resp = chain.proceed(req);
                continue;
            }
            break;
        }

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

        // True when this request told the user that the proof of work is being
        // solved. It is undone when the request is done.
        boolean announced = false;

        try {
            if (!acquired) {
                // The response is not used, the request is made again below.
                // It has to be closed, OkHttp does not allow a second
                // request while the previous response is still open.
                resp.close();
                // If this is a non-silent (user-visible) request, show the snackbar now — the
                // solver thread may be processing a silent request and won't show it.
                if (!silentPow) {
                    Chan8PowNotifier.onPowStarted();
                    announced = true;
                }
                // Wait for the thread that is performing POW to finish, with a bound
                // so a stuck solver can never park this dispatcher thread forever.
                try {
                    if (!localLatch.await(90, TimeUnit.SECONDS)) {
                        Logger.w(TAG, "Timed out waiting for POW solver; retrying anyway");
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                // The solver is done, ask again. The answer is the page with the
                // challenge when the solver could not do its job, then this thread
                // solves it itself.
                Response retryResp = chain.proceed(req);
                if (!isChallenge(retryResp)) {
                    return retryResp;
                }
                resp = retryResp;
                acquired = true;
                localLatch = null;
                xBlockRequired = true;
                is200HtmlPow = true;
            }

        // 8chan refuses further proof of work submissions for a while when too
        // many of them are sent. Remember that and stop trying, otherwise every
        // request would submit another one and keep the limit alive.
        if (System.currentTimeMillis() < submissionsBlockedUntil) {
            if (!backoffReported) {
                backoffReported = true;
                Logger.w(TAG, "POW submissions are refused by 8chan, not trying again");
            }
            return resp;
        }
        backoffReported = false;

        // For X-PoWBlock-Status and 200-HTML cases, the challenge may be in the response body.
        // For 403 and 200-HTML-without-token we fetch it separately inside the acquired block.
        String html = (xBlockRequired || is200HtmlPow) ? resp.peekBody(64 * 1024).string() : null;
        // When the challenge was received. 8chan measures how long the client
        // took to solve it, so this is the start of that measurement.
        long challengeReceivedAt = System.currentTimeMillis();

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
                    if (!silentPow) {
                        Chan8PowNotifier.onPowFailed();
                    }
                    return chain.proceed(req.newBuilder().header(POW_BYPASS_HEADER, "1").build());
                }
                challengeReceivedAt = System.currentTimeMillis();
                useCaptchaJsSubmit = true;
            } else if (is200HtmlPow) {
                // The body is a challenge or TOS/interstitial page.
                // Try to parse a challenge token from it first.  If none is present
                // (e.g. TOS acceptance page), fetch a fresh challenge via captcha.js.
                String tokenCheck = NetModule.firstMatch(html, POW_TOKEN_PATTERN);
                resp.close();
                if (tokenCheck == null) {
                    // Logger.w(TAG, "200 HTML POW: no challenge token in body, fetching from captcha.js");
                    clearPowCookieStore(req.url().host());
                    html = fetchPowChallenge(req.url());
                    if (html == null) {
                        Logger.w(TAG, "200 HTML POW: could not retrieve fresh challenge");
                        if (!silentPow) {
                            Chan8PowNotifier.onPowFailed();
                        }
                        return chain.proceed(req.newBuilder().header(POW_BYPASS_HEADER, "1").build());
                    }
                    challengeReceivedAt = System.currentTimeMillis();
                    useCaptchaJsSubmit = true;
                }
            }

            String token = NetModule.firstMatch(html, POW_TOKEN_PATTERN);
            Integer difficulty = NetModule.extractPowDifficulty(html);
            Logger.i(TAG, "POW challenge: token=" + (token != null) + " difficulty=" + difficulty
                    + " algorithm=" + NetModule.extractPowAlgorithm(html));

            if (token == null || difficulty == null) {
                Logger.w(TAG, "POW required but challenge parse failed; user must Login via WebView");
                if (!silentPow) {
                    Chan8PowNotifier.onPowFailed();
                }
                if (is403Pow || is200HtmlPow) return chain.proceed(req.newBuilder().header(POW_BYPASS_HEADER, "1").build());
                return resp;
            }

            if (!silentPow && !announced) {
                Chan8PowNotifier.onPowStarted();
                announced = true;
            }

            int algorithm = NetModule.extractPowAlgorithm(html);
            long t0 = System.currentTimeMillis();
            Chan8ProofOfWork solver = new Chan8ProofOfWork(token, difficulty, algorithm);
            Integer solution = solver.find();
            long elapsed = System.currentTimeMillis() - t0;
            if (solution == null) {
                Logger.w(TAG, "POW solver failed after " + elapsed + "ms");
                if (!silentPow) {
                    Chan8PowNotifier.onPowFailed();
                }
                if (is403Pow || is200HtmlPow) return chain.proceed(req.newBuilder().header(POW_BYPASS_HEADER, "1").build());
                return resp;
            }

            // Submit back to the endpoint that issued the challenge.
            // – xBlockRequired or is200HtmlPow-with-body: same path as the original request
            // – is403Pow or is200HtmlPow-fallback-via-captcha.js: /captcha.js
            String submitPath = useCaptchaJsSubmit ? "/captcha.js" : req.url().encodedPath();
            String queryParams;
            if (html != null && html.contains("powblock=") && html.contains("pbchal=")) {
                queryParams = "?powblock=" + solution + "&pbchal=" + token;
            } else {
                queryParams = "?pow=" + solution + "&t=" + token;
            }
            String submitUrl = req.url().scheme() + "://" + req.url().host() + submitPath + queryParams;

            if (!is200HtmlPow) resp.close(); // already closed for 200 HTML inside the block above

            Request submitReq = req.newBuilder()
                    .url(submitUrl)
                    .header("Referer", req.url().scheme() + "://" + req.url().host() + "/")
                    .removeHeader("Cookie")
                    .header(POW_BYPASS_HEADER, "1")
                    .build();

            Response submitResp = null;
            int submitCode = 0;
            // 8chan refuses a solution that is sent too soon after the
            // challenge was given. The app solves the challenge much faster
            // than a browser does, so the solution is held back a moment
            // before it is sent. A refused solution does not use up the
            // challenge, so it is sent again a little later.
            for (int attempt = 1; attempt <= SUBMIT_ATTEMPTS; attempt++) {
                long wait = challengeReceivedAt + MIN_SOLVE_TIME_MS - System.currentTimeMillis();
                if (wait > 0) {
                    try {
                        Thread.sleep(wait);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                if (attempt > 1) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                submitResp = getNonRedirectClient().newCall(submitReq).execute();
                submitCode = submitResp.code();
                Logger.i(TAG, "POW submit: code=" + submitCode + " attempt=" + attempt
                        + " url=" + submitUrl + " set-cookie=" + submitResp.headers("Set-Cookie"));
                if (submitCode == 200 || submitCode == 302 || submitCode == 303
                        || submitCode == 429) {
                    break;
                }
                // The solution was refused, the challenge is still good, so
                // try again after a moment.
                submitResp.close();
                submitResp = null;
                if (attempt < SUBMIT_ATTEMPTS) {
                    Logger.w(TAG, "POW submit refused with " + submitCode + ", sending it again");
                }
            }
            if (submitResp == null) {
                submitCode = submitCode == 0 ? 400 : submitCode;
            }
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

            if (submitCode != 200 && submitCode != 302 && submitCode != 303) {
                // The solution was refused twice. 8chan answers with 400 when
                // it does not like the submission, so stop sending them for a
                // while and let the app fall back to the browser.
                Logger.w(TAG, "POW submit refused with " + submitCode + ", backing off for "
                        + (SUBMISSION_BACKOFF_MS / 1000) + "s");
                submissionsBlockedUntil = System.currentTimeMillis() + SUBMISSION_BACKOFF_MS;
                if (submitResp != null) submitResp.close();
                return new okhttp3.Response.Builder()
                        .request(req)
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(submitCode)
                        .message("Proof of work refused")
                        .body(okhttp3.ResponseBody.create(
                                okhttp3.MediaType.parse("text/plain; charset=utf-8"),
                                "Proof of work refused"))
                        .build();
            }

            List<String> setCookies = submitResp.headers("Set-Cookie");
            String completeStatus = submitResp.header("X-PoWBlock-Status");
            if (completeStatus == null) completeStatus = submitResp.header("X-PoW-Status");
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
            if (announced) {
                // This request is done, the message about the proof of work is
                // no longer needed.
                Chan8PowNotifier.onPowFinished();
            }
            // Release waiting threads
            synchronized (Chan8PowInterceptor.class) {
                if (localLatch != null && _latch == localLatch) {
                    localLatch.countDown();
                    _latch = null;
                }
            }
        }
    }

    /**
     * True when the site was used recently enough that it is worth renewing
     * the proof of work in the background. The session is bound to the
     * address of the connection, so it only goes stale when the site is
     * actually being looked at.
     */
    public static boolean isUsedRecently() {
        return System.currentTimeMillis() - lastUseTime < USE_WINDOW_MS;
    }

    /** True when the response is the page of a proof of work challenge. */
    private static boolean isChallenge(Response response) throws java.io.IOException {        String powStatus = response.header("X-PoWBlock-Status");
        if (powStatus == null) powStatus = response.header("X-PoW-Status");
        if (powStatus != null && powStatus.equalsIgnoreCase("required")) return true;
        if (response.code() == 403) {
            String preview = response.peekBody(256).string();
            if (preview.contains("Proof of Work required") || preview.contains("403 PB")) return true;
        }
        if (response.code() == 200) {
            return response.peekBody(16).string().trim().startsWith("<");
        }
        return false;
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
            // The bypass header keeps this request out of the POW handling of
            // the interceptor: it is made by the interceptor itself, which is
            // already solving the POW and holds the lock for it.
            Request r = new Request.Builder().url(base + "/captcha.js")
                    .header(POW_BYPASS_HEADER, "1")
                    .header(SILENT_POW_HEADER, "1")
                    .build();
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
