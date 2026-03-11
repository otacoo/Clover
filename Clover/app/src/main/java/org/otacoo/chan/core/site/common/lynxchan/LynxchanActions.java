package org.otacoo.chan.core.site.common.lynxchan;

import static android.text.TextUtils.isEmpty;

import org.json.JSONArray;
import org.json.JSONObject;
import org.otacoo.chan.core.model.orm.Board;
import org.otacoo.chan.core.site.Boards;
import org.otacoo.chan.core.site.SiteAuthentication;
import org.otacoo.chan.core.site.common.CommonSite;
import org.otacoo.chan.core.site.common.MultipartHttpCall;
import org.otacoo.chan.core.site.http.DeleteRequest;
import org.otacoo.chan.core.site.http.DeleteResponse;
import org.otacoo.chan.core.site.http.HttpCall;
import org.otacoo.chan.core.site.http.Reply;
import org.otacoo.chan.core.site.http.ReplyResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.otacoo.chan.core.site.http.ProgressRequestBody;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import android.webkit.CookieManager;

import org.otacoo.chan.utils.Logger;

public class LynxchanActions extends CommonSite.CommonActions {
    private static final String TAG = "LynxchanActions";
    private boolean lastPostWasBypassable = false;

    public LynxchanActions(CommonSite commonSite) {
        super(commonSite);
    }

    @Override
    public void boards(BoardsListener boardsListener) {
        if (!site.getStaticBoards().isEmpty()) {
            Logger.d(TAG, "boards: using " + site.getStaticBoards().size() + " static boards");
            boardsListener.onBoardsReceived(new Boards(site.getStaticBoards()));
            return;
        }

        HttpCall call = new HttpCall(site) {
            @Override
            public void setup(Request.Builder requestBuilder, ProgressRequestBody.ProgressRequestListener progressListener) {
            }

            @Override
            public void process(Response response, String result) throws IOException {
                Logger.d(TAG, "boards process: HTTP " + response.code()
                        + " contentType=" + response.header("Content-Type")
                        + " bodyLen=" + result.length()
                        + " preview=" + result.substring(0, Math.min(300, result.length())).replace("\n", " "));
                try {
                    String trimmed = result.trim();
                    if (trimmed.startsWith("<")) {
                        // HTML response from /boards.js
                        // LynxChan formats board links with text like "/boardCode/ - Board Name"
                        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("/([^/\"\\s]+)/ - ([^<\r\n\"]+)");
                        java.util.regex.Matcher matcher = pattern.matcher(result);
                        List<Board> boards = new ArrayList<>();
                        java.util.Set<String> seen = new java.util.HashSet<>();
                        while (matcher.find()) {
                            String uri = matcher.group(1);
                            String name = matcher.group(2).trim();
                            if (seen.add(uri)) {
                                boards.add(Board.fromSiteNameCode(site, name, uri));
                            }
                        }
                        Logger.d(TAG, "boards process: parsed " + boards.size() + " boards from HTML");
                        boardsListener.onBoardsReceived(new Boards(boards));
                        return;
                    }
                    JSONArray arr;
                    if (trimmed.startsWith("{")) {
                        JSONObject obj = new JSONObject(result);
                        arr = obj.getJSONArray("boards");
                    } else {
                        arr = new JSONArray(result);
                    }
                    List<Board> boards = new ArrayList<>(arr.length());
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject b = arr.getJSONObject(i);
                        String uri = b.getString("boardUri");
                        String name = b.getString("boardName");
                        boards.add(Board.fromSiteNameCode(site, name, uri));
                    }
                    Logger.d(TAG, "boards process: parsed " + boards.size() + " boards from JSON");
                    boardsListener.onBoardsReceived(new Boards(boards));
                } catch (Exception e) {
                    Logger.e(TAG, "boards process: parse error", e);
                    throw new IOException("Failed to parse boards list", e);
                }
            }
        };

