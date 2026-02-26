/*
 * Kuroba - *chan browser https://github.com/Adamantcheese/Kuroba/
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
package org.otacoo.chan.core.manager;

import static org.otacoo.chan.utils.AndroidUtils.getAppContext;

import android.annotation.SuppressLint;
import android.util.Pair;

import androidx.annotation.NonNull;

import org.otacoo.chan.Chan;
import org.otacoo.chan.core.model.orm.Board;
import org.otacoo.chan.core.site.sites.chan4.Chan4;
import org.otacoo.chan.utils.IOUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class ArchivesManager {
    private JSONArray archivesList;

    @SuppressLint("StaticFieldLeak")
    private static ArchivesManager instance;

    public static ArchivesManager getInstance() {
        if (instance == null) {
            instance = new ArchivesManager();
        }
        return instance;
    }

    private ArchivesManager() {
        final File cachedJson = new File(getAppContext().getCacheDir(), "archives.json");

        if (cachedJson.exists()) {
            // used the cached json
            try {
                FileInputStream cachedJsonFIS = new FileInputStream(cachedJson);
                archivesList = new JSONArray(IOUtils.readString(cachedJsonFIS));
                cachedJsonFIS.close();
            } catch (Exception exception) { }
        } else {
            // setup the archives list from the internal file, populated when you build the application
            try {
                archivesList = new JSONArray(IOUtils.assetAsString(getAppContext(), "archives.json"));
            } catch (Exception ignored) {
            }
        }

        // fresh copy request, in case of updates (infrequent)
        OkHttpClient client = Chan.injector().instance(OkHttpClient.class);
        Request request = new Request.Builder()
                .url("https://4chenz.github.io/archives.json/archives.json")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (!response.isSuccessful()) return;
                try {
                    String body = response.body().string();
                    archivesList = new JSONArray(body);
                    // caching response (it'll be used next time)
                    try {
                        ByteArrayInputStream byteArray = new ByteArrayInputStream(body.getBytes("UTF-8"));
                        FileOutputStream cachedJsonFOS = new FileOutputStream(cachedJson);
                        IOUtils.copy(byteArray, cachedJsonFOS);
                        cachedJsonFOS.close();
                        byteArray.close();
                    } catch (Exception exception) { }
                } catch (JSONException ignored) {
                }
            }
        });
    }

    public List<Pair<String, String>> archivesForBoard(Board b) {
        if (archivesList == null || !(b.site instanceof Chan4))
            return new ArrayList<>(); // 4chan only
        else
            return archivesFor4ChanBoard(b.code);
    }

    public List<Pair<String, String>> archivesFor4ChanBoard(String code) {
        List<Pair<String, String>> result = new ArrayList<>();
        if (archivesList == null) return result;
        try {
            for (int i = 0; i < archivesList.length(); i++) {
                JSONObject a = archivesList.getJSONObject(i);
                JSONArray boardCodes = a.getJSONArray("boards");
                for (int j = 0; j < boardCodes.length(); j++) {
                    if (boardCodes.getString(j).equals(code)) {
                        result.add(new Pair<String,String>(a.getString("name"), a.getString("domain")) {
                            @Override
                            public String toString() {
                                return first;
                            }
                        });
                        break;
                    }
                }
            }
        } catch (JSONException ignored) { }
        return result;
    }
}
