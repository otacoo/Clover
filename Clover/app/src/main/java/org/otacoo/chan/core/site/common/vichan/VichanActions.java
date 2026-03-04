/*
 * Clover - 4chan browser https://github.com/otacoo/Clover/
 * Copyright (C) 2014  Floens https://github.com/Floens/Clover/
 * Copyright (C) 2026 otacoo
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
import org.otacoo.chan.core.site.SiteUrlHandler;
import org.otacoo.chan.core.site.common.CommonSite;
import org.otacoo.chan.core.site.common.MultipartHttpCall;
import org.otacoo.chan.core.site.http.DeleteRequest;
import org.otacoo.chan.core.site.http.DeleteResponse;
import org.otacoo.chan.core.site.http.Reply;
import org.otacoo.chan.core.site.http.ReplyResponse;
import org.otacoo.chan.utils.Logger;
import org.jsoup.Jsoup;

import java.util.List;
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

        // Use standard browser-like post URL (no json_response query/body)
        call.url(site.endpoints().reply(reply.loadable));

        String referer = site.resolvable().desktopUrl(reply.loadable, null);  
        call.referer(referer);
        //Logger.d(TAG, "  URL set without json_response");
        //Logger.d(TAG, "  referer=" + referer);
    }

    private String generateRandomPassword() {
        return Long.toHexString(Double.doubleToLongBits(Math.random()));
    }

    @Override
    public boolean requirePrepare() {
        return true;
    }

    @Override
    public void prepare(MultipartHttpCall call, Reply reply, ReplyResponse replyResponse) {
        //Logger.d(TAG, "prepare: Extracting antispam fields");
        VichanAntispam antispam = new VichanAntispam(
                HttpUrl.parse(site.resolvable().desktopUrl(reply.loadable, null)));
        antispam.addDefaultIgnoreFields();

        String threadValue = reply.loadable.isThreadMode() ? String.valueOf(reply.loadable.no) : null;
        String password = generateRandomPassword();
        long antispamFetchStart = System.currentTimeMillis();
        VichanAntispam.PostFormData postFormData = antispam.getPostFormData(
                reply.comment,
                reply.loadable.board.code,
                threadValue,
                reply.name != null ? reply.name : "",
                reply.options != null ? reply.options : "",
                reply.subject != null ? reply.subject : "",
                password
        );
        long antispamFetchElapsed = System.currentTimeMillis() - antispamFetchStart;
        applyMinimumFetchToSubmitDelay(antispamFetchElapsed);

        String selectedCommentField = null;
        for (Map.Entry<String, String> e : postFormData.fields.entrySet()) {
            if (reply.comment != null && reply.comment.equals(e.getValue())) {
                selectedCommentField = e.getKey();
                break;
            }
        }

        //Logger.d(TAG, "prepare: Adding form fields in DOM order");
        String fileFieldName = postFormData.fileFieldName != null && !postFormData.fileFieldName.isEmpty()
                ? postFormData.fileFieldName
                : "file";
        boolean filesAdded = false;
        for (Map.Entry<String, String> e : postFormData.fields.entrySet()) {
            call.parameter(e.getKey(), e.getValue());
            String displayValue = e.getValue().length() > 50 ? e.getValue().substring(0, 50) + "..." : e.getValue();
            //Logger.d(TAG, "  Added form field: " + e.getKey() + "=" + displayValue);

            if (!filesAdded && postFormData.fileInsertAfterField != null
                    && postFormData.fileInsertAfterField.equals(e.getKey())) {
                filesAdded = addFiles(call, reply, fileFieldName);
            }
        }

        //Logger.d(TAG, "prepare: Selected commentField=" + (selectedCommentField != null ? selectedCommentField : "(not-detected)") + ", fileField=" + fileFieldName + ", fileInsertAfter=" + (postFormData.fileInsertAfterField != null ? postFormData.fileInsertAfterField : "(end)"));
        if (!filesAdded) {
            addFiles(call, reply, fileFieldName);
        }
        
        //Logger.d(TAG, "  json_response not used");
    }

    private void applyMinimumFetchToSubmitDelay(long fetchElapsedMs) {
        final long minDelayMs = 5000;
        long remainingMs = minDelayMs - fetchElapsedMs;
        if (remainingMs <= 0) {
            //Logger.d(TAG, "prepare: fetch->submit delay satisfied (elapsed=" + fetchElapsedMs + "ms)");
            return;
        }

        //Logger.d(TAG, "prepare: waiting " + remainingMs + "ms to satisfy minimum fetch->submit delay");
        try {
            Thread.sleep(remainingMs);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean addFiles(MultipartHttpCall call, Reply reply, String fileFieldName) {
        boolean added = false;
        if (!reply.fileAttachments.isEmpty()) {
            for (Reply.FileAttachment attachment : reply.fileAttachments) {
                //Logger.d(TAG, "  Adding file: " + attachment.fileName + " (size=" + attachment.file.length() + " bytes)");
                call.fileParameter(fileFieldName, attachment.fileName, attachment.file);
                if (attachment.spoiler) {
                    call.parameter("fileSpoiler", "on");
                }
                added = true;
            }
        } else if (reply.file != null) {
            //Logger.d(TAG, "  Adding file: " + reply.fileName + " (size=" + reply.file.length() + " bytes)");
            call.fileParameter(fileFieldName, reply.fileName, reply.file);
            if (reply.spoilerImage) {
                call.parameter("spoiler", "on");
            }
            added = true;
        }
        return added;
    }

    @Override
    public void handlePost(ReplyResponse replyResponse, Response response, String result) {
        //Logger.d(TAG, "handlePost: Response code=" + response.code() + ", length=" + result.length());
        //Logger.d(TAG, "handlePost: Response body=" + (result.length() > 200 ? result.substring(0, 200) + "..." : result));

        if (isEmpty(result)) {
            replyResponse.errorMessage = "Empty response from server";
            return;
        }

        // Try to parse as JSON first
        String trimResult = result.trim();
        if (trimResult.startsWith("{")) {
            try {
                JSONObject json = new JSONObject(trimResult);
                //Logger.d(TAG, "handlePost: Parsed as JSON");
                if (json.has("error")) {
                    replyResponse.errorMessage = json.getString("error");
                    Logger.d(TAG, "handlePost: JSON error: " + replyResponse.errorMessage);
                    return;
                }
                
                if (json.optBoolean("captcha", false)) {
                    replyResponse.requireAuthentication = true;
                    Logger.d(TAG, "handlePost: Captcha required");
                    return;
                }

                if (json.has("id")) {
                    replyResponse.postNo = json.getInt("id");
                    replyResponse.threadNo = json.optInt("tid", replyResponse.postNo);
                    replyResponse.posted = true;
                    //Logger.d(TAG, "handlePost: Post successful, postNo=" + replyResponse.postNo);
                    return;
                }
            } catch (Exception e) {
                Logger.e(TAG, "Error parsing JSON response", e);
            }
        }

        // Check for a server redirect chain indicating a successful post.
        // When vichan accepts a post without json_response, it issues a 303 redirect
        // to the thread page (with noko) or board index (without noko).
        // OkHttp follows the redirect automatically, so we inspect priorResponse().
        Response prior = response.priorResponse();
        if (prior != null && prior.isRedirect()) {
            replyResponse.posted = true;

            // Try to extract thread/post number from the redirect Location header
            String location = prior.header("Location");
            //Logger.d(TAG, "handlePost: Detected redirect " + prior.code() + ", Location=" + location);

            if (location != null) {
                Matcher locMatcher = Pattern.compile("/\\w+/res/(\\d+)\\.html(?:#(\\d+))?").matcher(location);
                if (locMatcher.find()) {
                    replyResponse.threadNo = Integer.parseInt(locMatcher.group(1));
                    if (locMatcher.group(2) != null) {
                        replyResponse.postNo = Integer.parseInt(locMatcher.group(2));
                    } else {
                        replyResponse.postNo = replyResponse.threadNo;
                    }
                }
            }

            // Also try the final URL after redirect
            if (replyResponse.threadNo == 0) {
                HttpUrl finalUrl = response.request().url();
                Matcher urlMatcher = Pattern.compile("/\\w+/res/(\\d+)\\.html").matcher(finalUrl.encodedPath());
                if (urlMatcher.find()) {
                    replyResponse.threadNo = Integer.parseInt(urlMatcher.group(1));
                    String fragment = finalUrl.encodedFragment();
                    if (fragment != null && fragment.matches("\\d+")) {
                        replyResponse.postNo = Integer.parseInt(fragment);
                    }
                }
            }

            // Try to extract the post number from vichan's "serv" JS cookie.
            // After a successful post, vichan sets: Set-Cookie: serv={"id":<postNo>}
            if (replyResponse.postNo == 0) {
                try {
                    Response r = prior;
                    while (r != null && replyResponse.postNo == 0) {
                        List<String> cookies = r.headers("Set-Cookie");
                        for (String cookie : cookies) {
                            if (cookie.startsWith("serv=")) {
                                String value = cookie.substring(5);
                                int semi = value.indexOf(';');
                                if (semi > 0) value = value.substring(0, semi);
                                value = java.net.URLDecoder.decode(value, "UTF-8");
                                JSONObject servJson = new JSONObject(value);
                                if (servJson.has("id")) {
                                    replyResponse.postNo = servJson.getInt("id");
                                    //Logger.d(TAG, "handlePost: Extracted postNo from serv cookie: " + replyResponse.postNo);
                                }
                                break;
                            }
                        }
                        r = r.priorResponse();
                    }
                } catch (Exception e) {
                    //Logger.d(TAG, "handlePost: Could not parse serv cookie: " + e.getMessage());
                }
            }

            //Logger.d(TAG, "handlePost: Post successful (server redirect " + prior.code()
            //        + "), threadNo=" + replyResponse.threadNo + ", postNo=" + replyResponse.postNo);
            return;
        }

        // Fallback to HTML parsing if JSON failed or was not returned
        Matcher auth = Pattern.compile("\"captcha\": ?true").matcher(result);
        Matcher err = errorPattern().matcher(result);
        if (auth.find()) {
            replyResponse.requireAuthentication = true;
            Logger.d(TAG, "handlePost: Captcha required (HTML)");
        } else if (err.find()) {
            replyResponse.errorMessage = Jsoup.parse(err.group(1)).body().text();
            Logger.d(TAG, "handlePost: HTML error: " + replyResponse.errorMessage);
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
                    //Logger.d(TAG, "handlePost: Post successful (redirect), postNo=" + replyResponse.postNo);
                } else if (result.contains("Post successful") || result.contains("Thread created")) {
                    replyResponse.posted = true;
                    //Logger.d(TAG, "handlePost: Post successful (text match)");
                } else {
                    // Extract any visible text from the body if we're stuck on an error page
                    replyResponse.errorMessage = "Error posting: " + (result.length() > 100 ? "unknown response" : result);
                    Logger.d(TAG, "handlePost: Unknown response: " + replyResponse.errorMessage);
                }
            } catch (NumberFormatException ignored) {
                replyResponse.errorMessage = "Error posting: could not find posted thread.";
                Logger.d(TAG, "handlePost: NumberFormatException: " + replyResponse.errorMessage);
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
