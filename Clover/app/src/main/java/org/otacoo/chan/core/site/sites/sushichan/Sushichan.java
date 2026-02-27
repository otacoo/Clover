/*
 * Clover - 4chan browser https://github.com/floens/Clover/
 * Copyright (C) 2014  floens
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
package org.otacoo.chan.core.site.sites.sushichan;

import static org.otacoo.chan.Chan.injector;

import android.widget.Toast;

import androidx.annotation.Nullable;

import org.otacoo.chan.core.model.Post;
import org.otacoo.chan.core.model.orm.Board;
import org.otacoo.chan.core.model.orm.Loadable;
import org.otacoo.chan.core.net.JsonReaderRequest;
import org.otacoo.chan.core.site.Boards;
import org.otacoo.chan.core.site.Site;
import org.otacoo.chan.core.site.SiteIcon;
import org.otacoo.chan.core.site.common.CommonSite;
import org.otacoo.chan.core.site.common.vichan.VichanActions;
import org.otacoo.chan.core.site.common.vichan.VichanApi;
import org.otacoo.chan.core.site.common.vichan.VichanBoardsRequest;
import org.otacoo.chan.core.site.common.vichan.VichanCommentParser;
import org.otacoo.chan.core.site.common.vichan.VichanEndpoints;
import org.otacoo.chan.utils.AndroidUtils;
import org.otacoo.chan.utils.Logger;

import java.util.ArrayList;
import java.util.List;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;

public class Sushichan extends CommonSite {
    private static final String TAG = "Sushichan";

    public static final CommonSiteUrlHandler URL_HANDLER = new CommonSiteUrlHandler() {
        @Override
        public Class<? extends Site> getSiteClass() {
            return Sushichan.class;
        }

        @Override
        public HttpUrl getUrl() {
            return HttpUrl.parse("https://sushigirl.cafe/");
        }

        @Override
        public String[] getNames() {
            return new String[]{"sushichan"};
        }

        @Override
        public String desktopUrl(Loadable loadable, @Nullable Post post) {
            if (loadable.isCatalogMode()) {
                return getUrl().newBuilder().addPathSegment(loadable.boardCode).toString();
            } else if (loadable.isThreadMode()) {
                return getUrl().newBuilder()
                        .addPathSegment(loadable.boardCode).addPathSegment("res")
                        .addPathSegment(loadable.no + ".html")
                        .toString();
            } else {
                return getUrl().toString();
            }
        }
    };

    @Override
    public void setup() {
        setName("Sushichan");
        setIcon(SiteIcon.fromAssets("icons/sushichan.webp"));

        setBoardsType(BoardsType.DYNAMIC);

        setResolvable(URL_HANDLER);

        setConfig(new CommonConfig() {
            @Override
            public boolean feature(Feature feature) {
                return feature == Feature.POSTING;
            }
        });

        setEndpoints(new VichanEndpoints(this,
                "https://sushigirl.cafe/",
                "https://sushigirl.cafe/"));

        setActions(new VichanActions(this) {
            @Override
            public void boards(final BoardsListener listener) {
                VichanBoardsRequest request = new VichanBoardsRequest(Sushichan.this, new JsonReaderRequest.RequestListener<>() {
                    @Override
                    public void onResponse(List<Board> response) {
                        listener.onBoardsReceived(new Boards(response));
                    }

                    @Override
                    public void onError(String error) {
                        Logger.e(TAG, "Failed to get boards: " + error);
                        Toast.makeText(AndroidUtils.getAppContext(), "Failed to load board list", Toast.LENGTH_LONG).show();
                        listener.onBoardsReceived(new Boards(new ArrayList<>()));
                    }
                });

                OkHttpClient client = injector().instance(OkHttpClient.class);
                Request okRequest = new Request.Builder()
                        .url(request.getUrl())
                        .build();
                client.newCall(okRequest).enqueue(request);
            }
        });

        setApi(new VichanApi(this));
        setParser(new VichanCommentParser());
    }
}
