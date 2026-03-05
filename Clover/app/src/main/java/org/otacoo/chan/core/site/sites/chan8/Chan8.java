/*
 * Clover - 4chan browser https://github.com/otacoo/Clover/
 * Copyright (C) 2014  Floens https://github.com/Floens/Clover/
 * Copyright (C) 2026  otacoo
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

import androidx.annotation.Nullable;

import org.otacoo.chan.core.model.orm.Loadable;
import org.otacoo.chan.core.model.orm.Board;
import org.otacoo.chan.core.site.Site;
import org.otacoo.chan.core.site.SiteIcon;
import org.otacoo.chan.core.site.common.CommonSite;
import org.otacoo.chan.core.site.common.lynxchan.LynxchanActions;
import org.otacoo.chan.core.site.common.lynxchan.LynxchanApi;
import org.otacoo.chan.core.site.common.lynxchan.LynxchanCommentParser;
import org.otacoo.chan.core.site.common.lynxchan.LynxchanEndpoints;

import okhttp3.HttpUrl;

public class Chan8 extends CommonSite {
    public static final CommonSiteUrlHandler URL_HANDLER = new CommonSiteUrlHandler() {
        @Override
        public Class<? extends Site> getSiteClass() {
            return Chan8.class;
        }

        @Override
        public HttpUrl getUrl() {
            return HttpUrl.parse("https://8chan.moe/");
        }

        @Override
        public String[] getNames() {
            return new String[]{"8chan", "8chan.moe"};
        }

        @Override
        public String desktopUrl(Loadable loadable, @Nullable org.otacoo.chan.core.model.Post post) {
            // Basic URL builder, mirrors 4chan style paths. Can be customized later.
            if (loadable.isCatalogMode()) {
                return getUrl().newBuilder().addPathSegment(loadable.boardCode).toString();
            } else if (loadable.isThreadMode()) {
                return getUrl().newBuilder()
                        .addPathSegment(loadable.boardCode)
                        .addPathSegment("res")
                        .addPathSegment(loadable.no + ".html")
                        .toString();
            } else {
                return getUrl().toString();
            }
        }
    };

    @Override
    public void setup() {
        setName("8chan.moe");
        setIcon(SiteIcon.fromAssets("icons/8chan.webp"));
        setResolvable(URL_HANDLER);

        setBoards(
                Board.fromSiteNameCode(this, "Anime & Manga", "a"),
                Board.fromSiteNameCode(this, "Random", "b"),
                Board.fromSiteNameCode(this, "Gacha", "gacha"),
                Board.fromSiteNameCode(this, "Video Games", "v"),
                Board.fromSiteNameCode(this, "VTubers", "vyt")
        );

        // Engine is Lynxchan, boards are dynamic (added manually)
        setBoardsType(BoardsType.DYNAMIC);

        setEndpoints(new LynxchanEndpoints(this, "https://8chan.moe"));

        // Use the generic Lynxchan implementations for core behavior
        setActions(new LynxchanActions(this));
        setApi(new LynxchanApi(this));
        setParser(new LynxchanCommentParser());

        setConfig(new CommonConfig() {
            @Override
            public boolean feature(Feature feature) {
                return feature == Feature.POSTING;
            }
        });
    }
}
