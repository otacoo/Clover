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

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.telephony.TelephonyManager;

import org.json.JSONObject;
import org.otacoo.chan.utils.AndroidUtils;
import org.otacoo.chan.utils.Logger;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Keeps a 4chan_pass (email verification) token per connection method. The
 * token 4chan issues is bound to the fingerprint of the connection it was
 * verified on (Wi-Fi, mobile data, etc.), so switching connections can make
 * the token stop matching. Nothing is ever deleted here: every token is
 * archived under the network it was issued on and reused when the user is
 * back on that network.
 */
public class Chan4NetworkProfiles {
    private static final String TAG = "Chan4NetworkProfiles";
    private static final String PREFS_KEY = "preference_4chan_pass_network_profiles";

    private Chan4NetworkProfiles() {
    }

    // Identifies the current connection: "wifi", "mobile:<operator>", "vpn",
    // "ethernet" or "other". No permissions required.
    public static String getCurrentNetworkKey() {
        try {
            Context context = AndroidUtils.getAppContext();
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "other";
            Network network = cm.getActiveNetwork();
            if (network == null) return "other";
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps == null) return "other";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return "vpn";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "wifi";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                String operator = getOperatorName(context);
                return operator.isEmpty() ? "mobile" : "mobile:" + operator;
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "ethernet";
            return "other";
        } catch (Exception e) {
            Logger.e(TAG, "getCurrentNetworkKey failed", e);
            return "other";
        }
    }

    private static String getOperatorName(Context context) {
        try {
            TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return "";
            String name = tm.getNetworkOperatorName();
            if (name == null || name.isEmpty()) {
                name = tm.getSimOperatorName();
            }
            return name == null ? "" : name.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static Map<String, String> loadProfiles() {
        Map<String, String> map = new HashMap<>();
        try {
            String json = AndroidUtils.getPreferences().getString(PREFS_KEY, "{}");
            JSONObject obj = new JSONObject(json);
            for (Iterator<String> it = obj.keys(); it.hasNext(); ) {
                String key = it.next();
                map.put(key, obj.optString(key, ""));
            }
        } catch (Exception e) {
            Logger.e(TAG, "loadProfiles failed", e);
        }
        return map;
    }

    private static void saveProfiles(Map<String, String> map) {
        try {
            JSONObject obj = new JSONObject();
            for (Map.Entry<String, String> entry : map.entrySet()) {
                obj.put(entry.getKey(), entry.getValue());
            }
            AndroidUtils.getPreferences().edit().putString(PREFS_KEY, obj.toString()).apply();
        } catch (Exception e) {
            Logger.e(TAG, "saveProfiles failed", e);
        }
    }

    public static String getPassForNetwork(String networkKey) {
        return loadProfiles().get(networkKey);
    }

    public static void setPassForNetwork(String networkKey, String value) {
        Map<String, String> map = loadProfiles();
        map.put(networkKey, value);
        saveProfiles(map);
    }

    public static void removePassForNetwork(String networkKey) {
        Map<String, String> map = loadProfiles();
        if (map.remove(networkKey) != null) {
            saveProfiles(map);
        }
    }

    public static String getPassForCurrentNetwork() {
        String pass = getPassForNetwork(getCurrentNetworkKey());
        return pass != null ? pass : "";
    }

    public static boolean hasPassForCurrentNetwork() {
        return !getPassForCurrentNetwork().isEmpty();
    }
}
