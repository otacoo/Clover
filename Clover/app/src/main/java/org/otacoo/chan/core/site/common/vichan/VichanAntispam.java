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
package org.otacoo.chan.core.site.common.vichan;

import static org.otacoo.chan.Chan.inject;

import org.otacoo.chan.utils.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.inject.Inject;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Vichan applies garbage looking fields to the post form, to combat bots.
 * Load up the normal html, parse the form, and get these fields for our post.
 * <p>
 * {@link #get()} blocks, run it off the main thread.
 */
public class VichanAntispam {
    private static final String TAG = "VichanAntispam";
    private HttpUrl url;

    @Inject
    OkHttpClient okHttpClient;

    private List<String> fieldsToIgnore = new ArrayList<>();

    public VichanAntispam(HttpUrl url) {
        this.url = url;
        inject(this);
    }

    public void addDefaultIgnoreFields() {
        fieldsToIgnore.addAll(Arrays.asList("board", "thread", "name", "email",
                "subject", "body", "password", "file", "spoiler", "json_response",
                "file_url1", "file_url2", "file_url3", "post", "com"));
    }

    public void ignoreField(String name) {
        fieldsToIgnore.add(name);
    }

    public Map<String, String> get() {
        Map<String, String> res = new HashMap<>();

        Request request = new Request.Builder()
                .url(url)
                .build();
        
        try (Response response = okHttpClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (body != null) {
                Document document = Jsoup.parse(body.string());
                Elements forms = document.body().getElementsByTag("form");
                for (Element form : forms) {
                    // Usually the post form has name="post" or no name but contains a textarea.
                    if (form.attr("name").equals("post") || !form.getElementsByTag("textarea").isEmpty()) {
                        Elements inputs = form.getElementsByTag("input");
                        Elements textareas = form.getElementsByTag("textarea");

                        for (Element input : inputs) {
                            String name = input.attr("name");
                            String value = input.val();
                            String type = input.attr("type").toLowerCase(Locale.ENGLISH);

                            if (name.isEmpty() || fieldsToIgnore.contains(name) || type.equals("file") || type.equals("submit")) {
                                continue;
                            }
                            
                            res.put(name, value);
                        }
                        
                        // We generally ignore renamed textareas because we send the comment as "body".
                        // Sending an empty renamed textarea might override the "body" parameter on the server.
                        for (Element textarea : textareas) {
                            String name = textarea.attr("name");
                            if (!name.isEmpty() && !fieldsToIgnore.contains(name)) {
                                Logger.d(TAG, "Ignoring likely renamed comment field: " + name);
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

        if (!res.isEmpty()) {
            Logger.i(TAG, "Found antispam fields: " + res.keySet());
        }

        return res;
    }
}
