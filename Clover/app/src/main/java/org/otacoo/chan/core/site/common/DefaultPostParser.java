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
package org.otacoo.chan.core.site.common;


import static org.otacoo.chan.utils.AndroidUtils.sp;
import static org.otacoo.chan.utils.AndroidUtils.getString;

import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.UnderlineSpan;

import androidx.annotation.AnyThread;

import org.otacoo.chan.R;
import org.otacoo.chan.core.model.Post;
import org.otacoo.chan.core.model.PostImage;
import org.otacoo.chan.core.settings.ChanSettings;
import org.otacoo.chan.core.site.parser.CommentParser;
import org.otacoo.chan.core.site.parser.CommentParserHelper;
import org.otacoo.chan.core.site.parser.PostParser;
import org.otacoo.chan.ui.span.AbsoluteSizeSpanHashed;
import org.otacoo.chan.ui.span.ForegroundColorSpanHashed;
import org.otacoo.chan.ui.theme.Theme;
import org.otacoo.chan.ui.theme.ThemeHelper;
import org.otacoo.chan.utils.AndroidUtils;
import org.otacoo.chan.utils.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;

import java.util.List;

@AnyThread
public class DefaultPostParser implements PostParser {
    private static final String TAG = "DefaultPostParser";

    private CommentParser commentParser;

    public DefaultPostParser(CommentParser commentParser) {
        this.commentParser = commentParser;
    }

    @Override
    public Post parse(Theme theme, Post.Builder builder, Callback callback) {
        if (theme == null) {
            theme = ThemeHelper.getInstance().getTheme();
        }

        if (!TextUtils.isEmpty(builder.name)) {
            builder.name = Parser.unescapeEntities(builder.name, false);
        }

        if (!TextUtils.isEmpty(builder.subject)) {
            builder.subject = Parser.unescapeEntities(builder.subject, false);
        }

        parseSpans(theme, builder);

        if (builder.comment != null) {
            builder.comment = parseComment(theme, builder, builder.comment, callback);
        } else {
            builder.comment = "";
        }

        return builder.build();
    }

    /**
     * Parse the comment, subject, tripcodes, names etc. as spannables.<br>
     * This is done on a background thread for performance, even when it is UI code.<br>
     * The results will be placed on the Post.*Span members.
     *
     * @param theme   Theme to use for parsing
     * @param builder Post builder to get data from
     */
    private void parseSpans(Theme theme, Post.Builder builder) {
        boolean anonymize = ChanSettings.anonymize.get();
        boolean anonymizeIds = ChanSettings.anonymizeIds.get();

        final String defaultName = "Anonymous";
        if (anonymize) {
            builder.name(defaultName);
            builder.tripcode("");
        }

        if (anonymizeIds) {
            builder.posterId("");
        }

        SpannableString subjectSpan = null;
        SpannableString nameSpan = null;
        SpannableString tripcodeSpan = null;
        SpannableString idSpan = null;
        SpannableString capcodeSpan = null;

        int detailsSizePx = sp(Integer.parseInt(ChanSettings.fontSize.get()) - 4);

        if (!TextUtils.isEmpty(builder.subject)) {
            // 8chan: a subject of exactly "s" marks a spoiler post.
            // Display it as a bold red "SPOILER" label instead of the bare letter.
            boolean is8chan = builder.board != null && builder.board.site instanceof org.otacoo.chan.core.site.sites.chan8.Chan8;
            boolean isSpoilerSubject = is8chan && builder.subject.trim().equalsIgnoreCase("s");
            String displaySubject = isSpoilerSubject ? "SPOILER" : builder.subject;
            subjectSpan = new SpannableString(displaySubject);
            if (!builder.filterStub) {
                int subjectColor = isSpoilerSubject ? 0xffcc0000 : theme.subjectColor;
                subjectSpan.setSpan(new ForegroundColorSpanHashed(subjectColor), 0, subjectSpan.length(), 0);
                if (isSpoilerSubject) {
                    subjectSpan.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, subjectSpan.length(), 0);
                }
            }
        }

        if (!TextUtils.isEmpty(builder.name) && (!builder.name.equals(defaultName) || ChanSettings.showAnonymousName.get())) {
            nameSpan = new SpannableString(builder.name);
            nameSpan.setSpan(new ForegroundColorSpanHashed(theme.nameColor), 0, nameSpan.length(), 0);
        }

        if (!TextUtils.isEmpty(builder.tripcode)) {
            tripcodeSpan = new SpannableString(builder.tripcode);
            tripcodeSpan.setSpan(new ForegroundColorSpanHashed(theme.nameColor), 0, tripcodeSpan.length(), 0);
            tripcodeSpan.setSpan(new AbsoluteSizeSpanHashed(detailsSizePx), 0, tripcodeSpan.length(), 0);
        }

        if (!TextUtils.isEmpty(builder.posterId)) {
            idSpan = new SpannableString("  ID: " + builder.posterId + "  ");

            // Stolen from the 4chan extension
            int hash = builder.posterId.hashCode();

            int r = (hash >> 24) & 0xff;
            int g = (hash >> 16) & 0xff;
            int b = (hash >> 8) & 0xff;

            //noinspection NumericOverflow
            int idColor = (0xff << 24) + (r << 16) + (g << 8) + b;
            boolean lightColor = (r * 0.299f) + (g * 0.587f) + (b * 0.114f) > 125f;
            int idBgColor = lightColor ? theme.idBackgroundLight : theme.idBackgroundDark;

            idSpan.setSpan(new ForegroundColorSpanHashed(idColor), 0, idSpan.length(), 0);
            idSpan.setSpan(new BackgroundColorSpan(idBgColor), 0, idSpan.length(), 0);
            idSpan.setSpan(new AbsoluteSizeSpanHashed(detailsSizePx), 0, idSpan.length(), 0);
        }

