/*
 * Clover - 4chan browser https://github.com/otacoo/Clover/
 * Copyright (C) 2014  otacoo
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
package org.otacoo.chan.core.site.sites.chan8;

import java.util.concurrent.Semaphore;

/** Shared rate-limiter and domain-failover helper for 8chan.moe / 8chan.st. */
public final class Chan8RateLimit {

    private static final int MAX_CONCURRENT = 8;
    private static final Semaphore SEMAPHORE = new Semaphore(MAX_CONCURRENT, true);

    /** Primary domain, always the default on a fresh start. */
    public static final String PRIMARY_DOMAIN = "8chan.moe";
    /** Fallback domain when PRIMARY_DOMAIN is unreachable. */
    public static final String SECONDARY_DOMAIN = "8chan.st";

    private static final String[] DOMAIN_ORDER = new String[] {
        PRIMARY_DOMAIN,
        SECONDARY_DOMAIN
    };

    // Active domain for this session.  In-memory only — resets to primary on restart.
    private static volatile String activeDomain = PRIMARY_DOMAIN;

    // User-forced domain; null means auto-failover.
    private static volatile String forcedDomain = null;

    private Chan8RateLimit() {}

    public static boolean is8chan(String url) {
        return url.contains("8chan.moe") || url.contains("8chan.st");
    }

    public static boolean isMedia(String url) {
        return is8chan(url) && url.contains("/.media/");
    }

    // Returns the currently active 8chan domain
    public static String getActiveDomain() {
        String f = forcedDomain;
        return f != null ? f : activeDomain;
    }

    // Returns the forced domain, or null if in auto-failover mode.
    public static String getForcedDomain() {
        return forcedDomain;
    }

    // Forces all 8chan traffic to a specific domain for this session.
    public static void setForcedDomain(String domain) {
        if (domain == null) {
            forcedDomain = null;
        } else if (domain.equals(PRIMARY_DOMAIN) || domain.equals(SECONDARY_DOMAIN)) {
            forcedDomain = domain;
            activeDomain = domain;
        }
    }

    /**
     * Override the active domain for this session (in-memory only).
     * Called after the user completes verification on a non-primary domain,
     * so API calls continue to the domain that holds their session cookies.
     */
    public static void setActiveDomain(String domain) {
        if (domain != null &&
                (domain.equals("8chan.moe") || domain.equals("8chan.st"))) {
            activeDomain = domain;
        }
    }

    /**
     * Rewrites any 8chan URL to use the currently active domain.
     * No-op if the URL already uses the active domain or isn't an 8chan URL.
     */
    public static String rewriteToActiveDomain(String url) {
        String domain = activeDomain;
        if (url.contains(domain)) return url;
        if (url.contains("8chan.moe")) return url.replace("8chan.moe", domain);
        if (url.contains("8chan.st"))  return url.replace("8chan.st",  domain);
        return url;
    }

    /**
     * Called when a network-level failure (IOException, HTTP 5xx) occurs for
     * {@code domain}.  Switches to the fallback for the remainder of the session.
     * Only acts when the failing domain is the currently active one.
     */
    public static void notifyDomainUnreachable(String domain) {
        if (forcedDomain != null) return;
        if (domain != null && domain.equals(activeDomain)) {
            activeDomain = nextDomain(activeDomain);
        }
    }

    private static String nextDomain(String currentDomain) {
        for (int i = 0; i < DOMAIN_ORDER.length; i++) {
            if (DOMAIN_ORDER[i].equals(currentDomain)) {
                return DOMAIN_ORDER[(i + 1) % DOMAIN_ORDER.length];
            }
        }
        return PRIMARY_DOMAIN;
    }

    public static void acquire() throws InterruptedException {
        SEMAPHORE.acquire();
    }

    public static void release() {
        SEMAPHORE.release();
    }
}
