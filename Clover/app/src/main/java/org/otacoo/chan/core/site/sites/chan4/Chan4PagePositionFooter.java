package org.otacoo.chan.core.site.sites.chan4;

import android.util.Pair;

import androidx.annotation.NonNull;

import org.otacoo.chan.Chan;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Hashtable;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class Chan4PagePositionFooter {

    private static Hashtable<String, Pair<Hashtable<Integer, String>, Long>> cache = null;

    public static String getPage(String board, int thread) {
        try {
            if (cache == null) {
                cache = new Hashtable<String, Pair<Hashtable<Integer, String>, Long>>();
            }
            Pair<Hashtable<Integer, String>, Long> stored = cache.get(board);
            if (stored != null && stored.second + 30000 > System.currentTimeMillis()) {
                if (stored.first != null && stored.first.containsKey(thread)) {
                    return "\n[page " + String.valueOf(stored.first.get(thread)) + "]";
                }
            }

            // if we couldn't return a page position, reload the cache
            OkHttpClient client = Chan.getInstance().injector().instance(OkHttpClient.class);
            Request request = new Request.Builder()
                    .url("https://a.4cdn.org/" + board + "/threads.json")
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    if (!response.isSuccessful() || response.body() == null) return;
                    try {
                        String body = response.body().string();
                        JSONArray jsonArray = new JSONArray(body);
                        long now = System.currentTimeMillis();
                        if (cache.get(board) != null && cache.get(board).second + 30000 > now) {
                            return;
                        }
                        cache.put(board, new Pair<Hashtable<Integer, String>, Long>(null, now));
                        Hashtable<Integer, String> pages = new Hashtable<Integer, String>();
                        for (int i = 0; i < jsonArray.length(); i++) {
                            JSONObject jsonObject = jsonArray.getJSONObject(i);
                            int page = jsonObject.getInt("page");
                            JSONArray threads = jsonObject.getJSONArray("threads");
                            for (int j = 0; j < threads.length(); j++) {
                                pages.put(threads.getJSONObject(j).getInt("no"), page + " / post " + (j + 1) + "/" + threads.length());
                            }
                        }
                        cache.put(board, new Pair<Hashtable<Integer, String>, Long>(pages, now));
                    } catch (Exception ignored) {
                    } finally {
                        response.close();
                    }
                }
            });
        } catch (Exception ignored) {  }
        return "\n[page ? / post ?]";
    }

}