        if (!TextUtils.isEmpty(builder.moderatorCapcode)) {
            capcodeSpan = new SpannableString("Capcode: " + builder.moderatorCapcode);
            capcodeSpan.setSpan(new ForegroundColorSpanHashed(theme.capcodeColor), 0, capcodeSpan.length(), 0);
            capcodeSpan.setSpan(new AbsoluteSizeSpanHashed(detailsSizePx), 0, capcodeSpan.length(), 0);
        }

        // Append (You) to the name when this is one of the user's own posts.
        if (builder.isSavedReply) {
            String youLabel = (nameSpan != null ? nameSpan.toString().trim() : "Anonymous") + CommentParser.SAVED_REPLY_SUFFIX;
            nameSpan = new SpannableString(youLabel);
            nameSpan.setSpan(new ForegroundColorSpanHashed(theme.nameColor), 0, nameSpan.length(), 0);
        }

        CharSequence nameTripcodeIdCapcodeSpan = new SpannableString("");
        if (nameSpan != null) {
            nameTripcodeIdCapcodeSpan = TextUtils.concat(nameTripcodeIdCapcodeSpan, nameSpan, " ");
        }

        if (tripcodeSpan != null) {
            nameTripcodeIdCapcodeSpan = TextUtils.concat(nameTripcodeIdCapcodeSpan, tripcodeSpan, " ");
        }

        if (idSpan != null) {
            nameTripcodeIdCapcodeSpan = TextUtils.concat(nameTripcodeIdCapcodeSpan, idSpan, " ");
        }

        if (capcodeSpan != null) {
            nameTripcodeIdCapcodeSpan = TextUtils.concat(nameTripcodeIdCapcodeSpan, capcodeSpan, " ");
        }

        builder.spans(subjectSpan, nameTripcodeIdCapcodeSpan);

        // Pre-build per-image file info spans to avoid allocating them on the main thread per bind.
        if (builder.images != null && !builder.images.isEmpty()) {
            int count = builder.images.size();
            CharSequence[] fileNames = new CharSequence[count];
            CharSequence[] fileInfos = new CharSequence[count];
            for (int i = 0; i < count; i++) {
                PostImage image = builder.images.get(i);

                String filename = image.spoiler
                        ? getString(R.string.image_spoiler_filename)
                        : image.filename + "." + image.extension;
                SpannableString fn = new SpannableString("\n" + filename);
                fn.setSpan(new ForegroundColorSpanHashed(theme.detailsColor), 0, fn.length(), 0);
                fn.setSpan(new AbsoluteSizeSpanHashed(detailsSizePx), 0, fn.length(), 0);
                fn.setSpan(new UnderlineSpan(), 0, fn.length(), 0);
                fileNames[i] = fn;

                String info = "\n" + image.extension.toUpperCase() + " "
                        + AndroidUtils.getReadableFileSize(image.size) + " "
                        + image.imageWidth + "x" + image.imageHeight;
                SpannableString fi = new SpannableString(info);
                fi.setSpan(new ForegroundColorSpanHashed(theme.detailsColor), 0, fi.length(), 0);
                fi.setSpan(new AbsoluteSizeSpanHashed(detailsSizePx), 0, fi.length(), 0);
                fileInfos[i] = fi;
            }
            builder.fileNameSpans = fileNames;
            builder.fileInfoSpans = fileInfos;
        }
    }

    private CharSequence parseComment(Theme theme, Post.Builder post, CharSequence commentRaw, Callback callback) {
        SpannableStringBuilder total = new SpannableStringBuilder();

        try {
            String comment = commentRaw.toString().replace("<wbr>", "");

            Document document = Jsoup.parseBodyFragment(comment);

            for (Node node : document.body().childNodes()) {
                CharSequence nodeParsed = parseNode(theme, post, callback, node);
                if (nodeParsed != null) {
                    total.append(nodeParsed);
                }
            }
        } catch (Exception e) {
            Logger.e(TAG, "Error parsing comment html", e);
        }

        return total;
    }

    private CharSequence parseNode(Theme theme, Post.Builder post, Callback callback, Node node) {
        if (node instanceof TextNode) {
            String text = ((TextNode) node).text();
            SpannableString spannable = new SpannableString(text);

            return CommentParserHelper.detectLinks(theme, post, text, spannable);
        } else if (node instanceof Element) {
            String nodeName = node.nodeName();

            // Recursively call parseNode with the nodes of the paragraph.
            SpannableStringBuilder innerBuilder = new SpannableStringBuilder();
            for (Node innerNode : node.childNodes()) {
                CharSequence nodeParsed = parseNode(theme, post, callback, innerNode);
                if (nodeParsed != null) {
                    innerBuilder.append(nodeParsed);
                }
            }

            CharSequence result = commentParser.handleTag(
                    callback,
                    theme,
                    post,
                    nodeName,
                    innerBuilder,
                    (Element) node);
            return result != null ? result : innerBuilder;
        } else {
            return ""; // ?
        }
    }
}
