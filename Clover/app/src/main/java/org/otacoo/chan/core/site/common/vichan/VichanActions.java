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
package org.otacoo.chan.core.site.common.vichan;

import static android.text.TextUtils.isEmpty;

import org.json.JSONObject;
import org.otacoo.chan.core.site.SiteAuthentication;
import org.otacoo.chan.core.site.common.CommonSite;
import org.otacoo.chan.core.site.common.MultipartHttpCall;
import org.otacoo.chan.core.site.http.DeleteRequest;
import org.otacoo.chan.core.site.http.DeleteResponse;
import org.otacoo.chan.core.site.http.Reply;
import org.otacoo.chan.core.site.http.ReplyResponse;
import org.otacoo.chan.utils.Logger;
import org.jsoup.Jsoup;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;
import okhttp3.Response;

public class VichanActions extends CommonSite.CommonActions {
    private static final String TAG = "VichanActions";

    public VichanActions(CommonSite commonSite) {
        super(commonSite);
    }

    @Override
    public void setupPost(Reply reply, MultipartHttpCall call) {
        call.parameter("board", reply.loadable.board.code);

        if (reply.loadable.isThreadMode()) {
            call.parameter("thread", String.valueOf(reply.loadable.no));
        }

        // Identifying the action is crucial for vichan
        call.parameter("post", "New Post");
        call.parameter("json_response", "1");

        call.parameter("password", reply.password);
        call.parameter("name", reply.name);
        call.parameter("email", reply.options);

        if (!isEmpty(reply.subject)) {
            call.parameter("subject", reply.subject);
        }

        call.parameter("body", reply.comment);

        if (reply.file != null) {
            call.fileParameter("file", reply.fileName, reply.file);
        }

        if (reply.spoilerImage) {
            call.parameter("spoiler", "on");
        }

        // Add json_response=1 to the URL as well to force API mode
        HttpUrl currentUrl = site.endpoints().reply(reply.loadable);
        call.url(currentUrl.newBuilder().addQueryParameter("json_response", "1").build());
    }

    @Override
    public boolean requirePrepare() {
        return true;
    }

    @Override
    public void prepare(MultipartHttpCall call, Reply reply, ReplyResponse replyResponse) {
        VichanAntispam antispam = new VichanAntispam(
                HttpUrl.parse(site.resolvable().desktopUrl(reply.loadable, null)));
        antispam.addDefaultIgnoreFields();
        for (Map.Entry<String, String> e : antispam.get().entrySet()) {
            call.parameter(e.getKey(), e.getValue());
        }
    }

    @Override
    public void handlePost(ReplyResponse replyResponse, Response response, String result) {
        Logger.i(TAG, "handlePost: body=" + (result != null ? result.substring(0, Math.min(result.length(), 500)).replace("\n", " ") : "null"));

        if (isEmpty(result)) {
            replyResponse.errorMessage = "Empty response from server";
            return;
        }

        // Try to parse as JSON first
        String trimResult = result.trim();
        if (trimResult.startsWith("{")) {
            try {
                JSONObject json = new JSONObject(trimResult);
                if (json.has("error")) {
                    replyResponse.errorMessage = json.getString("error");
                    return;
                }
                
                if (json.optBoolean("captcha", false)) {
                    replyResponse.requireAuthentication = true;
                    return;
                }

                if (json.has("id")) {
                    replyResponse.postNo = json.getInt("id");
                    replyResponse.threadNo = json.optInt("tid", replyResponse.postNo);
                    replyResponse.posted = true;
                    return;
                }
            } catch (Exception e) {
                Logger.e(TAG, "Error parsing JSON response", e);
            }
        }

        // Fallback to HTML parsing if JSON failed or was not returned
        Matcher auth = Pattern.compile("\"captcha\": ?true").matcher(result);
        Matcher err = errorPattern().matcher(result);
        if (auth.find()) {
            replyResponse.requireAuthentication = true;
        } else if (err.find()) {
            replyResponse.errorMessage = Jsoup.parse(err.group(1)).body().text();
        } else {
            // Check for successful redirect or body content
            HttpUrl url = response.request().url();
            String path = url.encodedPath();
            
            // Regex for vichan thread URLs: /board/res/123.html
            Matcher m = Pattern.compile("/\\w+/res/(\\d+).html").matcher(path);
            try {
                if (m.find()) {
                    replyResponse.threadNo = Integer.parseInt(m.group(1));
                    String fragment = url.encodedFragment();
                    if (fragment != null && fragment.matches("\\d+")) {
                        replyResponse.postNo = Integer.parseInt(fragment);
                    } else {
                        replyResponse.postNo = replyResponse.threadNo;
                    }
                    replyResponse.posted = true;
                } else if (result.contains("Post successful") || result.contains("Thread created")) {
                    replyResponse.posted = true;
                } else {
                    // Extract any visible text from the body if we're stuck on an error page
                    replyResponse.errorMessage = "Error posting: " + (result.length() > 100 ? "unknown response" : result);
                }
            } catch (NumberFormatException ignored) {
                replyResponse.errorMessage = "Error posting: could not find posted thread.";
            }
        }
    }

    @Override
    public void setupDelete(DeleteRequest deleteRequest, MultipartHttpCall call) {
        call.parameter("board", deleteRequest.post.board.code);
        call.parameter("delete", "Delete");
        call.parameter("delete_" + deleteRequest.post.no, "on");
        call.parameter("password", deleteRequest.savedReply.password);

        if (deleteRequest.imageOnly) {
            call.parameter("file", "on");
        }
    }

    @Override
    public void handleDelete(DeleteResponse response, Response httpResponse, String responseBody) {
        Matcher err = errorPattern().matcher(responseBody);
        if (err.find()) {
            response.errorMessage = Jsoup.parse(err.group(1)).body().text();
        } else {
            response.deleted = true;
        }
    }

    public Pattern errorPattern() {
        return Pattern.compile("<h1[^>]*>Error</h1>.*?<h2[^>]*>(.*?)</h2>", Pattern.DOTALL);
    }

    @Override
    public SiteAuthentication postAuthenticate() {
        return SiteAuthentication.fromNone();
    }
}
