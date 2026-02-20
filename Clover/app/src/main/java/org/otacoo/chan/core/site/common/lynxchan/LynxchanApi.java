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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
        builder.setUnixTimestampSeconds(0); // Default to avoid "Post data not complete"

        reader.beginObject();
        while (reader.hasNext()) {
            readSinglePostField(reader, reader.nextName(), queue, builder);
        }
        reader.endObject();

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
                    } else {
                        String dateStr = reader.nextString();
                        builder.setUnixTimestampSeconds(ISO_8601.parse(dateStr).getTime() / 1000L);
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
                builder.moderatorCapcode(reader.nextString());
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
                    path = reader.nextString();
                    break;
                case "thumb":
                    thumb = reader.nextString();
                    break;
                case "originalName":
                    originalName = reader.nextString();
                    break;
                case "size":
                    size = reader.nextLong();
                    break;
                case "width":
                    width = reader.nextInt();
                    break;
                case "height":
                    height = reader.nextInt();
                    break;
                case "mime":
                    mime = reader.nextString();
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
                .filename(originalName)
                .extension(ext)
                .imageWidth(width)
                .imageHeight(height)
                .size(size)
                .build();
    }
}
