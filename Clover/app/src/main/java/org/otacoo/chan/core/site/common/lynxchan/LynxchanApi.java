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
        while (reader.hasNext()) {
            String key = reader.nextName();
            if (key.endsWith("Posts") || key.equals("posts")) {
                // OP is added after reading all its fields
                if (!opAdded) {
                    queue.addForParse(opBuilder);
                    opAdded = true;
                }

                reader.beginArray();
                while (reader.hasNext()) {
                    readPostObject(reader, queue, false);
                }
                reader.endArray();
            } else {
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
        reader.beginArray();
        while (reader.hasNext()) {
            readPostObject(reader, queue, true);
        }
        reader.endArray();
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

        reader.beginObject();
        while (reader.hasNext()) {
            String key = reader.nextName();
            if (key.equals("path") && reader.peek() == JsonToken.STRING) {
                standalonePath = reader.nextString();
            } else if (key.equals("thumb") && reader.peek() == JsonToken.STRING) {
                standaloneThumb = reader.nextString();
            } else if (key.equals("mime") && reader.peek() == JsonToken.STRING) {
                standaloneMime = reader.nextString();
            } else {
                readSinglePostField(reader, key, queue, builder);
            }
        }
        reader.endObject();

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

            String usePath = imagePath != null ? imagePath : standaloneThumb;
            String filename = usePath;
            int lastSlash = usePath.lastIndexOf('/');
            if (lastSlash != -1) filename = usePath.substring(lastSlash + 1);

            Map<String, String> args = SiteEndpoints.makeArgument("path", imagePath, "thumb", standaloneThumb);

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
            case "threadId":
            case "postId":
            case "no":
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
                    images.add(readPostImage(reader, builder, endpoints));
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

        Map<String, String> args = makeArgument("path", path, "thumb", thumb);
        String ext = "";
        if (mime != null && mime.contains("/")) {
            ext = mime.split("/")[1];
        } else if (path.contains(".")) {
            ext = path.substring(path.lastIndexOf(".") + 1);
        }

        return new PostImage.Builder()
                .originalName(originalName != null ? originalName : "image")
                .thumbnailUrl(endpoints.thumbnailUrl(builder, false, args))
                .imageUrl(endpoints.imageUrl(builder, args))
                .filename(originalName != null ? originalName : "image")
                .extension(ext)
                .imageWidth(width)
                .imageHeight(height)
                .size(size)
                .build();
    }
}
