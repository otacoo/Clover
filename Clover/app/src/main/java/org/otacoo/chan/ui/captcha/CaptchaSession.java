/*
 * Clover - 4chan browser - https://github.com/Floens/Clover/
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
package org.otacoo.chan.ui.captcha;

import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The captcha state that is shared between the captcha layouts: the payloads
 * of the challenges, the post cooldowns, the expiries, the tickets and the
 * answers the user already gave for a challenge.
 */
public final class CaptchaSession {

    static final String LAST_REQUEST_KEY = "last_captcha_request_time";

    private static final Map<String, Long> cooldowns = new ConcurrentHashMap<>();
    private static final Map<String, Long> expiries = new ConcurrentHashMap<>();
    private static final Map<String, String> payloads = new ConcurrentHashMap<>();
    private static final Map<String, String> tickets = new ConcurrentHashMap<>();
    private static final Map<String, String> answersByChallenge = new ConcurrentHashMap<>();

    private CaptchaSession() {
    }

    /** Identifies the cooldown bucket of a board or thread. */
    public static String key(String siteName, String boardCode, int threadNo) {
        return siteName + "_" + boardCode + "_" + threadNo;
    }

    /** The challenge of a payload, empty when the payload has none. */
    public static String challengeOf(String payload) {
        if (payload == null) return "";
        try {
            JSONObject root = new JSONObject(payload);
            JSONObject inner = root.optJSONObject("twister");
            return (inner != null ? inner : root).optString("challenge", "");
        } catch (Exception e) {
            return "";
        }
    }

    public static int cooldownRemaining(String key) {
        return secondsLeft(cooldowns.get(key));
    }

    /** Seconds until a new captcha may be requested. */
    public static int requestCooldownRemaining() {
        Long lastRequest = cooldowns.get(LAST_REQUEST_KEY);
        if (lastRequest == null) return 0;
        return secondsLeft(lastRequest + 30000L);
    }

    public static int expiryRemaining(String key) {
        return secondsLeft(expiries.get(key));
    }

    private static int secondsLeft(Long endTime) {
        if (endTime == null) return 0;
        long diff = endTime - System.currentTimeMillis();
        if (diff <= 0) return 0;
        return (int) ((diff + 999) / 1000);
    }

    public static void setCooldown(String key, long endTime) {
        cooldowns.put(key, endTime);
    }

    public static Long cooldownEnd(String key) {
        return cooldowns.get(key);
    }

    public static void setCooldown(String key, int seconds) {
        setCooldown(key, System.currentTimeMillis() + (seconds * 1000L));
    }

    public static void removeCooldown(String key) {
        cooldowns.remove(key);
    }

    public static void setExpiry(String key, long endTime) {
        expiries.put(key, endTime);
    }

    public static Long expiryEnd(String key) {
        return expiries.get(key);
    }

    public static void removeExpiry(String key) {
        expiries.remove(key);
    }

    public static String payload(String key) {
        return payloads.get(key);
    }

    public static void setPayload(String key, String payload) {
        payloads.put(key, payload);
    }

    public static void removePayload(String key) {
        payloads.remove(key);
    }

    /** True when the session still has a challenge that was not solved yet. */
    public static boolean hasLiveChallenge(String key) {
        String p = payloads.get(key);
        if (p == null || p.equals("null")) return false;
        try {
            JSONObject root = new JSONObject(p);
            JSONObject o = root.optJSONObject("twister");
            if (o == null) o = root;
            if (!o.has("img") && !o.has("tasks")) return false;
            Long exp = expiries.get(key);
            return exp == null || exp > System.currentTimeMillis();
        } catch (Exception e) {
            return false;
        }
    }

    public static String ticket(String key) {
        String t = tickets.get(key);
        return t == null ? "" : t;
    }

    public static void setTicket(String key, String ticket) {
        tickets.put(key, ticket);
    }

    public static void removeTicket(String key) {
        tickets.remove(key);
    }

    public static void clearTickets() {
        tickets.clear();
    }

    public static void saveAnswers(String challenge, String csv) {
        answersByChallenge.put(challenge, csv == null ? "" : csv);
    }

    public static String answers(String challenge) {
        return answersByChallenge.get(challenge);
    }

    public static void removeAnswers(String challenge) {
        answersByChallenge.remove(challenge);
    }

    public static void clearAnswers() {
        answersByChallenge.clear();
    }

    /** Drops everything, used when a session is reset. */
    public static void clear() {
        payloads.clear();
        cooldowns.clear();
        expiries.clear();
        tickets.clear();
        answersByChallenge.clear();
    }
}
