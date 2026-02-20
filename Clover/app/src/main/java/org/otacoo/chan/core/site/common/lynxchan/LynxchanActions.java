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
import okhttp3.Request;
import okhttp3.Response;

public class LynxchanActions extends CommonSite.CommonActions {
    public LynxchanActions(CommonSite commonSite) {
        super(commonSite);
    }

    @Override
    public void boards(BoardsListener boardsListener) {
        if (!site.getStaticBoards().isEmpty()) {
            boardsListener.onBoardsReceived(new Boards(site.getStaticBoards()));
            return;
        }

        HttpCall call = new HttpCall(site) {
            @Override
            public void setup(Request.Builder requestBuilder, ProgressRequestBody.ProgressRequestListener progressListener) {
            }

            @Override
            public void process(Response response, String result) throws IOException {
                try {
                    JSONArray arr;
                    if (result.trim().startsWith("{")) {
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
                    boardsListener.onBoardsReceived(new Boards(boards));
                } catch (Exception e) {
                    throw new IOException("Failed to parse boards list", e);
                }
            }
        };

        // Lynxchan boards list is paged. Using page=1 with boards.json
        call.url(site.endpoints().boards().newBuilder().addQueryParameter("page", "1").build().toString());
        site.getHttpCallManager().makeHttpCall(call, new HttpCall.HttpCallback<HttpCall>() {
            @Override
            public void onHttpSuccess(HttpCall httpCall) {
            }

            @Override
            public void onHttpFail(HttpCall httpCall, Exception e) {
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

        // Lynxchan file parameter is usually "files"
        if (reply.file != null) {
            call.fileParameter("files", reply.fileName, reply.file);
        }

        if (reply.captchaResponse != null) {
            call.parameter("captcha", reply.captchaResponse);
        }
    }

    @Override
    public void handlePost(ReplyResponse replyResponse, Response response, String result) {
        try {
            JSONObject json = new JSONObject(result);
            String status = json.optString("status");
            
            if ("ok".equals(status)) {
                replyResponse.posted = true;
                // Lynxchan typically returns the thread/post ID in the "data" field
                Object data = json.opt("data");
                if (data instanceof Integer) {
                    replyResponse.postNo = (Integer) data;
                    if (replyResponse.threadNo == 0) {
                        replyResponse.threadNo = replyResponse.postNo;
                    }
                }
            } else if ("error".equals(status)) {
                replyResponse.errorMessage = json.optString("data", "Unknown Lynxchan error");
                // Check if captcha is required
                if (replyResponse.errorMessage.toLowerCase().contains("captcha")) {
                    replyResponse.requireAuthentication = true;
                }
            } else {
                // Could be HTML or unexpected JSON
                replyResponse.errorMessage = "Unexpected response from server";
            }
        } catch (Exception e) {
            // Might be a redirect or HTML error page
            if (result.contains("Error") || result.contains("fail")) {
                replyResponse.errorMessage = "Post failed (possible server error)";
            } else {
                replyResponse.errorMessage = "Failed to parse server response";
            }
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
    public SiteAuthentication postAuthenticate() {
        return SiteAuthentication.fromNone();
    }
}
