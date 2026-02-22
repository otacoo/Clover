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

import org.otacoo.chan.core.net.Chan8RateLimit;

import java.util.Map;

/**
 * Compatibility for 8chan.moe.
 * Note: Main site has strong bot protections.
 */
public class Moe8 extends CommonSite {

    private static final String ROOT = "https://8chan.moe/";

    public static final CommonSiteUrlHandler URL_HANDLER = new CommonSiteUrlHandler() {
        @Override
        public Class<? extends Site> getSiteClass() {
            return Moe8.class;
        }

        @Override
        public HttpUrl getUrl() {
            return HttpUrl.parse(ROOT);
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

        // Boards are not fetched at startup. Users add boards by entering their code directly.
        setBoardsType(BoardsType.INFINITE);

        setResolvable(URL_HANDLER);

        setConfig(new CommonConfig() {
            @Override
            public boolean feature(Feature feature) {
                return feature == Feature.POSTING || feature == Feature.POST_DELETE || feature == Feature.POST_REPORT || feature == Feature.LOGIN;
            }
        });

        setEndpoints(new LynxchanEndpoints(this, ROOT) {
            @Override
            public HttpUrl login() {
                return root.url();
            }

            // Build URLs against the currently active domain so requests
            // automatically switch to 8chan.st if 8chan.moe is unreachable.
            private HttpUrl base() {
                return HttpUrl.parse("https://" + Chan8RateLimit.getActiveDomain() + "/");
            }

            @Override
            public HttpUrl catalog(Board board) {
                return base().newBuilder().addPathSegments(board.code + "/catalog.json").build();
            }

            @Override
            public HttpUrl thread(Board board, Loadable loadable) {
                return base().newBuilder().addPathSegments(board.code + "/res/" + loadable.no + ".json").build();
            }

            @Override
            public HttpUrl imageUrl(Post.Builder post, Map<String, String> arg) {
                String path = arg.get("path");
                if (path == null) return null;
                if (path.startsWith("http")) return HttpUrl.parse(path);
                if (path.startsWith("/")) path = path.substring(1);
                return base().newBuilder().addPathSegments(path).build();
            }

            @Override
            public HttpUrl thumbnailUrl(Post.Builder post, boolean spoiler, Map<String, String> arg) {
                String thumb = arg.get("thumb");
                if (thumb == null) return imageUrl(post, arg);
                if (thumb.startsWith("http")) return HttpUrl.parse(thumb);
                if (thumb.startsWith("/")) thumb = thumb.substring(1);
                return base().newBuilder().addPathSegments(thumb).build();
            }
        });
        
        setActions(new Moe8Actions(this));
        setApi(new LynxchanApi(this));
        setParser(new LynxchanCommentParser());

        setRequestModifier(new CommonRequestModifier() {
            @Override
            public void modifyHttpCall(HttpCall httpCall, Request.Builder requestBuilder) {
                String url = requestBuilder.build().url().toString();
                String cookies = android.webkit.CookieManager.getInstance().getCookie(url);
                if (cookies != null && !cookies.isEmpty()) {
                    requestBuilder.header("Cookie", cookies);
                }

                requestBuilder.header("Accept", "application/json, text/javascript, */*; q=0.01");
                requestBuilder.header("X-Requested-With", "XMLHttpRequest");

                String referer;
                if (url.contains(".json")) {
                    int lastSlash = url.lastIndexOf('/');
                    referer = lastSlash != -1 ? url.substring(0, lastSlash) + "/" : ROOT;
                } else {
                    referer = ROOT;
                }
                requestBuilder.header("Referer", referer);
                requestBuilder.header("Origin", "https://8chan.moe");
                requestBuilder.header("Sec-Fetch-Dest", "empty");
                requestBuilder.header("Sec-Fetch-Mode", "cors");
                requestBuilder.header("Sec-Fetch-Site", "same-origin");
            }

            @Override
            public void modifyVolleyHeaders(java.util.Map<String, String> headers, String url) {
                String cookies = android.webkit.CookieManager.getInstance().getCookie(url);
                if (cookies != null && !cookies.isEmpty()) {
                    headers.put("Cookie", cookies);
                }

                boolean isMedia = url.contains("/.media/");
                if (isMedia) {
                    String lower = url.toLowerCase();
                    boolean isVideo = lower.endsWith(".webm") || lower.endsWith(".mp4")
                            || lower.endsWith(".mov") || lower.endsWith(".ogg");
                    headers.put("Accept", isVideo
                            ? "video/webm,video/mp4,video/*;q=0.9,*/*;q=0.5"
                            : "image/webp,image/apng,image/*,*/*;q=0.8");
                    headers.put("Sec-Fetch-Dest", isVideo ? "video" : "image");
                    headers.put("Sec-Fetch-Mode", "no-cors");
                    headers.put("Accept-Language", "en-US,en;q=0.9");
                } else {
                    headers.put("Accept", "application/json, text/javascript, */*; q=0.01");
                    headers.put("X-Requested-With", "XMLHttpRequest");
                    headers.put("Sec-Fetch-Dest", "empty");
                    headers.put("Sec-Fetch-Mode", "cors");
                }

                String referer;
                if (isMedia) {
                    referer = ROOT;
                } else if (url.contains(".json")) {
                    int lastSlash = url.lastIndexOf('/');
                    referer = lastSlash != -1 ? url.substring(0, lastSlash) + "/" : ROOT;
                } else {
                    referer = ROOT;
                }
                headers.put("Referer", referer);
                headers.put("Sec-Fetch-Site", "same-origin");
            }
        });
    }
}
