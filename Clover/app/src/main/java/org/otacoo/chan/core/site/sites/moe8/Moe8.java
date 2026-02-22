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
import org.otacoo.chan.core.site.common.lynxchan.LynxchanApi;
import org.otacoo.chan.core.site.common.lynxchan.LynxchanCommentParser;
import org.otacoo.chan.core.site.common.lynxchan.LynxchanEndpoints;
import org.otacoo.chan.core.site.http.HttpCall;

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
        setName("8chan");
        setIcon(SiteIcon.fromAssets("icons/8moe.png"));

        // Only the top boards from the 8chan.moe frontpage are loaded automatically;
        // users can navigate to any board by entering its code directly.
        setBoardsType(BoardsType.DYNAMIC);

        setResolvable(URL_HANDLER);

        setConfig(new CommonConfig() {
            @Override
            public boolean feature(Feature feature) {
                // Enabled LOGIN to allow user to solve PoWBlock and TOS verification in WebView via site settings.
                return feature == Feature.POSTING || feature == Feature.POST_DELETE || feature == Feature.POST_REPORT || feature == Feature.LOGIN;
            }
        });

        setEndpoints(new LynxchanEndpoints(this, "https://8chan.moe/"));
        
        setActions(new Moe8Actions(this));
        setApi(new LynxchanApi(this));
        setParser(new LynxchanCommentParser());

        setRequestModifier(new CommonRequestModifier() {
            @Override
            public void modifyHttpCall(HttpCall httpCall, Request.Builder requestBuilder) {
                // Copy cookies from CookieManager for bot protection (PoWBlock, TOS cookies, etc)
                String url = requestBuilder.build().url().toString();
                // For /.media/ URLs use the root URL so we get all domain-wide cookies.
                String cookieLookupUrl = url.contains("/.media/") ? "https://8chan.moe/" : url;
                String cookies = android.webkit.CookieManager.getInstance().getCookie(cookieLookupUrl);
                
                if (cookies != null && !cookies.isEmpty()) {
                    requestBuilder.header("Cookie", cookies);
                }
                
                requestBuilder.header("Accept", "application/json, text/javascript, */*; q=0.01");
                requestBuilder.header("X-Requested-With", "XMLHttpRequest");

                // For media (/.media/) and API requests use site root as Referer.
                String referer;
                if (url.contains("/.media/")) {
                    referer = "https://8chan.moe/";
                } else if (url.contains(".json")) {
                    int lastSlash = url.lastIndexOf('/');
                    referer = lastSlash != -1 ? url.substring(0, lastSlash) + "/" : "https://8chan.moe/";
                } else {
                    referer = "https://8chan.moe/";
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
                // For /.media/ URLs use the root URL for cookie lookup so we get all
                // domain-wide cookies (date-based TOS, PoW tokens) set by the WebView flow.
                String cookieLookupUrl = url.contains("/.media/") ? "https://8chan.moe/" : url;
                String cookies = android.webkit.CookieManager.getInstance().getCookie(cookieLookupUrl);
                
                if (cookies != null && !cookies.isEmpty()) {
                    headers.put("Cookie", cookies);
                }
                
                // Set browser-like headers
                headers.put("Accept", "application/json, text/javascript, */*; q=0.01");
                headers.put("X-Requested-With", "XMLHttpRequest");
                
                // For media (/.media/) and API requests, use the site root as Referer.
                String referer;
                if (url.contains("/.media/")) {
                    referer = "https://8chan.moe/";
                } else if (url.contains(".json")) {
                    int lastSlash = url.lastIndexOf('/');
                    referer = lastSlash != -1 ? url.substring(0, lastSlash) + "/" : "https://8chan.moe/";
                } else {
                    referer = "https://8chan.moe/";
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
