package org.otacoo.chan.core.site.common.lynxchan;

import static org.otacoo.chan.core.site.SiteEndpoints.makeArgument;

import android.util.JsonReader;
import android.util.JsonToken;

import org.otacoo.chan.core.model.Post;
import org.otacoo.chan.core.model.PostImage;
import org.otacoo.chan.core.site.SiteEndpoints;
import org.otacoo.chan.core.site.common.CommonSite;
import org.otacoo.chan.core.site.parser.ChanReaderProcessingQueue;
import org.jsoup.parser.Parser;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import okhttp3.HttpUrl;

public class LynxchanApi extends CommonSite.CommonApi {
    private static final SimpleDateFormat ISO_8601;

    static {
        ISO_8601 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        ISO_8601.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    public LynxchanApi(CommonSite commonSite) {
        super(commonSite);
    }

    @Override
    public void loadThread(JsonReader reader, ChanReaderProcessingQueue queue) throws Exception {
        Post.Builder opBuilder = new Post.Builder();
        opBuilder.board(queue.getLoadable().board);
        opBuilder.op(true);
        opBuilder.opId(0);
        opBuilder.id(queue.getLoadable().no);
        opBuilder.setUnixTimestampSeconds(0);

        queue.setOp(opBuilder);

        boolean opAdded = false;

        reader.beginObject();
        boolean opMarkdownHandled = false;
        while (reader.hasNext()) {
            String key = reader.nextName();
            // Lynxchan thread JSON: OP fields are at the top level,
            // reply posts are in the "posts" array.
            if (key.equals("posts")) {
                // Flush OP before reading replies
                if (!opAdded) {
                    queue.addForParse(opBuilder);
                    opAdded = true;
                }
                reader.beginArray();
                while (reader.hasNext()) {
                    readPostObject(reader, queue, false);
                }
                reader.endArray();
            } else if (key.equals("flagData")) {
                parseFlagData(reader, queue.getLoadable().board);
            } else if ((key.equals("message") || key.equals("comment")) && opMarkdownHandled) {
                // "markdown" is the rendered HTML; "message" is raw source — skip raw source
                // if we already captured the HTML version.
                reader.skipValue();
            } else {
                if (key.equals("markdown")) opMarkdownHandled = true;
                readSinglePostField(reader, key, queue, opBuilder);
            }
        }
        reader.endObject();

        if (!opAdded) {
            queue.addForParse(opBuilder);
        }
    }

    @Override
    public void loadCatalog(JsonReader reader, ChanReaderProcessingQueue queue) throws Exception {
        // 8chan/Lynxchan catalog.json can be an array: [...] or an object: {"threads": [...]}
        // Try to detect by peaking.
        if (reader.peek() == JsonToken.BEGIN_ARRAY) {
            reader.beginArray();
            while (reader.hasNext()) {
                readPostObject(reader, queue, true);
            }
            reader.endArray();
        } else {
            // Iterate the outer object and read the threads array when found.
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                if (key.equals("threads")) {
                    reader.beginArray();
                    while (reader.hasNext()) {
                        readPostObject(reader, queue, true);
                    }
                    reader.endArray();
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();
        }
    }

    @Override
    public void readPostObject(JsonReader reader, ChanReaderProcessingQueue queue) throws Exception {
        readPostObject(reader, queue, true);
    }

    public void readPostObject(JsonReader reader, ChanReaderProcessingQueue queue, boolean isOp) throws Exception {
        Post.Builder builder = new Post.Builder();
        builder.board(queue.getLoadable().board);
        builder.op(isOp);
        builder.opId(isOp ? 0 : queue.getLoadable().no);
        builder.setUnixTimestampSeconds(0);
        builder.replies(0);
        builder.images(0);

        if (isOp && queue.getOp() == null) {
            queue.setOp(builder);
        }

        String standalonePath = null;
        String standaloneThumb = null;
        String standaloneMime  = null;
        String pendingFlag     = null;
        String pendingFlagCode = null;
        String pendingFlagName = null;

        reader.beginObject();
        boolean markdownHandled = false;
        while (reader.hasNext()) {
            String key = reader.nextName();
            if (key.equals("path") && reader.peek() == JsonToken.STRING) {
                standalonePath = reader.nextString();
            } else if (key.equals("thumb") && reader.peek() == JsonToken.STRING) {
                standaloneThumb = reader.nextString();
            } else if (key.equals("mime") && reader.peek() == JsonToken.STRING) {
                standaloneMime = reader.nextString();
            } else if (key.equals("flag") && reader.peek() == JsonToken.STRING) {
                pendingFlag = reader.nextString();
            } else if (key.equals("flagCode") && reader.peek() == JsonToken.STRING) {
                pendingFlagCode = reader.nextString();
            } else if (key.equals("flagName") && reader.peek() == JsonToken.STRING) {
                pendingFlagName = reader.nextString();
            } else if ((key.equals("message") || key.equals("comment")) && markdownHandled) {
                // "markdown" is the rendered HTML; "message" is raw source — skip raw source
                // if we already captured the HTML version.
                reader.skipValue();
            } else {
                if (key.equals("markdown")) markdownHandled = true;
                readSinglePostField(reader, key, queue, builder);
            }
        }
        reader.endObject();

        if (pendingFlag != null && pendingFlagName != null) {
            SiteEndpoints endpoints = queue.getLoadable().getSite().endpoints();
            Map<String, String> flagArgs = SiteEndpoints.makeArgument("flag", pendingFlag);
            HttpUrl flagUrl = endpoints.icon(builder, "country", flagArgs);
            if (flagUrl != null) {
                // Name hidden — uncomment to restore country/custom-flag label display:
                // String code = (pendingFlagCode != null && pendingFlagCode.startsWith("-"))
                //         ? pendingFlagCode.substring(1) : pendingFlagCode != null ? pendingFlagCode : "";
                // String iconName = pendingFlagName + "/" + code;
                builder.addHttpIcon(new org.otacoo.chan.core.model.PostHttpIcon(flagUrl, ""));
            }
        }

        if ((builder.images == null || builder.images.isEmpty())
                && (standalonePath != null || standaloneThumb != null)) {

            // Catalog entries supply only "thumb" (no "path"). Derive the full image path by
            // stripping the "t_" thumbnail prefix.  e.g. /.media/t_fd97ac… → /.media/fd97ac…
            String imagePath = standalonePath;
            if (imagePath == null && standaloneThumb != null) {
                int lastSlash = standaloneThumb.lastIndexOf('/');
                if (lastSlash != -1) {
                    String name = standaloneThumb.substring(lastSlash + 1);
                    if (name.startsWith("t_")) name = name.substring(2);
                    imagePath = standaloneThumb.substring(0, lastSlash + 1) + name;
                } else {
                    imagePath = standaloneThumb;
                }
            }

            // Extension: prefer explicit dot-extension in the path, fall back to mime type.
            String ext = "";
            if (imagePath != null) {
                int lastDot  = imagePath.lastIndexOf('.');
                int lastSlash = imagePath.lastIndexOf('/');
                if (lastDot != -1 && lastDot > lastSlash) {
                    ext = imagePath.substring(lastDot + 1).toLowerCase(Locale.US);
                }
            }
            if (ext.isEmpty() && standaloneMime != null) {
                switch (standaloneMime) {
                    case "image/jpeg":  ext = "jpg";  break;
                    case "image/jpg":   ext = "jpg";  break;
                    case "image/jxl":   ext = "jxl";  break;
                    case "image/png":   ext = "png";  break;
                    case "image/apng":  ext = "png";  break;
                    case "image/gif":   ext = "gif";  break;
                    case "image/avif":  ext = "avif"; break;
                    case "image/webp":  ext = "webp"; break;
                    case "image/bmp":   ext = "bmp";  break;
                    case "video/mp4":   ext = "mp4";  break;
                    case "video/webm":  ext = "webm"; break;
                    case "video/x-m4v": ext = "m4v";  break;
                    case "audio/ogg":   ext = "ogg";  break;
                    case "audio/mpeg":  ext = "mp3";  break;
                    case "audio/x-m4a": ext = "m4a";  break;
                    case "audio/x-wav": ext = "wav";  break;
                    default:
                        int slash = standaloneMime.indexOf('/');
                        if (slash != -1) ext = standaloneMime.substring(slash + 1);
                        break;
                }
            }

            // For catalog items, the image path is derived from the thumbnail by stripping
            // the "t_" prefix.  Thumbnails have no extension, so the derived path also lacks
            // one.  Append the known extension so the full-image URL resolves correctly.
            // APNG images are currently served without extension on 8chan
            boolean isApng = "image/apng".equals(standaloneMime);
            if (imagePath != null && !ext.isEmpty() && !isApng) {
                int dot   = imagePath.lastIndexOf('.');
                int sl    = imagePath.lastIndexOf('/');
                if (dot <= sl) imagePath = imagePath + "." + ext; // no extension yet
            }

            String usePath = imagePath != null ? imagePath : standaloneThumb;
            String filename = usePath;
            int lastSlash = usePath.lastIndexOf('/');
            if (lastSlash != -1) filename = usePath.substring(lastSlash + 1);
            // Strip trailing extension from the display name to avoid "foo.png.png" in the UI.
            if (!ext.isEmpty() && filename.toLowerCase(Locale.US).endsWith("." + ext)) {
                filename = filename.substring(0, filename.length() - ext.length() - 1);
            }

            // 8chan.moe uses flat /.media/ paths for everything.
            // Ensure the arguments passed to SiteEndpoints do not contain leading slashes
            // because HttpUrl.Builder#addPathSegments interprets them literally or doubles them.
            String cleanImagePath = imagePath;
            if (cleanImagePath != null && cleanImagePath.startsWith("/")) {
                cleanImagePath = cleanImagePath.substring(1);
            }
            String cleanThumbPath = standaloneThumb;
            if (cleanThumbPath != null && cleanThumbPath.startsWith("/")) {
                cleanThumbPath = cleanThumbPath.substring(1);
            }

            Map<String, String> args = SiteEndpoints.makeArgument("path", cleanImagePath, "thumb", cleanThumbPath);

            PostImage.Builder imageBuilder = new PostImage.Builder()
                .thumbnailUrl(queue.getLoadable().getSite().endpoints().thumbnailUrl(builder, false, args))
                .imageUrl(queue.getLoadable().getSite().endpoints().imageUrl(builder, args))
                .extension(ext)
                .filename(filename)
                .originalName(filename);

            List<PostImage> list = new ArrayList<>();
            list.add(imageBuilder.build());
            builder.images(list);
        }

        if (builder.id < 0) {
            // Safety: if no ID was found, ensure it's at least 0 to avoid crash.
            // In thread mode, we use the thread no as fallback for OP.
            if (isOp && queue.getLoadable().no > 0) {
                builder.id(queue.getLoadable().no);
            } else {
                builder.id(0);
            }
        }

        queue.addForParse(builder);
    }

    private void readSinglePostField(JsonReader reader, String key, ChanReaderProcessingQueue queue, Post.Builder builder) throws Exception {
        SiteEndpoints endpoints = queue.getLoadable().getSite().endpoints();

        switch (key) {
            case "postId":
            case "no":
            case "threadId": // 8chan catalog uses threadId for the OP id
                if (reader.peek() == JsonToken.NUMBER) {
                    builder.id(reader.nextInt());
                } else if (reader.peek() == JsonToken.STRING) {
                    try {
                        builder.id(Integer.parseInt(reader.nextString()));
                    } catch (NumberFormatException e) {
                        reader.skipValue();
                    }
                } else {
                    reader.skipValue();
                }
                break;
            case "subject":
                if (reader.peek() != JsonToken.NULL) {
                    builder.subject(reader.nextString());
                } else {
                    reader.skipValue();
                }
                break;
            case "name":
                if (reader.peek() != JsonToken.NULL) {
                    builder.name(reader.nextString());
                } else {
                    reader.skipValue();
                }
                break;
            case "markdown":
                if (reader.peek() != JsonToken.NULL) {
                    // Lynxchan uses bare \n between HTML elements; convert to <br> so
                    // the CommentParser renders line-breaks correctly.
                    String md = reader.nextString().replace("\n", "<br>");
                    md = md.replaceAll("\\[code\\](.*?)\\[/code\\]", "<pre><code>$1</code></pre>");
                    builder.comment(md);
                } else {
                    reader.skipValue();
                }
                break;
            case "message":
            case "comment":
                if (reader.peek() != JsonToken.NULL) {
                    builder.comment(reader.nextString());
                } else {
                    reader.skipValue();
                }
                break;
            case "creation":
            case "time":
            case "lastBump": // Lynxchan catalog threads use lastBump (ISO-8601) as the bump time
                try {
                    if (reader.peek() == JsonToken.NUMBER) {
                        builder.setUnixTimestampSeconds(reader.nextLong());
                    } else if (reader.peek() == JsonToken.STRING) {
                        String dateStr = reader.nextString();
                        builder.setUnixTimestampSeconds(ISO_8601.parse(dateStr).getTime() / 1000L);
                    } else {
                        reader.skipValue();
                    }
                } catch (Exception e) {
                    builder.setUnixTimestampSeconds(0);
                }
                break;
            case "files":
                reader.beginArray();
                List<PostImage> images = new ArrayList<>();
                while (reader.hasNext()) {
                    PostImage img = readPostImage(reader, builder, endpoints);
                    if (img != null) images.add(img);
                }
                reader.endArray();
                builder.images(images);
                break;
            case "id":
                if (reader.peek() == JsonToken.NUMBER) {
                    builder.id(reader.nextInt());
                } else if (reader.peek() == JsonToken.STRING) {
                    builder.posterId(reader.nextString());
                } else {
                    reader.skipValue();
                }
                break;
            case "signedRole":
                if (reader.peek() != JsonToken.NULL) {
                    builder.moderatorCapcode(reader.nextString());
                } else {
                    reader.skipValue();
                }
                break;
            case "postCount":
            case "postsCount":
            case "replyCount":
            case "totalPosts":
                if (reader.peek() == JsonToken.NUMBER) {
                    builder.replies(reader.nextInt());
                } else {
                    reader.skipValue();
                }
                break;
            case "fileCount":
            case "filesCount":
            case "imageCount":
            case "totalFiles":
                if (reader.peek() == JsonToken.NUMBER) {
                    builder.images(reader.nextInt());
                } else {
                    reader.skipValue();
                }
                break;
            case "locked":
                builder.closed(reader.nextBoolean());
                break;
            case "pinned":
                builder.sticky(reader.nextBoolean());
                break;
            case "cyclic":
                // cyclic means it doesn't bump or something, usually mapped to archived or similar in UI
                reader.skipValue();
                break;
            case "posts":
                // In catalog, "posts" might be recently replies to the OP.
                // We typically skip them in catalog view or handle them as previews.
                reader.skipValue();
                break;
            default:
                reader.skipValue();
                break;
        }
    }

    private PostImage readPostImage(JsonReader reader, Post.Builder builder, SiteEndpoints endpoints) throws Exception {
        String path = null;
        String thumb = null;
        String originalName = null;
        String mime = null;
        long size = 0;
        int width = 0;
        int height = 0;

        reader.beginObject();
        while (reader.hasNext()) {
            switch (reader.nextName()) {
                case "path":
                    if (reader.peek() != JsonToken.NULL) {
                        path = reader.nextString();
                    } else {
                        reader.skipValue();
                    }
                    break;
                case "thumb":
                    if (reader.peek() != JsonToken.NULL) {
                        thumb = reader.nextString();
                    } else {
                        reader.skipValue();
                    }
                    break;
                case "originalName":
                    if (reader.peek() != JsonToken.NULL) {
                        originalName = reader.nextString();
                    } else {
                        reader.skipValue();
                    }
                    break;
                case "size":
                    if (reader.peek() == JsonToken.NUMBER) {
                        size = reader.nextLong();
                    } else {
                        reader.skipValue();
                    }
                    break;
                case "width":
                    if (reader.peek() == JsonToken.NUMBER) {
                        width = reader.nextInt();
                    } else {
                        reader.skipValue();
                    }
                    break;
                case "height":
                    if (reader.peek() == JsonToken.NUMBER) {
                        height = reader.nextInt();
                    } else {
                        reader.skipValue();
                    }
                    break;
                case "mime":
                    if (reader.peek() != JsonToken.NULL) {
                        mime = reader.nextString();
                    } else {
                        reader.skipValue();
                    }
                    break;
                default:
                    reader.skipValue();
                    break;
            }
        }
        reader.endObject();

        if (path == null) return null;

        boolean isSpoiler = thumb != null &&
                (thumb.equals("/spoiler.png") || thumb.endsWith("custom.spoiler"));

        Map<String, String> args = makeArgument("path", path, "thumb", thumb);

        // Derive extension: prefer explicit extension in the path, fall back to mime type.
        String ext = "";
        if (path.contains(".")) {
            int dot = path.lastIndexOf('.');
            int slash = path.lastIndexOf('/');
            if (dot > slash) ext = path.substring(dot + 1).toLowerCase(Locale.US);
        }
        if (ext.isEmpty() && mime != null) {
            switch (mime) {
                case "image/jpeg":  ext = "jpg";  break;
                case "image/jpg":   ext = "jpg";  break;
                case "image/jxl":   ext = "jxl";  break;
                case "image/png":   ext = "png";  break;
                case "image/apng":  ext = "png";  break;
                case "image/gif":   ext = "gif";  break;
                case "image/avif":  ext = "avif"; break;
                case "image/webp":  ext = "webp"; break;
                case "image/bmp":   ext = "bmp";  break;
                case "video/mp4":   ext = "mp4";  break;
                case "video/webm":  ext = "webm"; break;
                case "video/x-m4v": ext = "m4v";  break;
                case "audio/ogg":   ext = "ogg";  break;
                case "audio/mpeg":  ext = "mp3";  break;
                case "audio/x-m4a": ext = "m4a";  break;
                case "audio/x-wav": ext = "wav";  break;
                default:
                    if (mime.contains("/")) ext = mime.substring(mime.indexOf('/') + 1);
                    break;
            }
        }

        // Strip the file extension from the display name when it is already embedded in
        // originalName (e.g. "80385381_p1.png") to avoid the doubled "foo.png.png" display.
        String displayName = originalName != null ? originalName : "image";
        if (!ext.isEmpty() && displayName.toLowerCase(Locale.US).endsWith("." + ext)) {
            displayName = displayName.substring(0, displayName.length() - ext.length() - 1);
        }
        HttpUrl thumbUrl = endpoints.thumbnailUrl(builder, false, args);
        HttpUrl realThumbUrl = thumbUrl;
        if (isSpoiler) {
            // Lynxchan real thumbnails use "t_<hash>" with no extension (e.g. /.media/t_abcd1234).
            int lastSlash = path.lastIndexOf('/');
            String filename = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
            int dot = filename.lastIndexOf('.');
            String basename = dot >= 0 ? filename.substring(0, dot) : filename;
            String dir = lastSlash >= 0 ? path.substring(0, lastSlash + 1) : "";
            String realThumbPath = dir + "t_" + basename;
            realThumbUrl = endpoints.thumbnailUrl(builder, false,
                    makeArgument("path", path, "thumb", realThumbPath));
        }
        return new PostImage.Builder()
                .originalName(originalName != null ? originalName : "image")
                .thumbnailUrl(realThumbUrl)
                .spoilerThumbnailUrl(thumbUrl)
                .imageUrl(endpoints.imageUrl(builder, args))
                .filename(displayName)
                .extension(ext)
                .imageWidth(width)
                .imageHeight(height)
                .spoiler(isSpoiler)
                .size(size)
                .build();
    }

    /** Parses the top-level {@code flagData} array and stores results in {@code board.boardFlags}. */
    private void parseFlagData(JsonReader reader,
            org.otacoo.chan.core.model.orm.Board board) throws Exception {
        board.boardFlags.clear();
        reader.beginArray();
        while (reader.hasNext()) {
            reader.beginObject();
            String id = null, name = null;
            while (reader.hasNext()) {
                String k = reader.nextName();
                if (k.equals("_id")) {
                    id = reader.nextString();
                } else if (k.equals("name")) {
                    name = reader.nextString();
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();
            if (id != null && name != null) {
                board.boardFlags.put(id, name);
            }
        }
        reader.endArray();
    }
}
