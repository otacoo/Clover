package org.otacoo.chan.core.net;

/**
 * Marks a request as part of the automatic updating of a thread. The user is
 * not waiting for such a request, it only refreshes what is on the screen.
 * Requests marked like this do not tell the user about things that happen
 * while they are looking at the site.
 */
public final class BackgroundRequest {
    public static final BackgroundRequest INSTANCE = new BackgroundRequest();

    private BackgroundRequest() {}
}