        // LynxChan board list — use the endpoint URL directly (boards.js returns full HTML list)
        String boardsUrl = site.endpoints().boards().toString();
        Logger.d(TAG, "boards: fetching " + boardsUrl);
        call.url(boardsUrl);
        site.getHttpCallManager().makeHttpCall(call, new HttpCall.HttpCallback<HttpCall>() {
            @Override
            public void onHttpSuccess(HttpCall httpCall) {
                Logger.d(TAG, "boards: onHttpSuccess");
            }

            @Override
            public void onHttpFail(HttpCall httpCall, Exception e) {
                Logger.e(TAG, "boards: onHttpFail", e);
                boardsListener.onBoardsReceived(new Boards(new ArrayList<>()));
            }
        });
    }

    @Override
    public void setupPost(Reply reply, MultipartHttpCall call) {
        // Lynxchan expected parameters
        call.parameter("boardUri", reply.loadable.board.code);
        
        if (reply.loadable.isThreadMode()) {
            call.parameter("threadId", String.valueOf(reply.loadable.no));
        }

        call.parameter("message", reply.comment);
        
        if (!isEmpty(reply.name)) {
            call.parameter("name", reply.name);
        }
        
        if (!isEmpty(reply.options)) {
            call.parameter("email", reply.options);
        }
        
        if (!isEmpty(reply.subject)) {
            call.parameter("subject", reply.subject);
        }
        
        if (!isEmpty(reply.password)) {
            call.parameter("password", reply.password);
        }

        if (!isEmpty(reply.flag)) {
            call.parameter("flag", reply.flag);
        }

        // Support both legacy single file and new multiple files
        if (!reply.fileAttachments.isEmpty()) {
            // New multiple file support
            for (Reply.FileAttachment attachment : reply.fileAttachments) {
                call.fileParameter("files", attachment.fileName, attachment.file);
            }
        } else if (reply.file != null) {
            // Legacy single file support
            call.fileParameter("files", reply.fileName, reply.file);
        }

        // Lynxchan captcha: challengeId goes in captchaId, the typed answer in captchaAnswer.
        // Also send the legacy "captcha" field for compatibility with older Lynxchan versions.
        if (!isEmpty(reply.captchaChallenge)) {
            call.parameter("captchaId", reply.captchaChallenge);
        }
        if (!isEmpty(reply.captchaResponse)) {
            call.parameter("captchaAnswer", reply.captchaResponse);
            call.parameter("captcha", reply.captchaResponse); // legacy fallback
        }
    }

    @Override
    public void handlePost(ReplyResponse replyResponse, Response response, String result) {
        Logger.i(TAG, "handlePost: HTTP " + response.code()
                + " body=" + result.substring(0, Math.min(result.length(), 512))
                        .replace("\n", "\\n"));

        // Sync cookies back to WebView so things like 'bypass' tokens are persisted.
        syncCookiesToWebView(response);

        // Lynxchan returns HTML instead of JSON for challenge/error pages when
        // cookies are stale — detect this before attempting JSON parse.
        String trimmed = result.trim();
        if (trimmed.startsWith("<") || trimmed.contains("<html") || trimmed.contains("<!DOCTYPE")) {
            replyResponse.requireAuthentication = true;
            replyResponse.errorMessage = "Session challenge page returned — please re-verify via Login.";
            Logger.w(TAG, "handlePost: received HTML response, treating as auth required");
            return;
        }

        try {
            JSONObject json = new JSONObject(result);
            String status = json.optString("status");
            Logger.i(TAG, "handlePost: status=" + status + " data=" + json.opt("data"));

            switch (status) {
                case "ok": {
                    replyResponse.posted = true;
                    lastPostWasBypassable = false;
                    // Lynxchan returns the new post/thread number in the "data" field.
                    Object data = json.opt("data");
                    if (data instanceof Number) {
                        replyResponse.postNo = ((Number) data).intValue();
                        if (replyResponse.threadNo == 0) {
                            replyResponse.threadNo = replyResponse.postNo;
                        }
                    } else if (data instanceof String) {
                        try {
                            replyResponse.postNo = Integer.parseInt((String) data);
                            if (replyResponse.threadNo == 0) {
                                replyResponse.threadNo = replyResponse.postNo;
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                    break;
                }
                case "error": {
                    String msg = json.optString("data", "Unknown Lynxchan error");
                    replyResponse.errorMessage = msg;
                    // Captcha-related errors should re-trigger the captcha UI.
                    String msgLower = msg.toLowerCase();
                    if (msgLower.contains("captcha") || msgLower.contains("verification")
                            || msgLower.contains("invalid") || msgLower.contains("expired")) {
                        replyResponse.requireAuthentication = true;
                    }
                    break;
                }
                case "bypassable":
                    // Server wants a block bypass solved before accepting the post.
                    replyResponse.requireAuthentication = true;
                    replyResponse.isBypass = true;
                    lastPostWasBypassable = true;
                    break;
                case "hashban":
                    replyResponse.probablyBanned = true;
                    replyResponse.errorMessage = "Your post was blocked (hash ban).";
                    break;
                case "idban":
                case "ban":
                    replyResponse.probablyBanned = true;
                    replyResponse.errorMessage = json.optString("data",
                            "You are banned from this board.");
                    break;
                default:
                    // Show the actual JSON so future debugging is easy.
                    replyResponse.errorMessage = "Server response: status=\"" + status
                            + "\" data=" + json.opt("data");
                    Logger.w(TAG, "handlePost: unhandled status — " + result);
                    break;
            }
        } catch (Exception e) {
            Logger.e(TAG, "handlePost: JSON parse failed — body=" + result, e);
            // If the body contains an obvious error phrase use it; otherwise
            // report the raw body so it appears in the error toast.
            if (result.length() > 0) {
                replyResponse.errorMessage = "Post failed: " + result.substring(0, Math.min(result.length(), 120));
            } else {
                replyResponse.errorMessage = "Failed to parse server response";
            }
        }
    }

    private void syncCookiesToWebView(Response response) {
        String url = response.request().url().toString();
        List<String> setCookies = response.headers("Set-Cookie");
        if (!setCookies.isEmpty()) {
            CookieManager cm = CookieManager.getInstance();
            for (String cookie : setCookies) {
                cm.setCookie(url, cookie);
            }
            cm.flush();
        }
    }

    @Override
    public void setupDelete(DeleteRequest deleteRequest, MultipartHttpCall call) {
        call.parameter("boardUri", deleteRequest.post.board.code);
        call.parameter("postId", String.valueOf(deleteRequest.post.no));
        call.parameter("password", deleteRequest.savedReply.password);
    }

    @Override
    public void handleDelete(DeleteResponse response, Response httpResponse, String responseBody) {
        try {
            JSONObject json = new JSONObject(responseBody);
            if ("ok".equals(json.optString("status"))) {
                response.deleted = true;
            } else {
                response.errorMessage = json.optString("data", "Delete failed");
            }
        } catch (Exception e) {
            response.errorMessage = "Failed to parse delete response";
        }
    }

    @Override
    public boolean postRequiresAuthentication() {
        return !isLoggedIn() || !hasBypassCookie();
    }

    @Override
    public SiteAuthentication postAuthenticate() {
        String root = ((LynxchanEndpoints) site.endpoints()).root().toString();
        if (lastPostWasBypassable) {
            return SiteAuthentication.fromLynxchanBypass(root);
        }
        if (isLoggedIn() && !hasBypassCookie()) {
            return SiteAuthentication.fromLynxchanBypass(root);
        }
        return SiteAuthentication.fromLynxchanCaptcha(root);
    }

    private boolean hasBypassCookie() {
        String[] urls = {"https://8chan.moe/", "https://8chan.st/", "https://8chan.cc/"};
        java.net.CookieManager cm = org.otacoo.chan.core.di.NetModule.getSharedCookieManager();
        if (cm != null) {
            for (String url : urls) {
                try {
                    java.util.List<java.net.HttpCookie> list =
                            cm.getCookieStore().get(new java.net.URI(url));
                    for (java.net.HttpCookie c : list) {
                        if ("bypass".equals(c.getName()) && !c.getValue().isEmpty()) return true;
                    }
                } catch (Exception ignored) {}
            }
        }
        CookieManager wvcm = CookieManager.getInstance();
        for (String url : urls) {
            String raw = wvcm.getCookie(url);
            if (raw != null && raw.contains("bypass=")) return true;
        }
        return false;
    }

    @Override
    public boolean isLoggedIn() {
        // Check cookies across all known domains for this site, since the user
        // may have verified on a mirror (e.g. 8chan.st) while API requests go
        // to another domain (e.g. 8chan.moe).
        HttpUrl root = ((LynxchanEndpoints) site.endpoints()).root();
        String host = root.host();
        
        // Collect cookies from the root URL and known 8chan mirrors
        String[] urls = {root.toString()};
        if (host.contains("8chan")) {
            urls = new String[]{
                "https://8chan.moe/",
                "https://8chan.st/",
                "https://8chan.cc/"
            };
        }
        
        StringBuilder allCookies = new StringBuilder();
        // Prefer the shared java.net cookie manager (centralized jar) when available
        java.net.CookieManager shared = org.otacoo.chan.core.di.NetModule.getSharedCookieManager();
        if (shared != null) {
            for (String url : urls) {
                try {
                    java.net.URI uri = new java.net.URI(url);
                    List<java.net.HttpCookie> list = shared.getCookieStore().get(uri);
                    for (java.net.HttpCookie hc : list) {
                        if (allCookies.length() > 0) allCookies.append("; ");
                        allCookies.append(hc.getName()).append("=").append(hc.getValue());
                    }
                } catch (Exception ignored) {}
            }
        } else {
            CookieManager cm = CookieManager.getInstance();
            for (String url : urls) {
                String c = cm.getCookie(url);
                if (c != null && !c.isEmpty()) {
                    if (allCookies.length() > 0) allCookies.append("; ");
                    allCookies.append(c);
                }
            }
        }
        
        String cookies = allCookies.toString();
        if (cookies.isEmpty()) return false;
        
        // Match any TOS cookie (e.g., TOS, TOS20250418) and POW_TOKEN
        boolean hasTOS = java.util.regex.Pattern.compile("\\bTOS\\w*=").matcher(cookies).find();
        return hasTOS && cookies.contains("POW_TOKEN");
    }
}
