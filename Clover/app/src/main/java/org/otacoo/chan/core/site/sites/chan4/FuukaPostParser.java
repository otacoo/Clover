/*
 * Clover - 4chan browser
 * Copyright (C) 2014  Floens https://github.com/Floens/Clover/
 * Copyright (C) 2026  otacoo https://github.com/otacoo/Clover/
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
package org.otacoo.chan.core.site.sites.chan4;

import android.util.JsonReader;
import android.util.JsonToken;

import org.otacoo.chan.core.model.Post;
import org.otacoo.chan.core.model.PostImage;
import org.otacoo.chan.core.model.orm.Board;
import org.otacoo.chan.utils.Logger;

import java.io.IOException;
import java.io.StringReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

import okhttp3.HttpUrl;

/**
 * Parses a fuuka/foolfuuka archive thread JSON response
 * ({@code /_/api/chan/thread/?board=X&num=N}) into Post.Builders.
 * <p>
 * Two API formats exist:
 * <ul>
 * <li><b>Classic</b> (fuuka, foolfuuka &lt; 2.2): object keyed by post number
 * with flat 4chan-style fields ({@code no}, {@code resto}, {@code com},
 * {@code tim}, {@code ext}, ...).</li>
 * <li><b>Foolfuuka 2.2+</b>: object keyed by thread number with nested
 * {@code op} and {@code posts} members; post fields are {@code num},
 * {@code timestamp}, {@code title}, {@code comment_processed},
 * {@code poster_hash}, {@code media {media_orig, media_filename, thumb_link,
 * remote_media_link, ...}}.</li>
 * </ul>
 * Parsing is deliberately tolerant: bad fields or bad posts are skipped
 * instead of aborting the whole response.
 */
public class FuukaPostParser {
    private static final String TAG = "FuukaPostParser";

    public static class Result {
        public final List<Post.Builder> posts;
        public Post.Builder op;
        public final Set<Integer> deletedNos = new HashSet<>();

        public Result(List<Post.Builder> posts, Post.Builder op) {
            this.posts = posts;
            this.op = op;
        }
    }

    private static class ParsedPost {
        final Post.Builder post;
        final Post.Builder opSnapshot; // non-null when this post is the OP
        final boolean deleted;

        ParsedPost(Post.Builder post, Post.Builder opSnapshot, boolean deleted) {
            this.post = post;
            this.opSnapshot = opSnapshot;
            this.deleted = deleted;
        }
    }

    public static Result parse(String json, Board board, String domain, int threadNo) {
        List<Post.Builder> builders = new ArrayList<>();
        Result result = new Result(builders, null);

        JsonReader reader = new JsonReader(new StringReader(json));
        try {
            reader.setLenient(true);
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                int keyInt = -1;
                try {
                    keyInt = Integer.parseInt(key);
                } catch (NumberFormatException ignored) {
                }

                reader.beginObject();
                String firstKey = reader.hasNext() ? reader.nextName() : null;
                if ("posts".equals(firstKey) || "op".equals(firstKey)) {
                    // Foolfuuka 2.2+: thread entry with nested op/posts.
                    parseThreadEntry(reader, firstKey, board, domain, threadNo, result);
                } else {
                    // Classic fuuka: flat post object.
                    try {
                        addParsed(result, parseFlatPost(reader, firstKey, board, domain, keyInt, threadNo));
                    } catch (Exception e) {
                        Logger.w(TAG, "Skipping post " + keyInt + ": " + e.getMessage());
                    }
                }
                reader.endObject();
            }
            reader.endObject();
        } catch (Exception e) {
            Logger.e(TAG, "Failed to parse archive json", e);
        } finally {
            try {
                reader.close();
            } catch (IOException ignored) {
            }
        }

        Logger.d(TAG, "Parsed " + builders.size() + " posts from " + domain);

        Collections.sort(builders, Comparator.comparingInt(b -> b.id));

        // If no post had resto==0 (some archives omit it), the lowest
        // numbered post (the thread number) is the OP.
        if (result.op == null && !builders.isEmpty()) {
            Post.Builder first = builders.get(0);
            if (first.id == threadNo) {
                first.op(true);
                result.op = new Post.Builder()
                        .closed(first.closed)
                        .archived(first.archived)
                        .sticky(first.sticky)
                        .replies(first.replies)
                        .images(first.imagesCount)
                        .uniqueIps(first.uniqueIps)
                        .lastModified(first.lastModified);
            }
        }

