package com.mytube.apk;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.CookieManager;

import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * YouTube/Google session cookies shared by search, playback metadata, and downloads.
 * PC My Tube avoids blocks largely because yt-dlp carries Edge login cookies; this
 * store is the APK equivalent.
 */
final class CookieStore {
    private static final String PREFS = "mytube_cookies";
    private static final String KEY_HEADER = "cookie_header";
    private static final String KEY_SOURCE = "cookie_source";
    private static final String KEY_UPDATED_AT = "updated_at";

    /** Minimal consent so results HTML is not an EU interstitial. */
    private static final String CONSENT =
            "SOCS=CAI; CONSENT=YES+cb.20210328-17-p0.en+FX+000";

    private static final String[] AUTH_MARKERS = {
            "LOGIN_INFO",
            "SAPISID",
            "__Secure-1PSID",
            "__Secure-3PSID",
            "SID",
            "HSID",
            "SSID",
            "APISID",
            "__Secure-1PAPISID",
            "__Secure-3PAPISID",
    };

    private static volatile Context app;

    /**
     * When true on this thread, only consent cookies are sent (no login session).
     * Used so stream resolve can fall back if a logged-in WEB session breaks player clients.
     */
    private static final ThreadLocal<Boolean> GUEST_MODE = ThreadLocal.withInitial(() -> false);

    private CookieStore() {}

    static void init(Context context) {
        if (context != null) app = context.getApplicationContext();
    }

    static void setGuestMode(boolean guest) {
        GUEST_MODE.set(guest);
    }

    static boolean isGuestMode() {
        return Boolean.TRUE.equals(GUEST_MODE.get());
    }

    private static SharedPreferences prefs() {
        if (app == null) {
            throw new IllegalStateException("CookieStore.init() was not called");
        }
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Cookie header for YouTube requests (consent + optional login session). */
    static String cookieHeader() {
        String saved = "";
        try {
            saved = prefs().getString(KEY_HEADER, "");
        } catch (Exception ignored) {
            // not initialized yet during early static use
        }
        if (saved == null || saved.trim().isEmpty()) return CONSENT;
        return mergeHeaders(CONSENT, saved.trim());
    }

    static boolean hasAuthCookies() {
        String header = "";
        try {
            header = prefs().getString(KEY_HEADER, "");
        } catch (Exception ignored) {
            return false;
        }
        if (header == null || header.isEmpty()) return false;
        String upper = header.toUpperCase(Locale.US);
        for (String marker : AUTH_MARKERS) {
            if (upper.contains(marker.toUpperCase(Locale.US) + "=")) return true;
        }
        return false;
    }

    static String statusText() {
        if (!hasAuthCookies()) {
            return "로그인 안 됨 · PC처럼 쿠키가 없으면 검색·대량 다운로드 시 IP 제한이 잘 걸립니다.";
        }
        String source = prefs().getString(KEY_SOURCE, "session");
        long at = prefs().getLong(KEY_UPDATED_AT, 0L);
        String when = at > 0 ? " · 저장 " + android.text.format.DateFormat.format("MM/dd HH:mm", at) : "";
        return "YouTube 로그인 쿠키 사용 중 (" + source + ")" + when;
    }

    /**
     * Attach cookies only. Does NOT add SAPISIDHASH — that header must only go on
     * WEB browse/search. Putting it on NewPipe / iOS player / media CDN breaks playback.
     */
    static void applyTo(HttpURLConnection connection) {
        applyTo(connection, false);
    }

    /**
     * @param webAuth if true, also send SAPISIDHASH (home feed / WEB search only)
     */
    static void applyTo(HttpURLConnection connection, boolean webAuth) {
        if (connection == null) return;
        if (isGuestMode()) {
            connection.setRequestProperty("Cookie", CONSENT);
            return;
        }
        String header = cookieHeader();
        if (!header.isEmpty()) connection.setRequestProperty("Cookie", header);
        if (webAuth) applyWebAuth(connection);
    }

    /** WEB InnerTube home/browse only. */
    static void applyWebAuth(HttpURLConnection connection) {
        if (connection == null || isGuestMode() || !hasAuthCookies()) return;
        String auth = sapisidAuthorization();
        if (auth == null || auth.isEmpty()) return;
        connection.setRequestProperty("Authorization", auth);
        connection.setRequestProperty("X-Origin", "https://www.youtube.com");
        connection.setRequestProperty("X-Goog-AuthUser", "0");
    }

    /** Single cookie value from the stored header (first match). */
    static String cookieValue(String name) {
        if (name == null || name.isEmpty()) return "";
        String header = cookieHeader();
        for (String part : header.split(";")) {
            String piece = part.trim();
            int eq = piece.indexOf('=');
            if (eq <= 0) continue;
            if (name.equals(piece.substring(0, eq).trim())) {
                return piece.substring(eq + 1).trim();
            }
        }
        return "";
    }

    /**
     * YouTube WEB client auth: SAPISIDHASH {unix}_{sha1(unix + " " + SAPISID + " " + origin)}.
     * Without this, browse home often returns a generic/guest shelf even with Cookie set.
     */
    static String sapisidAuthorization() {
        String sapisid = cookieValue("SAPISID");
        if (sapisid.isEmpty()) sapisid = cookieValue("__Secure-3PAPISID");
        if (sapisid.isEmpty()) sapisid = cookieValue("__Secure-1PAPISID");
        if (sapisid.isEmpty()) return "";
        long ts = System.currentTimeMillis() / 1000L;
        String origin = "https://www.youtube.com";
        String raw = ts + " " + sapisid + " " + origin;
        String hash = sha1Hex(raw);
        if (hash.isEmpty()) return "";
        return "SAPISIDHASH " + ts + "_" + hash;
    }

    private static String sha1Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] dig = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    static void clear() {
        prefs().edit()
                .remove(KEY_HEADER)
                .remove(KEY_SOURCE)
                .remove(KEY_UPDATED_AT)
                .apply();
    }

