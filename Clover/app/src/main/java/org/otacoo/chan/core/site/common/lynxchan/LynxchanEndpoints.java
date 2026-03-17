package org.otacoo.chan.core.site.common.lynxchan;

import org.otacoo.chan.core.model.Post;
import org.otacoo.chan.core.model.orm.Board;
import org.otacoo.chan.core.model.orm.Loadable;
import org.otacoo.chan.core.site.common.CommonSite;

import java.util.Map;

import okhttp3.HttpUrl;

public class LynxchanEndpoints extends CommonSite.CommonEndpoints {
    protected final CommonSite.SimpleHttpUrl root;

    public LynxchanEndpoints(CommonSite commonSite, String rootUrl) {
        super(commonSite);
        root = new CommonSite.SimpleHttpUrl(rootUrl);
    }

    public HttpUrl root() {
        String url = root.url().toString();
        if (org.otacoo.chan.core.site.sites.chan8.Chan8RateLimit.is8chan(url)) {
            String rewritten = org.otacoo.chan.core.site.sites.chan8.Chan8RateLimit.rewriteToActiveDomain(url);
            HttpUrl parsed = HttpUrl.parse(rewritten);
            if (parsed != null) return parsed;
        }
        return root.url();
    }

    @Override
    public HttpUrl boards() {
        return root().newBuilder().addPathSegment("boards.js").build();
    }

    @Override
    public HttpUrl catalog(Board board) {
        return root().newBuilder()
                .addPathSegment(board.code)
                .addPathSegment("catalog.json")
                .build();
    }

    @Override
    public HttpUrl archive(Board board) {
        return null;
    }

    @Override
    public HttpUrl thread(Board board, Loadable loadable) {
        return root().newBuilder()
                .addPathSegment(board.code)
                .addPathSegment("res")
                .addPathSegment(loadable.no + ".json")
                .build();
    }

    @Override
    public HttpUrl imageUrl(Post.Builder post, Map<String, String> arg) {
        String path = arg.get("path");
        if (path == null) return null;
        if (path.startsWith("http")) return HttpUrl.parse(path);
        
        // Lynxchan media paths often start with /
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        
        return root().newBuilder().addPathSegments(path).build();
    }

    @Override
    public HttpUrl thumbnailUrl(Post.Builder post, boolean spoiler, Map<String, String> arg) {
        String thumb = arg.get("thumb");
        if (thumb == null) return imageUrl(post, arg);
        if (thumb.startsWith("http")) return HttpUrl.parse(thumb);
        if (thumb.startsWith("/")) thumb = thumb.substring(1);
        return root().newBuilder().addPathSegments(thumb).build();
    }

    @Override
    public HttpUrl icon(Post.Builder post, String icon, Map<String, String> arg) {
        String flagPath = arg.get("flag");
        if (flagPath != null) {
            if (flagPath.startsWith("/")) flagPath = flagPath.substring(1);
            return root().newBuilder().addPathSegments(flagPath).build();
        }
        return null;
    }

    @Override
    public HttpUrl reply(Loadable loadable) {
        String path = loadable.isThreadMode() ? "replyThread.js" : "newThread.js";
        return root().newBuilder().addPathSegment(path).addQueryParameter("json", "1").build();
    }

    @Override
    public HttpUrl delete(Post post) {
        return root().newBuilder()
                .addPathSegment(post.board.code)
                .addPathSegment("deletePost")
                .build();
    }

    @Override
    public HttpUrl login() {
        return root().newBuilder().addPathSegment("login.html").build();
    }

    public HttpUrl blockBypass() {
        return root().newBuilder().addPathSegment("blockBypass.js").build();
    }

    @Override
    public HttpUrl report(Post post) {
        return root().newBuilder()
                .addPathSegment(post.board.code)
                .addPathSegment("reportPost")
                .build();
    }

    public HttpUrl captcha() {
        return root().newBuilder().addPathSegment("captcha.js").build();
    }

    // Not used for 8chan (captcha is submitted inline with the post form).
    // May be needed for other Lynxchan sites with a separate captcha-solve step.
    public HttpUrl solveCaptcha() {
        return root().newBuilder().addPathSegment("solveCaptcha.js").build();
    }

    public HttpUrl renewBypass() {
        return root().newBuilder().addPathSegment("renewBypass.js").build();
    }

    public HttpUrl validateBypass() {
        return root().newBuilder().addPathSegment("validateBypass.js").build();
    }
}