        return result;
    }

    private static void addParsed(Result result, ParsedPost parsed) {
        if (parsed == null) return;
        if (parsed.opSnapshot != null && result.op == null) {
            result.op = parsed.opSnapshot;
        }
        if (parsed.deleted) {
            result.deletedNos.add(parsed.post.id);
        }
        result.posts.add(parsed.post);
    }

    // ---------- Foolfuuka 2.2+ nested format ----------

    private static void parseThreadEntry(JsonReader reader, String firstKey, Board board, String domain, int threadNo, Result result) throws IOException {
        String key = firstKey;
        while (key != null) {
            switch (key) {
                case "op":
                    try {
                        addParsed(result, parseNestedPost(reader, board, domain, threadNo));
                    } catch (Exception e) {
                        Logger.w(TAG, "Skipping nested OP: " + e.getMessage());
                    }
                    break;
                case "posts":
                    reader.beginObject();
                    while (reader.hasNext()) {
                        reader.nextName();
                        try {
                            addParsed(result, parseNestedPost(reader, board, domain, threadNo));
                        } catch (Exception e) {
                            Logger.w(TAG, "Skipping nested post: " + e.getMessage());
                        }
                    }
                    reader.endObject();
                    break;
                default:
                    reader.skipValue();
                    break;
            }
            key = reader.hasNext() ? reader.nextName() : null;
        }
    }

    private static ParsedPost parseNestedPost(JsonReader reader, Board board, String domain, int threadNo) throws IOException {
        Post.Builder builder = new Post.Builder();
        builder.board(board);
        builder.id(threadNo);
        builder.opId(threadNo);
        builder.setUnixTimestampSeconds(0);

        int postNo = -1;
        boolean deleted = false;
        boolean hasMedia = false;
        String mediaOrig = null;
        String mediaFilename = null;
        String mediaW = null;
        String mediaH = null;
        String mediaSize = null;
        String mediaHash = null;
        String thumbLink = null;
        String remoteLink = null;
        String mediaStatus = null;
        boolean spoiler = false;

        reader.beginObject();
        while (reader.hasNext()) {
            String key = reader.nextName();
            try {
                switch (key) {
                    case "num":
                        postNo = readInt(reader);
                        break;
                    case "op":
                        builder.op("1".equals(readNullableString(reader)));
                        break;
                    case "thread_num":
                        int resto = readInt(reader);
                        if (resto > 0) builder.opId(resto);
                        break;
                    case "timestamp":
                        builder.setUnixTimestampSeconds(readLong(reader));
                        break;
                    case "capcode": {
                        String capcode = readNullableString(reader);
                        if (capcode != null && !capcode.isEmpty() && !"N".equals(capcode)) {
                            builder.moderatorCapcode(capcode);
                        }
                        break;
                    }
                    case "name":
                        builder.name(readNullableString(reader));
                        break;
                    case "trip": {
                        String trip = readNullableString(reader);
                        if (trip != null && !trip.isEmpty()) builder.tripcode(trip);
                        break;
                    }
                    case "title": {
                        String title = readNullableString(reader);
                        if (title != null) builder.subject(title);
                        break;
                    }
                    case "comment_processed":
                    case "comment": {
                        String comment = readNullableString(reader);
                        if (comment != null) builder.comment(comment);
                        break;
                    }
                    case "poster_hash": {
                        String hash = readNullableString(reader);
                        if (hash != null && !hash.isEmpty()) builder.posterId(hash);
                        break;
                    }
                    case "sticky":
                        builder.sticky("1".equals(readNullableString(reader)));
                        break;
                    case "locked":
                        builder.closed("1".equals(readNullableString(reader)));
                        break;
                    case "deleted":
                        deleted = "1".equals(readNullableString(reader));
                        break;
                    case "media":
                        if (reader.peek() == JsonToken.NULL) {
                            reader.nextNull();
                        } else {
                            hasMedia = true;
                            reader.beginObject();
                            while (reader.hasNext()) {
                                String mediaKey = reader.nextName();
                                switch (mediaKey) {
                                    case "spoiler":
                                        spoiler = "1".equals(readNullableString(reader));
                                        break;
                                    case "media_orig":
                                        mediaOrig = readNullableString(reader);
                                        break;
                                    case "media_filename":
                                        mediaFilename = readNullableString(reader);
                                        break;
                                    case "media_w":
                                        mediaW = readNullableString(reader);
                                        break;
                                    case "media_h":
                                        mediaH = readNullableString(reader);
                                        break;
                                    case "media_size":
                                        mediaSize = readNullableString(reader);
                                        break;
                                    case "media_hash":
                                        mediaHash = readNullableString(reader);
                                        break;
                                    case "thumb_link":
                                        thumbLink = readNullableString(reader);
                                        break;
                                    case "remote_media_link":
                                    case "media_link":
                                        remoteLink = readNullableString(reader);
                                        break;
                                    case "media_status":
                                        mediaStatus = readNullableString(reader);
                                        break;
                                    default:
                                        reader.skipValue();
                                        break;
                                }
                            }
                            reader.endObject();
                        }
                        break;
                    default:
                        reader.skipValue();
                        break;
                }
            } catch (NumberFormatException | IllegalStateException e) {
                Logger.w(TAG, "Skipping nested field " + key);
            }
        }
        reader.endObject();

        if (postNo < 0) return null;
        builder.id(postNo);

        boolean fileDeleted = hasMedia
                && ("deleted".equals(mediaStatus) || mediaOrig == null || "0".equals(mediaOrig));
        if (hasMedia && !fileDeleted && mediaOrig != null) {
            String extension = "jpg";
            int dot = mediaOrig.lastIndexOf('.');
            if (dot >= 0 && dot < mediaOrig.length() - 1) {
                extension = mediaOrig.substring(dot + 1).toLowerCase(Locale.ROOT);
            }
            String displayName = mediaFilename != null
                    ? org.jsoup.parser.Parser.unescapeEntities(mediaFilename, false)
                    : mediaOrig;

            HttpUrl thumb = parseUrl(thumbLink);
            if (thumb == null) {
                thumb = new HttpUrl.Builder().scheme("https").host(domain)
                        .addPathSegment(board.code).addPathSegment("thumb")
                        .addPathSegment(mediaOrig)
                        .build();
            }
            HttpUrl image = parseUrl(remoteLink);
            if (image == null) {
                image = new HttpUrl.Builder().scheme("https").host(domain)
                        .addPathSegment(board.code).addPathSegment("image")
                        .addPathSegment(mediaOrig)
                        .build();
            }

            int width = parseDim(mediaW);
            int height = parseDim(mediaH);
            long size = parseDim(mediaSize);

            List<PostImage> files = new ArrayList<>(1);
            files.add(new PostImage.Builder()
                    .originalName(mediaOrig)
                    .thumbnailUrl(thumb)
                    .spoilerThumbnailUrl(thumb)
                    .imageUrl(image)
                    .filename(displayName)
                    .extension(extension)
                    .imageWidth(width)
                    .imageHeight(height)
                    .spoiler(spoiler)
                    .size(size)
                    .md5(mediaHash)
                    .build());
            builder.images(files);
        } else {
            builder.images(new ArrayList<>());
        }
        builder.fileDeleted(fileDeleted);

        Post.Builder opSnapshot = null;
        if (builder.op) {
            opSnapshot = new Post.Builder()
                    .closed(builder.closed)
                    .archived(builder.archived)
                    .sticky(builder.sticky)
                    .replies(builder.replies)
                    .images(builder.imagesCount)
                    .uniqueIps(builder.uniqueIps)
                    .lastModified(builder.lastModified);
        }

        return new ParsedPost(builder, opSnapshot, deleted);
    }

    private static HttpUrl parseUrl(String url) {
        if (url == null || url.isEmpty()) return null;
        try {
            HttpUrl parsed = HttpUrl.parse(url);
            if (parsed != null) {
                HttpUrl.Builder https = parsed.newBuilder().scheme("https");
                return https.build();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static int parseDim(String value) {
        if (value == null || value.isEmpty()) return 0;
        try {
            return (int) Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---------- Classic fuuka flat format ----------

    private static ParsedPost parseFlatPost(JsonReader reader, String firstKey, Board board, String domain, int postNo, int threadNo) throws IOException {
        Post.Builder builder = new Post.Builder();
        builder.board(board);
        builder.id(postNo);
        builder.opId(threadNo);
        // Some archives omit time/now; default to 0 so posts still build.
        builder.setUnixTimestampSeconds(0);

        String fileId = null;
        String fileExt = null;
        int fileWidth = 0;
        int fileHeight = 0;
        long fileSize = 0;
        boolean fileSpoiler = false;
        String fileName = null;
        String md5 = null;
        boolean fileDeleted = false;

        String key = firstKey;
        while (key != null) {
            try {
                switch (key) {
                    case "no":
                        builder.id(readInt(reader));
                        break;
                    case "resto":
                        int opId = readInt(reader);
                        builder.op(opId == 0);
                        builder.opId(opId);
                        break;
                    case "time":
                        builder.setUnixTimestampSeconds(readLong(reader));
                        break;
                    case "now":
                        builder.setUnixTimestampSeconds(parseIsoDate(readNullableString(reader)));
                        break;
                    case "name":
                        builder.name(readNullableString(reader));
                        break;
                    case "trip": {
                        String trip = readNullableString(reader);
                        if (trip != null) builder.tripcode(trip);
                        break;
                    }
                    case "id": {
                        String id = readNullableString(reader);
                        if (id != null) builder.posterId(id);
                        break;
                    }
                    case "capcode": {
                        String capcode = readNullableString(reader);
                        if (capcode != null) builder.moderatorCapcode(capcode);
                        break;
                    }
                    case "sub": {
                        String subject = readNullableString(reader);
                        if (subject != null) builder.subject(subject);
                        break;
                    }
                    case "com": {
                        String comment = readNullableString(reader);
                        if (comment != null) builder.comment(comment);
                        break;
                    }
                    case "tim":
                        fileId = readNullableString(reader);
                        break;
                    case "ext":
                        fileExt = readNullableString(reader);
                        if (fileExt != null) fileExt = fileExt.replace(".", "");
                        break;
                    case "w":
                        fileWidth = readInt(reader);
                        break;
                    case "h":
                        fileHeight = readInt(reader);
                        break;
                    case "fsize":
                        fileSize = readLong(reader);
                        break;
                    case "filename":
                        fileName = readNullableString(reader);
                        break;
                    case "filedeleted":
                        fileDeleted = readInt(reader) == 1;
                        break;
                    case "spoiler":
                        fileSpoiler = readInt(reader) == 1;
                        break;
                    case "md5":
                        md5 = readNullableString(reader);
                        break;
                    case "sticky":
                        builder.sticky(readInt(reader) == 1);
                        break;
                    case "locked":
                    case "closed":
                        builder.closed(readInt(reader) == 1);
                        break;
                    case "archived":
                        builder.archived(readInt(reader) == 1);
                        break;
                    case "replies":
                        builder.replies(readInt(reader));
                        break;
                    case "images":
                        builder.images(readInt(reader));
                        break;
                    case "unique_ips":
                        builder.uniqueIps(readInt(reader));
                        break;
                    case "last_modified":
                        builder.lastModified(readLong(reader));
                        break;
                    default:
                        reader.skipValue();
                        break;
                }
            } catch (NumberFormatException | IllegalStateException e) {
                Logger.w(TAG, "Skipping field " + key + " of post " + postNo);
            }
            key = reader.hasNext() ? reader.nextName() : null;
        }

        // fuuka reports tim=0 (or omits it) for posts whose file was deleted.
        boolean hasFile = fileId != null && !fileId.isEmpty() && !"0".equals(fileId)
                && fileName != null && fileExt != null && !fileExt.isEmpty();
        if (hasFile) {
            HttpUrl base = new HttpUrl.Builder()
                    .scheme("https")
                    .host(domain)
                    .build();

            HttpUrl thumb = base.newBuilder()
                    .addPathSegment(board.code)
                    .addPathSegment("thumb")
                    .addPathSegment(fileId + "." + fileExt)
                    .build();
            HttpUrl image = base.newBuilder()
                    .addPathSegment(board.code)
                    .addPathSegment("image")
                    .addPathSegment(fileId + "." + fileExt)
                    .build();
            HttpUrl spoilerThumb = thumb;
            if (fileSpoiler) {
                spoilerThumb = base.newBuilder()
                        .addPathSegment("static")
                        .addPathSegment("spoiler.png")
                        .build();
            }

            String displayName = org.jsoup.parser.Parser.unescapeEntities(fileName, false);
            List<PostImage> files = new ArrayList<>(1);
            files.add(new PostImage.Builder()
                    .originalName(String.valueOf(fileId))
                    .thumbnailUrl(thumb)
                    .spoilerThumbnailUrl(spoilerThumb)
                    .imageUrl(image)
                    .filename(displayName)
                    .extension(fileExt)
                    .imageWidth(fileWidth)
                    .imageHeight(fileHeight)
                    .spoiler(fileSpoiler)
                    .size(fileSize)
                    .md5(md5)
                    .build());
            builder.images(files);
        } else {
            builder.images(new ArrayList<>());
            if (fileId == null || "0".equals(fileId)) {
                fileDeleted = true;
            }
        }
        builder.fileDeleted(fileDeleted);

        Post.Builder opSnapshot = null;
        if (builder.op) {
            opSnapshot = new Post.Builder()
                    .closed(builder.closed)
                    .archived(builder.archived)
                    .sticky(builder.sticky)
                    .replies(builder.replies)
                    .images(builder.imagesCount)
                    .uniqueIps(builder.uniqueIps)
                    .lastModified(builder.lastModified);
        }

        return new ParsedPost(builder, opSnapshot, false);
    }

    // Fuuka instances are inconsistent: numeric fields can arrive as strings.
    private static int readInt(JsonReader reader) throws IOException {
        String value = readNullableString(reader);
        if (value == null) return 0;
        try {
            return (int) Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long readLong(JsonReader reader) throws IOException {
        String value = readNullableString(reader);
        if (value == null) return 0L;
        try {
            return (long) Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String readNullableString(JsonReader reader) throws IOException {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull();
            return null;
        }
        return reader.nextString();
    }

    private static long parseIsoDate(String iso) {
        if (iso == null) return 0L;
        try {
            String truncated = iso.length() > 19 ? iso.substring(0, 19) : iso;
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            return sdf.parse(truncated).getTime() / 1000L;
        } catch (Exception ignored) {
            return 0L;
        }
    }
}