    /**
     * Import Netscape cookies.txt (same format PC My Tube / yt-dlp uses).
     * @return number of YouTube/Google cookies kept
     */
    static int importNetscape(String text) {
        if (text == null || text.trim().isEmpty()) return 0;
        Map<String, String> map = new LinkedHashMap<>();
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            boolean httpOnly = line.startsWith("#HttpOnly_");
            if (line.startsWith("#") && !httpOnly) continue;
            if (httpOnly) line = line.substring("#HttpOnly_".length());
            String[] parts = line.split("\t");
            if (parts.length < 7) continue;
            String domain = parts[0].trim().toLowerCase(Locale.US);
            if (!isAllowedDomain(domain)) continue;
            String name = parts[5].trim();
            String value = parts[6].trim();
            if (name.isEmpty()) continue;
            if (name.indexOf('\n') >= 0 || value.indexOf('\n') >= 0) continue;
            map.put(name, value);
        }
        if (map.isEmpty()) return 0;
        saveMap(map, "cookies.txt");
        return map.size();
    }

    /** Pull cookies from WebView CookieManager after the user signs in. */
    static int importFromWebView() {
        CookieManager manager = CookieManager.getInstance();
        manager.flush();
        Map<String, String> map = new LinkedHashMap<>();
        putCookieString(map, manager.getCookie("https://www.youtube.com"));
        putCookieString(map, manager.getCookie("https://youtube.com"));
        putCookieString(map, manager.getCookie("https://www.google.com"));
        putCookieString(map, manager.getCookie("https://accounts.google.com"));
        if (map.isEmpty()) return 0;
        // Keep only names that look useful; still require at least one auth marker.
        saveMap(map, "WebView 로그인");
        return hasAuthCookies() ? map.size() : 0;
    }

    private static void putCookieString(Map<String, String> map, String header) {
        if (header == null || header.isEmpty()) return;
        for (String part : header.split(";")) {
            String piece = part.trim();
            int eq = piece.indexOf('=');
            if (eq <= 0) continue;
            String name = piece.substring(0, eq).trim();
            String value = piece.substring(eq + 1).trim();
            if (!name.isEmpty()) map.put(name, value);
        }
    }

    private static void saveMap(Map<String, String> map, String source) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        prefs().edit()
                .putString(KEY_HEADER, sb.toString())
                .putString(KEY_SOURCE, source)
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                .apply();
    }

    private static boolean isAllowedDomain(String domain) {
        String d = domain.startsWith(".") ? domain.substring(1) : domain;
        return d.equals("youtube.com")
                || d.endsWith(".youtube.com")
                || d.equals("google.com")
                || d.endsWith(".google.com")
                || d.equals("googlevideo.com")
                || d.endsWith(".googlevideo.com")
                || d.equals("google.co.kr")
                || d.endsWith(".google.co.kr")
                || d.equals("accounts.google.com")
                || d.endsWith(".accounts.google.com")
                || d.equals("youtube-nocookie.com")
                || d.endsWith(".youtube-nocookie.com");
    }

    private static String mergeHeaders(String base, String extra) {
        Map<String, String> map = new LinkedHashMap<>();
        putCookieString(map, base);
        putCookieString(map, extra);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
