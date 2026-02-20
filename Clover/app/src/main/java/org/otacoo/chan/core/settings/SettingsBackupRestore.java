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
package org.otacoo.chan.core.settings;

import android.content.SharedPreferences;
import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.otacoo.chan.core.database.DatabaseManager;
import org.otacoo.chan.core.model.json.site.SiteConfig;
import org.otacoo.chan.core.model.orm.Board;
import org.otacoo.chan.core.model.orm.Loadable;
import org.otacoo.chan.core.model.orm.Pin;
import org.otacoo.chan.core.model.orm.SavedReply;
import org.otacoo.chan.core.model.orm.SiteModel;
import org.otacoo.chan.core.repository.SiteRepository;
import org.otacoo.chan.core.site.Site;

/**
 * Exports and imports app settings (SharedPreferences), watched threads (pins), and saved replies to/from a JSON file.
 */
public final class SettingsBackupRestore {

    private static final String KEY_VERSION = "_backup_version";
    private static final int BACKUP_VERSION_1 = 1;
    private static final int BACKUP_VERSION_FULL = 2;
    private static final int BACKUP_VERSION_SITES = 3;

    private static final String KEY_PREFERENCES = "preferences";
    private static final String KEY_SITES = "sites";
    private static final String KEY_PINS = "pins";
    private static final String KEY_SAVED_REPLIES = "saved_replies";
    private static final String KEY_COOKIES = "cookies";
    
    /** Stable site identifier (SiteRegistry classId); used so restore works across devices where numeric siteId differs. */
    private static final String KEY_SITE_CLASS_ID = "siteClassId";

    private SettingsBackupRestore() {
    }

    /** Check if a preference key is a site-specific setting that should not be restored */
    private static boolean isSiteSpecificPreference(String key) {
        if (key.equals("preference_captcha_type")) return true;

        // Site specific preferences are preference_N_... where N is the site id.
        // We exclude them from general preferences because site ids are not stable across devices.
        // Site-specific settings are now primarily handled via SiteModel.userSettings in the sites array.
        if (key.startsWith("preference_")) {
            String remainder = key.substring("preference_".length());
            return remainder.length() > 0 && Character.isDigit(remainder.charAt(0));
        }

        return false;
    }

    /** Resolve backup siteClassId to current device's site id. Returns -1 if no site with that class exists. */
    private static int resolveSiteClassIdToCurrentId(DatabaseManager databaseManager, int siteClassId) throws Exception {
        List<SiteModel> all = databaseManager.runTask(databaseManager.getDatabaseSiteManager().getAll());
        for (SiteModel m : all) {
            SiteConfig config = m.loadConfigFields().first;
            if (config.classId == siteClassId) return m.id;
        }
        return -1;
    }

