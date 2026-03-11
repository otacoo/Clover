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

import org.json.JSONArray;
import org.json.JSONObject;
import org.otacoo.chan.core.model.orm.Board;
import org.otacoo.chan.core.model.orm.Loadable;
import org.otacoo.chan.core.net.Chan8RateLimit;
import org.otacoo.chan.core.site.Boards;
import org.otacoo.chan.core.site.Site;
import org.otacoo.chan.core.site.SiteActions;
import org.otacoo.chan.core.site.SiteIcon;
import org.otacoo.chan.core.site.SiteSetting;
import org.otacoo.chan.core.site.common.CommonSite;
import org.otacoo.chan.core.site.common.lynxchan.LynxchanActions;
import org.otacoo.chan.core.site.common.lynxchan.LynxchanApi;
import org.otacoo.chan.core.site.common.lynxchan.LynxchanCommentParser;
import org.otacoo.chan.core.site.common.lynxchan.LynxchanEndpoints;
import org.otacoo.chan.core.site.http.HttpCall;
import org.otacoo.chan.core.site.http.LoginRequest;
import org.otacoo.chan.core.site.http.LoginResponse;
import org.otacoo.chan.core.site.http.ProgressRequestBody;
import org.otacoo.chan.core.settings.OptionSettingItem;
import org.otacoo.chan.core.settings.OptionsSetting;
import org.otacoo.chan.core.settings.SettingProvider;
import org.otacoo.chan.core.settings.SharedPreferencesSettingProvider;
import org.otacoo.chan.utils.AndroidUtils;
import org.otacoo.chan.utils.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

public class Chan8 extends CommonSite {

    public enum PreferredDomain implements OptionSettingItem {
        AUTO("auto"),
        MOE("moe"),
        ST("st"),
        CC("cc");

        private final String key;
        PreferredDomain(String k) { this.key = k; }

        @Override
        public String getKey() { return key; }
    }

    private OptionsSetting<PreferredDomain> preferredDomain;

    @Override
    public void initializeSettings() {
        super.initializeSettings();
        SettingProvider p = new SharedPreferencesSettingProvider(AndroidUtils.getPreferences());
        preferredDomain = new OptionsSetting<>(p, "preference_chan8_domain", PreferredDomain.class, PreferredDomain.AUTO);
        applyDomainPreference(preferredDomain.get());
        preferredDomain.addCallback((setting, value) -> applyDomainPreference(value));
    }

    private void applyDomainPreference(PreferredDomain pref) {
        switch (pref) {
            case MOE: Chan8RateLimit.setForcedDomain(Chan8RateLimit.PRIMARY_DOMAIN); break;
            case ST:  Chan8RateLimit.setForcedDomain(Chan8RateLimit.SECONDARY_DOMAIN); break;
            case CC:  Chan8RateLimit.setForcedDomain(Chan8RateLimit.TERTIARY_DOMAIN); break;
            default:  Chan8RateLimit.setForcedDomain(null); break;
        }
    }

    @Override
    public List<SiteSetting> settings() {
        List<SiteSetting> list = new ArrayList<>();
        list.add(SiteSetting.forOption(
                preferredDomain,
                "Force 8chan domain",
                Arrays.asList("Auto (failover)", "8chan.moe", "8chan.st", "8chan.cc")));
        return list;
    }

    public static final CommonSiteUrlHandler URL_HANDLER = new CommonSiteUrlHandler() {        @Override
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
        setName("8chan");
        setIcon(SiteIcon.fromAssets("icons/8chan.webp"));
        setResolvable(URL_HANDLER);

        // Engine is Lynxchan, boards are dynamic (fetched from /boards.js)
        setBoardsType(BoardsType.DYNAMIC);

        setEndpoints(new LynxchanEndpoints(this, "https://8chan.moe"));

        // Use Chan8Actions (extends LynxchanActions) for 8chan-specific login behaviour
        setActions(new Chan8Actions(this));
        setApi(new LynxchanApi(this));
        setParser(new LynxchanCommentParser());

        setConfig(new CommonConfig() {
            @Override
            public boolean feature(Feature feature) {
                return feature == Feature.POSTING || feature == Feature.LOGIN;
            }
        });
    }

    private static class Chan8Actions extends LynxchanActions {
        private static final String TAG = "Chan8Actions";

        Chan8Actions(CommonSite site) {
            super(site);
        }

        @Override
        public void boards(BoardsListener boardsListener) {
            HttpCall call = new HttpCall(site) {
                @Override
                public void setup(Request.Builder requestBuilder,
                        ProgressRequestBody.ProgressRequestListener progressListener) {
                }

                @Override
                public void process(Response response, String result) throws IOException {
                    try {
                        JSONObject obj = new JSONObject(result);
                        JSONArray arr = obj.getJSONArray("topBoards");
                        List<Board> boards = new ArrayList<>(arr.length());
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject b = arr.getJSONObject(i);
                            boards.add(Board.fromSiteNameCode(
                                    site,
                                    b.getString("boardName"),
                                    b.getString("boardUri")));
                        }
                        Logger.d(TAG, "boards: parsed " + boards.size() + " top boards");
                        boardsListener.onBoardsReceived(new Boards(boards));
                    } catch (Exception e) {
                        Logger.e(TAG, "boards: parse error", e);
                        throw new IOException("Failed to parse index.json", e);
                    }
                }
            };

            HttpUrl indexUrl = ((LynxchanEndpoints) site.endpoints()).root()
                    .newBuilder().addPathSegment("index.json").build();
            call.url(indexUrl.toString());
            site.getHttpCallManager().makeHttpCall(call, new HttpCall.HttpCallback<HttpCall>() {
                @Override
                public void onHttpSuccess(HttpCall httpCall) {}

                @Override
                public void onHttpFail(HttpCall httpCall, Exception e) {
                    Logger.e(TAG, "boards: fetch failed", e);
                    boardsListener.onBoardsFailed(
                            "Could not load board list. Please do the 8chan verification first by" + "completing the security check (PoW bypass), then try again.");
                }
            });
        }

        @Override
        public String verificationUrl() {
            // Check WebView CookieManager directly — it is the authoritative store on the main
            // thread and is always up to date after a verification session.
            String[] checkUrls = {"https://8chan.moe/", "https://8chan.st/", "https://8chan.cc/"};
            for (String url : checkUrls) {
                String cookies = android.webkit.CookieManager.getInstance().getCookie(url);
                if (cookies != null
                        && cookies.contains("POW_TOKEN")
                        && java.util.regex.Pattern.compile("\\bTOS\\w*=").matcher(cookies).find()) {
                    return null;
                }
            }
            return ((LynxchanEndpoints) site.endpoints()).root().toString();
        }
    }
}
