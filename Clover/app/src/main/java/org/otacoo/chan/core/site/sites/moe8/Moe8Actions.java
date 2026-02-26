/*
 * Clover - 4chan browser https://github.com/otacoo/Clover/
 * Copyright (c) 2026  otacoo
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
package org.otacoo.chan.core.site.sites.moe8;

import org.otacoo.chan.core.net.Chan8RateLimit;
import org.otacoo.chan.core.site.SiteAuthentication;
import org.otacoo.chan.core.site.common.CommonSite;
import org.otacoo.chan.core.site.common.lynxchan.LynxchanActions;

/**
 * Actions for 8chan.moe (Moe8).
 * Boards are added by the user entering a board code directly (BoardsType.INFINITE).
 */
public class Moe8Actions extends LynxchanActions {
    public Moe8Actions(CommonSite site) {
        super(site);
    }

    /**
     * Returns the LynxChan native image captcha authentication for 8chan.
     * The captcha fetch URL is built dynamically using the active domain so it
     * works when the app fails over from 8chan.moe to 8chan.st.
     */
    @Override
    public SiteAuthentication postAuthenticate() {
        return SiteAuthentication.fromLynxchanCaptcha(
                "https://" + Chan8RateLimit.getActiveDomain() + "/");
    }
}