    /** Get current device site id for a backup entry (pin or saved reply). Prefers siteClassId; falls back to legacy siteId if site exists. */
    private static int resolveRestoreSiteId(DatabaseManager databaseManager, JSONObject o) throws Exception {
        if (o.has(KEY_SITE_CLASS_ID)) {
            int currentId = resolveSiteClassIdToCurrentId(databaseManager, o.getInt(KEY_SITE_CLASS_ID));
            if (currentId >= 0) return currentId;
        }
        int legacySiteId = o.optInt("siteId", -1);
        if (legacySiteId < 0) return -1;
        try {
            SiteRepository.forId(legacySiteId);
            return legacySiteId;
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static int getSiteClassId(DatabaseManager databaseManager, int siteId) {
        try {
            SiteModel m = databaseManager.runTask(databaseManager.getDatabaseSiteManager().byId(siteId));
            return m != null ? m.loadConfigFields().first.classId : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Export preferences only (legacy). Use {@link #exportFull} for full backup.
     */
    public static String exportToJson(SharedPreferences prefs) throws Exception {
        JSONObject out = new JSONObject();
        out.put(KEY_VERSION, BACKUP_VERSION_1);
        putPreferencesInto(out, prefs);
        return out.toString(2);
    }

    /**
     * Export preferences, watched threads (pins), and saved replies to a JSON string.
     * Must be called from a thread that can block on database tasks.
     */
    public static String exportFull(DatabaseManager databaseManager, SharedPreferences prefs) throws Exception {
        List<Pin> pins = databaseManager.runTask(databaseManager.getDatabasePinManager().getPins());
        List<SavedReply> savedReplies = databaseManager.runTask(
                databaseManager.getDatabaseSavedReplyManager().getAllForBackup());
        List<SiteModel> sites = databaseManager.runTask(databaseManager.getDatabaseSiteManager().getAll());

        JSONObject out = new JSONObject();
        out.put(KEY_VERSION, BACKUP_VERSION_SITES);

        JSONObject prefsObj = new JSONObject();
        putPreferencesInto(prefsObj, prefs);
        out.put(KEY_PREFERENCES, prefsObj);

        JSONArray sitesArr = new JSONArray();
        for (SiteModel site : sites) {
            JSONObject s = new JSONObject();
            s.put("configuration", site.configuration);
            s.put("userSettings", site.userSettings);
            s.put("order", site.order);

            // Export saved boards for this site
            try {
                Site siteObj = SiteRepository.forId(site.id);
                if (siteObj != null) {
                    List<Board> boards = databaseManager.runTask(databaseManager.getDatabaseBoardManager().getSiteSavedBoards(siteObj));
                    if (boards != null && boards.size() > 0) {
                        JSONArray boardsArr = new JSONArray();
                        for (Board b : boards) {
                            JSONObject bo = new JSONObject();
                            bo.put("code", b.code);
                            bo.put("name", b.name);
                            bo.put("saved", b.saved);
                            bo.put("order", b.order);
                            bo.put("workSafe", b.workSafe);
                            boardsArr.put(bo);
                        }
                        s.put("boards", boardsArr);
                    }
                }
            } catch (Exception e) {
                // Site not in repository or DB error, skip boards for this site
            }

            sitesArr.put(s);
        }
        out.put(KEY_SITES, sitesArr);

        putCookiesInto(out);

        JSONArray pinsArr = new JSONArray();
        for (Pin pin : pins) {
            if (pin.loadable == null || !pin.loadable.isThreadMode()) continue;
            JSONObject o = new JSONObject();
            int classId = getSiteClassId(databaseManager, pin.loadable.siteId);
            if (classId >= 0) o.put(KEY_SITE_CLASS_ID, classId);
            o.put("siteId", pin.loadable.siteId);
            o.put("boardCode", pin.loadable.boardCode);
            o.put("mode", pin.loadable.mode);
            o.put("no", pin.loadable.no);
            o.put("title", pin.loadable.title != null ? pin.loadable.title : "");
            o.put("watching", pin.watching);
            o.put("watchLastCount", pin.watchLastCount);
            o.put("watchNewCount", pin.watchNewCount);
            o.put("quoteLastCount", pin.quoteLastCount);
            o.put("quoteNewCount", pin.quoteNewCount);
            o.put("isError", pin.isError);
            o.put("thumbnailUrl", pin.thumbnailUrl != null ? pin.thumbnailUrl : "");
            o.put("order", pin.order);
            o.put("archived", pin.archived);
            pinsArr.put(o);
        }
        out.put(KEY_PINS, pinsArr);

        JSONArray repliesArr = new JSONArray();
        for (SavedReply r : savedReplies) {
            JSONObject o = new JSONObject();
            int classId = getSiteClassId(databaseManager, r.siteId);
            if (classId >= 0) o.put(KEY_SITE_CLASS_ID, classId);
            o.put("siteId", r.siteId);
            o.put("board", r.board);
            o.put("no", r.no);
            o.put("password", r.password != null ? r.password : "");
            repliesArr.put(o);
        }
        out.put(KEY_SAVED_REPLIES, repliesArr);

        return out.toString(2);
    }

    private static void putCookiesInto(JSONObject out) throws Exception {
        CookieManager cookieManager = CookieManager.getInstance();
        JSONObject cookiesObj = new JSONObject();
        String[] domains = {
                "https://www.4chan.org",
                "https://boards.4chan.org",
                "https://sys.4chan.org",
                "https://8chan.moe",
                "https://8chan.st"
        };
        for (String domain : domains) {
            String c = cookieManager.getCookie(domain);
            if (c != null) {
                cookiesObj.put(domain, c);
            }
        }
        out.put(KEY_COOKIES, cookiesObj);
    }

    private static void putPreferencesInto(JSONObject out, SharedPreferences prefs) throws Exception {
        Map<String, ?> all = prefs.getAll();
        for (Map.Entry<String, ?> e : all.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (value == null) continue;
            if (value instanceof Set) {
                @SuppressWarnings("unchecked")
                Set<String> set = (Set<String>) value;
                out.put(key, new JSONArray(set));
            } else {
                out.put(key, value);
            }
        }
    }

    /**
     * Import preferences only. Merges with existing; reload proxy after. Use {@link #importFull} for full restore.
     */
    public static void importFromJson(SharedPreferences prefs, String json) throws Exception {
        JSONObject obj = new JSONObject(json);
        int version = obj.optInt(KEY_VERSION, BACKUP_VERSION_1);
        if (version >= BACKUP_VERSION_FULL && obj.has(KEY_PREFERENCES)) {
            applyPreferences(prefs, obj.getJSONObject(KEY_PREFERENCES));
        } else {
            applyPreferencesFromRoot(prefs, obj);
        }
    }

    /**
     * Import preferences, saved replies, and pins from a full backup JSON.
     * Must be called from a thread that can block on database tasks.
     */
    public static void importFull(DatabaseManager databaseManager, SharedPreferences prefs, String json) throws Exception {
        JSONObject obj = new JSONObject(json);
        int version = obj.optInt(KEY_VERSION, BACKUP_VERSION_1);

        if (version >= BACKUP_VERSION_FULL) {
            if (obj.has(KEY_PREFERENCES)) {
                applyPreferences(prefs, obj.getJSONObject(KEY_PREFERENCES));
            }
            if (obj.has(KEY_SITES)) {
                JSONArray arr = obj.getJSONArray(KEY_SITES);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    SiteModel site = new SiteModel();
                    site.configuration = o.getString("configuration");
                    site.userSettings = o.getString("userSettings");
                    site.order = o.optInt("order", 0);

                    // Check if site already exists by classId
                    int classId = site.loadConfigFields().first.classId;
                    if (resolveSiteClassIdToCurrentId(databaseManager, classId) < 0) {
                        databaseManager.runTask(databaseManager.getDatabaseSiteManager().add(site));
                    }
                }
                SiteRepository.refresh();

                // Restore boards for each site
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    if (o.has("boards")) {
                        SiteModel temp = new SiteModel();
                        temp.configuration = o.getString("configuration");
                        temp.userSettings = o.optString("userSettings", "{\"settings\":{}}");
                        int classId = temp.loadConfigFields().first.classId;
                        int currentId = resolveSiteClassIdToCurrentId(databaseManager, classId);
                        if (currentId >= 0) {
                            Site siteObj = SiteRepository.forId(currentId);
                            JSONArray boards = o.getJSONArray("boards");
                            for (int j = 0; j < boards.length(); j++) {
                                JSONObject bo = boards.getJSONObject(j);
                                String code = bo.getString("code");
                                // Use the site's createBoard/board logic to get/create the board object.
                                // createBoard(name, code)
                                String name = bo.optString("name", code);
                                Board board = siteObj.board(code);
                                if (board == null) {
                                    board = siteObj.createBoard(name, code);
                                }
                                board.saved = bo.optBoolean("saved", true);
                                board.order = bo.optInt("order", 0);
                                board.workSafe = bo.optBoolean("workSafe", false);

                                // Save the user-set fields to database.
                                databaseManager.runTask(databaseManager.getDatabaseBoardManager().updateIncludingUserFields(board));
                            }
                        }
                    }
                }
            }
            if (obj.has(KEY_COOKIES)) {
                applyCookies(obj.getJSONObject(KEY_COOKIES));
            }
            if (obj.has(KEY_SAVED_REPLIES)) {
                JSONArray arr = obj.getJSONArray(KEY_SAVED_REPLIES);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    int siteId = resolveRestoreSiteId(databaseManager, o);
                    if (siteId < 0) continue;
                    SavedReply r = new SavedReply();
                    r.siteId = siteId;
                    r.board = o.getString("board");
                    r.no = o.getInt("no");
                    r.password = o.optString("password", "");
                    databaseManager.runTask(databaseManager.getDatabaseSavedReplyManager().saveReply(r));
                }
            }
            if (obj.has(KEY_PINS)) {
                JSONArray arr = obj.getJSONArray(KEY_PINS);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    int siteId = resolveRestoreSiteId(databaseManager, o);
                    if (siteId < 0) continue;
                    String boardCode = o.getString("boardCode");
                    int no = o.getInt("no");
                    String title = o.optString("title", "");
                    Site site = SiteRepository.forId(siteId);
                    Board board = site.board(boardCode);
                    if (board == null) {
                        board = site.createBoard(boardCode, boardCode);
                    }
                    Loadable loadable = Loadable.forThread(site, board, no, title);
                    loadable = databaseManager.runTask(
                            databaseManager.getDatabaseLoadableManager().getOrCreate(loadable));
                    Pin pin = new Pin();
                    pin.loadable = loadable;
                    pin.watching = o.optBoolean("watching", true);
                    pin.watchLastCount = o.optInt("watchLastCount", -1);
                    pin.watchNewCount = o.optInt("watchNewCount", -1);
                    pin.quoteLastCount = o.optInt("quoteLastCount", -1);
                    pin.quoteNewCount = o.optInt("quoteNewCount", -1);
                    pin.isError = o.optBoolean("isError", false);
                    pin.thumbnailUrl = o.optString("thumbnailUrl", "");
                    if (pin.thumbnailUrl.isEmpty()) pin.thumbnailUrl = null;
                    pin.order = o.optInt("order", -1);
                    pin.archived = o.optBoolean("archived", false);
                    databaseManager.runTask(databaseManager.getDatabasePinManager().createPin(pin));
                }
            }
        } else {
            applyPreferencesFromRoot(prefs, obj);
        }
    }

    private static void applyPreferences(SharedPreferences prefs, JSONObject obj) throws Exception {
        SharedPreferences.Editor ed = prefs.edit();
        Iterator<String> keyIt = obj.keys();
        while (keyIt.hasNext()) {
            String key = keyIt.next();
            if (!isSiteSpecificPreference(key)) {
                applyPrefEntry(ed, key, obj.opt(key));
            }
        }
        if (!ed.commit()) {
            throw new Exception("Failed to write preferences to storage");
        }
    }

    private static void applyPreferencesFromRoot(SharedPreferences prefs, JSONObject obj) throws Exception {
        SharedPreferences.Editor ed = prefs.edit();
        Iterator<String> keyIt = obj.keys();
        while (keyIt.hasNext()) {
            String key = keyIt.next();
            if (KEY_VERSION.equals(key)) continue;
            if (!isSiteSpecificPreference(key)) {
                applyPrefEntry(ed, key, obj.opt(key));
            }
        }
        if (!ed.commit()) {
            throw new Exception("Failed to write preferences to storage");
        }
    }

    private static void applyPrefEntry(SharedPreferences.Editor ed, String key, Object value) throws Exception {
        if (value == null || value == JSONObject.NULL) return;
        if (value instanceof Boolean) {
            ed.putBoolean(key, (Boolean) value);
        } else if (value instanceof Number) {
            long l = ((Number) value).longValue();
            if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                ed.putInt(key, (int) l);
            } else {
                ed.putLong(key, l);
            }
        } else if (value instanceof String) {
            ed.putString(key, (String) value);
        } else if (value instanceof JSONArray) {
            JSONArray arr = (JSONArray) value;
            Set<String> set = new HashSet<>();
            for (int i = 0; i < arr.length(); i++) {
                set.add(arr.getString(i));
            }
            ed.putStringSet(key, set);
        }
    }

    private static void applyCookies(JSONObject obj) throws Exception {
        CookieManager cookieManager = CookieManager.getInstance();
        Iterator<String> domains = obj.keys();
        while (domains.hasNext()) {
            String domain = domains.next();
            String cookies = obj.getString(domain);
            String[] parts = cookies.split(";");
            for (String part : parts) {
                cookieManager.setCookie(domain, part.trim());
            }
        }
        cookieManager.flush();
    }
}
