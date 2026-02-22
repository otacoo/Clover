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
package org.otacoo.chan.core.net;

import java.util.concurrent.Semaphore;

/** Shared rate-limiter and domain-failover helper for 8chan.moe / 8chan.st. */
public final class Chan8RateLimit {

    private static final int MAX_CONCURRENT = 8;
    private static final Semaphore SEMAPHORE = new Semaphore(MAX_CONCURRENT, true);

    /** Currently active domain; switches to the other on connectivity failure. */
    private static volatile String activeDomain = "8chan.moe";

    private Chan8RateLimit() {}

    public static boolean is8chan(String url) {
        return url.contains("8chan.moe") || url.contains("8chan.st");
    }

    public static boolean isMedia(String url) {
        return is8chan(url) && url.contains("/.media/");
    }

    /** Returns the currently active 8chan domain (e.g. {@code "8chan.moe"}). */
    public static String getActiveDomain() {
        return activeDomain;
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
     * Called when a request to {@code domain} fails with a connectivity error.
     * Switches to the other domain so subsequent requests use the fallback.
     */
    public static void notifyDomainUnreachable(String domain) {
        if (domain.equals(activeDomain)) {
            activeDomain = activeDomain.equals("8chan.moe") ? "8chan.st" : "8chan.moe";
        }
    }

    public static void acquire() throws InterruptedException {
        SEMAPHORE.acquire();
    }

    public static void release() {
        SEMAPHORE.release();
    }
}
