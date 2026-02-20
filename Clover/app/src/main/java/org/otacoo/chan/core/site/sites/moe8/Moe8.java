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

import androidx.annotation.Nullable;

import org.otacoo.chan.core.model.Post;
import org.otacoo.chan.core.model.orm.Board;
import org.otacoo.chan.core.model.orm.Loadable;
import org.otacoo.chan.core.site.Site;
import org.otacoo.chan.core.site.SiteIcon;
import org.otacoo.chan.core.site.SiteRequestModifier;
import org.otacoo.chan.core.site.common.CommonSite;
import org.otacoo.chan.core.site.common.lynxchan.LynxchanActions;
import org.otacoo.chan.core.site.common.lynxchan.LynxchanApi;
import org.otacoo.chan.core.site.common.lynxchan.LynxchanCommentParser;
import org.otacoo.chan.core.site.common.lynxchan.LynxchanEndpoints;
import org.otacoo.chan.core.site.http.HttpCall;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import okhttp3.HttpUrl;
import okhttp3.Request;

/**
 * Compatibility for 8chan.moe.
 * Note: Main site has strong bot protections.
 */
public class Moe8 extends CommonSite {
    public static final CommonSiteUrlHandler URL_HANDLER = new CommonSiteUrlHandler() {
        @Override
        public Class<? extends Site> getSiteClass() {
            return Moe8.class;
        }

        @Override
        public HttpUrl getUrl() {
            return HttpUrl.parse("https://8chan.moe/");
        }

        @Override
        public String[] getNames() {
            return new String[]{"8chan.moe", "8chan", "moe8"};
        }

        @Override
        public boolean respondsTo(HttpUrl url) {
            String host = url.host();
            return host.equals("8chan.moe") || host.equals("8chan.st") || host.equals("dev.8ch.moe");
        }

        @Override
        public String desktopUrl(Loadable loadable, @Nullable Post post) {
            if (loadable.isCatalogMode()) {
                return getUrl().newBuilder().addPathSegment(loadable.boardCode).toString();
            } else if (loadable.isThreadMode()) {
                return getUrl().newBuilder()
                        .addPathSegment(loadable.boardCode).addPathSegment("res")
                        .addPathSegment(String.valueOf(loadable.no) + ".html")
                        .toString();
            } else {
                return getUrl().toString();
            }
        }
    };

    @Override
    public void setup() {
        setName("8chan.moe");
        setIcon(SiteIcon.fromAssets("icons/8moe.png"));

        // 8chan.moe uses dynamic boards, but we can provide some default/seed boards if necessary.
        // For now, setting it to DYNAMIC so board list can be fetched via API.
        setBoardsType(BoardsType.DYNAMIC);

        // Since we don't have a static list yet and it's dynamic, we'll let it fetch them.
        // For testing, user can add boards manually or we can add some here.
        setBoards(
            Board.fromSiteNameCode(this, "Random", "b"),
            Board.fromSiteNameCode(this, "Anime", "a"),
            Board.fromSiteNameCode(this, "Technology", "tech")
        );

        setResolvable(URL_HANDLER);

        setConfig(new CommonConfig() {
            @Override
            public boolean feature(Feature feature) {
                // Enabled LOGIN to allow user to solve PoWBlock and TOS verification in WebView via site settings.
                return feature == Feature.POSTING || feature == Feature.POST_DELETE || feature == Feature.POST_REPORT || feature == Feature.LOGIN;
            }
        });

        setEndpoints(new LynxchanEndpoints(this, "https://8chan.moe/"));
        
        setActions(new LynxchanActions(this));
        setApi(new LynxchanApi(this));
        setParser(new LynxchanCommentParser());

        setRequestModifier(new CommonRequestModifier() {
            @Override
            public void modifyHttpCall(HttpCall httpCall, Request.Builder requestBuilder) {
                // Copy cookies from CookieManager for bot protection (PoWBlock, TOS cookies, etc)
                String url = requestBuilder.build().url().toString();
                String cookies = android.webkit.CookieManager.getInstance().getCookie(url);
                
                if (cookies == null || cookies.isEmpty()) {
                    cookies = "TOS20250418=1";
                } else if (!cookies.contains("TOS")) {
                    cookies += "; TOS20250418=1";
                }
                
                requestBuilder.header("Cookie", cookies);
                
                requestBuilder.header("Accept", "application/json, text/javascript, */*; q=0.01");
                requestBuilder.header("X-Requested-With", "XMLHttpRequest");

                String referer = url;
                if (url.contains(".json")) {
                    int lastSlash = url.lastIndexOf('/');
                    if (lastSlash != -1) {
                        referer = url.substring(0, lastSlash) + "/";
                    }
                }
                requestBuilder.header("Referer", referer);
                requestBuilder.header("Origin", "https://8chan.moe");
                
                requestBuilder.header("Sec-Fetch-Dest", "empty");
                requestBuilder.header("Sec-Fetch-Mode", "cors");
                requestBuilder.header("Sec-Fetch-Site", "same-origin");
                
                requestBuilder.header("Sec-Ch-Ua", "\"Chromium\";v=\"133\", \"Google Chrome\";v=\"133\", \"Not;A=Brand\";v=\"99\"");
                requestBuilder.header("Sec-Ch-Ua-Mobile", "?1");
                requestBuilder.header("Sec-Ch-Ua-Platform", "\"Android\"");
            }

            @Override
            public void modifyVolleyHeaders(java.util.Map<String, String> headers, String url) {
                // Copy cookies from CookieManager for bot protection (PoWBlock, TOS cookies, etc)
                String cookies = android.webkit.CookieManager.getInstance().getCookie(url);
                
                // Add mandatory 8chan-specific cookies, usually these are set by the WebView after passing the splash page.
                // If they are missing, we add a fallback, though solving in WebView is preferred.
                if (cookies == null || cookies.isEmpty()) {
                    cookies = "TOS20250418=1";
                } else if (!cookies.contains("TOS")) {
                    cookies += "; TOS20250418=1";
                }
                
                headers.put("Cookie", cookies);
                
                // Set browser-like headers
                headers.put("Accept", "application/json, text/javascript, */*; q=0.01");
                headers.put("X-Requested-With", "XMLHttpRequest");
                
                String referer = url;
                if (url.contains(".json")) {
                    int lastSlash = url.lastIndexOf('/');
                    if (lastSlash != -1) {
                        referer = url.substring(0, lastSlash) + "/";
                    }
                }
                headers.put("Referer", referer);
                headers.put("Origin", "https://8chan.moe");
                
                // Add some fetch metadata
                headers.put("Sec-Fetch-Dest", "empty");
                headers.put("Sec-Fetch-Mode", "cors");
                headers.put("Sec-Fetch-Site", "same-origin");
                
                // Add mobile platform hints
                headers.put("Sec-Ch-Ua", "\"Chromium\";v=\"133\", \"Google Chrome\";v=\"133\", \"Not;A=Brand\";v=\"99\"");
                headers.put("Sec-Ch-Ua-Mobile", "?1");
                headers.put("Sec-Ch-Ua-Platform", "\"Android\"");
            }
        });
    }
}
