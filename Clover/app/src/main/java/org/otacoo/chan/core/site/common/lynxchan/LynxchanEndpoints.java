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
        return root.url();
    }

    @Override
    public HttpUrl boards() {
        return root.builder().s("boards.js").url();
    }

    @Override
    public HttpUrl catalog(Board board) {
        return root.builder().s(board.code).s("catalog.json").url();
    }

    @Override
    public HttpUrl archive(Board board) {
        return null;
    }

    @Override
    public HttpUrl thread(Board board, Loadable loadable) {
        return root.builder().s(board.code).s("res").s(loadable.no + ".json").url();
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
        
        return root.builder().ss(path).url();
    }

    @Override
    public HttpUrl thumbnailUrl(Post.Builder post, boolean spoiler, Map<String, String> arg) {
        String thumb = arg.get("thumb");
        if (thumb == null) return imageUrl(post, arg);
        if (thumb.startsWith("http")) return HttpUrl.parse(thumb);
        if (thumb.startsWith("/")) thumb = thumb.substring(1);
        return root.builder().ss(thumb).url();
    }

    @Override
    public HttpUrl icon(Post.Builder post, String icon, Map<String, String> arg) {
        return null;
    }

    @Override
    public HttpUrl reply(Loadable loadable) {
        String path = loadable.isThreadMode() ? "replyThread.js" : "newThread.js";
        return root.builder().s(path).url().newBuilder().addQueryParameter("json", "1").build();
    }

    @Override
    public HttpUrl delete(Post post) {
        return root.builder().s(post.board.code).s("deletePost").url();
    }

    @Override
    public HttpUrl login() {
        return root.builder().s("login.html").url();
    }

    @Override
    public HttpUrl report(Post post) {
        return root.builder().s(post.board.code).s("reportPost").url();
    }
}
