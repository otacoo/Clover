/*
 * Clover - 4chan browser https://github.com/otacoo/Clover/
 * Copyright (C) 2014  Floens https://github.com/Floens/Clover/
 * Copyright (C) 2026  otacoo
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

import static org.otacoo.chan.Chan.inject;

import org.otacoo.chan.utils.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import javax.inject.Inject;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Vichan applies garbage looking fields to the post form, to combat bots.
 * Load up the normal html, parse the form, and get these fields for our post.
 */
public class VichanAntispam {
    private static final String TAG = "VichanAntispam";
    private static final Pattern DISPLAY_NONE = Pattern.compile("display\\s*:\\s*none", Pattern.CASE_INSENSITIVE);
    private static final Pattern VISIBILITY_HIDDEN = Pattern.compile("visibility\\s*:\\s*hidden", Pattern.CASE_INSENSITIVE);

    public static class PostFormData {
        public final Map<String, String> fields;
        public final String fileFieldName;
        public final String fileInsertAfterField;

        public PostFormData(Map<String, String> fields, String fileFieldName, String fileInsertAfterField) {
            this.fields = fields;
            this.fileFieldName = fileFieldName;
            this.fileInsertAfterField = fileInsertAfterField;
        }
    }

    private HttpUrl url;

    @Inject
    OkHttpClient okHttpClient;

    private List<String> fieldsToIgnore = new ArrayList<>();

    @SuppressWarnings("this-escape")
    public VichanAntispam(HttpUrl url) {
        this.url = url;
        inject(this);
    }

    public void addDefaultIgnoreFields() {
        // We want to extract ALL fields initially to maintain DOM order, 
        // including standard ones like user, thread, q, board.
        // We will override their values in VichanActions.prepare.
        fieldsToIgnore.clear();
    }

    public void ignoreField(String name) {
        fieldsToIgnore.add(name);
    }

    public Map<String, String> get(String comment) {
        return getWithReplyValues(comment, null, null, "", "", "", "");
    }

    public Map<String, String> getWithReplyValues(String comment, String boardValue, String threadValue, 
                                                   String nameValue, String emailValue, String subjectValue, String passwordValue) {
        return getPostFormData(comment, boardValue, threadValue, nameValue, emailValue, subjectValue, passwordValue).fields;
    }

    public PostFormData getPostFormData(String comment, String boardValue, String threadValue,
                                        String nameValue, String emailValue, String subjectValue, String passwordValue) {
        Map<String, String> res = new java.util.LinkedHashMap<>();
        String fileFieldName = "file";
        String fileInsertAfterField = null;

        Request request = new Request.Builder()
                .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Upgrade-Insecure-Requests", "1")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "same-origin")
            .header("Sec-Fetch-User", "?1")
            .header("Referer", url.toString())
                .build();
        
        try (Response response = okHttpClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (body != null) {
                String html = body.string();
                Document document = Jsoup.parse(html);
                Elements forms = document.body().getElementsByTag("form");
                for (int formIdx = 0; formIdx < forms.size(); formIdx++) {
                    Element form = forms.get(formIdx);
                    // Usually the post form has name="post" or no name but contains a textarea.
                    if (form.attr("name").equals("post") || !form.getElementsByTag("textarea").isEmpty()) {
                        Logger.d(TAG, "Found post form, extracting fields in DOM order...");
                        String commentFieldName = null;
                        String lastAddedFieldName = null;
                        
                        // Get ALL inputs and textareas in document order
                        for (Element elem : form.getAllElements()) {
                            if (elem.tagName().equalsIgnoreCase("input")) {
                                String name = elem.attr("name");
                                String value = elem.val();
                                String type = elem.attr("type").toLowerCase(Locale.ENGLISH);

                                // Skip fields with no name and UI controls except submit name=post
                                if (name.isEmpty() || type.equals("reset") || type.equals("button")) {
                                    continue;
                                }

                                if (type.equals("submit") && !name.equals("post")) {
                                    continue;
                                }

                                if (type.equals("file")) {
                                    if (fileFieldName == null || fileFieldName.isEmpty() || fileFieldName.equals("file")) {
                                        fileFieldName = name;
                                    }
                                    if (fileInsertAfterField == null) {
                                        fileInsertAfterField = lastAddedFieldName;
                                    }
                                    continue;
                                }

                                // Unchecked checkboxes and radio buttons shouldn't be submitted
                                if ((type.equals("checkbox") || type.equals("radio")) && !elem.hasAttr("checked")) {
                                    continue;
                                }

                                // Standard fields where we inject Clover values.
                                boolean isStandard = name.equals("board") || name.equals("thread") || name.equals("name") || 
                                                    name.equals("email") || name.equals("subject") || name.equals("message") || 
                                                    name.equals("password") || name.equals("user") || name.equals("q") || 
                                                    name.equals("hash") || name.equals("spoiler") || name.equals("embed") ||
                                                    name.equals("post");

                                if (!res.containsKey(name)) {
                                    if (name.equals("board") && boardValue != null) {
                                        value = boardValue;
                                    } else if (name.equals("thread") && threadValue != null) {
                                        value = threadValue;
                                    } else if (name.equals("name")) {
                                        value = nameValue != null ? nameValue : "";
                                    } else if (name.equals("email")) {
                                        value = emailValue != null ? emailValue : "";
                                    } else if (name.equals("subject")) {
                                        value = subjectValue != null ? subjectValue : "";
                                    } else if (name.equals("password")) {
                                        value = passwordValue != null ? passwordValue : "";
                                    }
                                    res.put(name, value);
                                    lastAddedFieldName = name;
                                }
                            } else if (elem.tagName().equalsIgnoreCase("textarea")) {
                                String name = elem.attr("name");
                                if (!name.isEmpty() && !res.containsKey(name)) {
                                    String value = getTextareaWholeText(elem);
                                    if (!isHiddenElement(elem) && commentFieldName == null) {
                                        commentFieldName = name;
                                        value = comment != null ? comment : "";
                                    }
                                    res.put(name, value);
                                    lastAddedFieldName = name;
                                }
                            }
                        }

                        if (commentFieldName == null) {
                            if (res.containsKey("body")) {
                                res.put("body", comment != null ? comment : "");
                            } else if (res.containsKey("message")) {
                                res.put("message", comment != null ? comment : "");
                            }
                        }
                        break;
                    }
                }
            }
        } catch (IOException e) {
            Logger.e(TAG, "IOException parsing vichan bot fields", e);
        } catch (Exception e) {
            Logger.e(TAG, "Error parsing vichan bot fields", e);
        }

        return new PostFormData(res, fileFieldName, fileInsertAfterField);
    }

    /**
     * Get the raw text content of a textarea element, preserving exact whitespace.
     * This method uses getWholeText() to preserve the exact content. 
     * This is critical for vichan antispam hash validation.
     */
    private String getTextareaWholeText(Element textarea) {
        StringBuilder sb = new StringBuilder();
        for (Node child : textarea.childNodes()) {
            if (child instanceof TextNode) {
                sb.append(((TextNode) child).getWholeText());
            }
        }
        return sb.toString();
    }

    private boolean isHiddenElement(Element element) {
        Element current = element;
        while (current != null) {
            String style = current.attr("style");
            if (!style.isEmpty() && (DISPLAY_NONE.matcher(style).find()
                    || VISIBILITY_HIDDEN.matcher(style).find())) {
                return true;
            }
            current = current.parent();
        }
        return false;
    }
}
