/*
 * Clover - 4chan browser https://github.com/Floens/Clover/
 * Copyright (C) 2014  Floens
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
package org.otacoo.chan.core.site;


import org.otacoo.chan.core.model.orm.SiteModel;
import org.otacoo.chan.core.repository.SiteRepository;
import org.otacoo.chan.core.settings.json.JsonSettings;
import org.otacoo.chan.utils.Logger;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class SiteService {
    private static boolean addSiteForLegacy = false;

    /**
     * Called from the DatabaseHelper when upgrading to the tables with a site id.
     */
    public static void addSiteForLegacy() {
        addSiteForLegacy = true;
    }

    private SiteRepository siteRepository;
    private SiteResolver resolver;

    private boolean initialized = false;

    @Inject
    public SiteService(SiteRepository siteRepository, SiteResolver resolver) {
        this.siteRepository = siteRepository;
        this.resolver = resolver;
    }

    public boolean areSitesSetup() {
        return !siteRepository.all().getAll().isEmpty();
    }

    public void addSite(String url, SiteAddCallback callback) {
        Site existing = resolver.findSiteForUrl(url);
        if (existing != null) {
            callback.onSiteAddFailed("site already added");
            return;
        }

        SiteResolver.SiteResolverResult resolve = resolver.resolveSiteForUrl(url);

        Class<? extends Site> siteClass;
        if (resolve.match == SiteResolver.SiteResolverResult.Match.BUILTIN) {
            siteClass = resolve.builtinResult;
        } else if (resolve.match == SiteResolver.SiteResolverResult.Match.EXTERNAL) {
            callback.onSiteAddFailed("external sites not hardcoded is not implemented yet");
            return;
        } else {
            callback.onSiteAddFailed("not a url");
            return;
        }

        Site site = siteRepository.createFromClass(siteClass);

        callback.onSiteAdded(site);
    }

    public void updateUserSettings(Site site, JsonSettings jsonSettings) {
        SiteModel siteModel = siteRepository.byId(site.id());
        if (siteModel == null) throw new NullPointerException("siteModel == null");
        siteRepository.updateSiteUserSettingsAsync(siteModel, jsonSettings);
    }

    public void updateOrdering(List<Site> sitesInNewOrder) {
        siteRepository.updateSiteOrderingAsync(sitesInNewOrder);
    }

    public void initialize() {
        if (initialized) {
            throw new IllegalStateException("Already initialized");
        }
        initialized = true;

        if (addSiteForLegacy) {
            addSiteForLegacy = false;
            siteRepository.addLegacySite();
        }

        siteRepository.initialize();

        List<Site> existingSites = siteRepository.all().getAll();

        // Remove invalid sites (e.g. from removed sites like Sushichan/Lainchan)
        for (Site existing : existingSites) {
            if (existing instanceof SiteRepository.InvalidSite) {
                siteRepository.removeSite(existing);
            }
        }

        // Refresh list after removals
        existingSites = siteRepository.all().getAll();

        for (SiteUrlHandler handler : SiteRegistry.URL_HANDLERS) {
            boolean exists = false;
            for (Site existing : existingSites) {
                if (existing.getClass().equals(handler.getSiteClass())) {
                    if (exists) {
                        siteRepository.removeSite(existing);
                    } else {
                        exists = true;
                    }
                }
            }

            if (!exists) {                Logger.d("SiteService", "Adding missing default site: " + handler.getSiteClass().getName());                siteRepository.createFromClass(handler.getSiteClass());
            }
        }
    }

    public interface SiteAddCallback {
        void onSiteAdded(Site site);

        void onSiteAddFailed(String message);
    }
}
