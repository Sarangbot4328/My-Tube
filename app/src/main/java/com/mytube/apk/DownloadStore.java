package com.mytube.apk;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

// Persists the list of completed downloads and the user's chosen save folder.
// Backed by SharedPreferences (small metadata only; the videos live on disk).
final class DownloadStore {
    private static final String PREFS = "mytube_downloads";
    private static final String KEY_ITEMS = "items";
    private static final String KEY_FOLDER_URI = "folder_uri";
    private static final String KEY_NEXT_PLAY_ORDER = "next_play_order";
    private static final String KEY_DEFAULT_QUALITY = "default_quality";
    static final String ORDER_SEQUENTIAL = "sequential";
    static final String ORDER_RANDOM = "random";
    static final String QUALITY_LOWEST = "lowest";
    static final String QUALITY_HIGHEST = "highest";

    private DownloadStore() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static List<DownloadItem> list(Context c) {
        List<DownloadItem> items = new ArrayList<>();
        String raw = prefs(c).getString(KEY_ITEMS, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                items.add(new DownloadItem(
                        o.optString("id"),
                        o.optString("title"),
                        o.optString("uploader"),
                        o.optString("uri"),
                        o.optString("thumbnailUrl"),
                        o.optString("quality"),
                        o.optString("searchText")
                ));
            }
        } catch (Exception ignored) {
            // corrupt store — treat as empty
        }
        return items;
    }

    static boolean exists(Context c, String id) {
        if (id == null || id.isEmpty()) return false;
        for (DownloadItem item : list(c)) {
            if (id.equals(item.id)) return true;
        }
        return false;
    }

    static void add(Context c, DownloadItem item) {
        List<DownloadItem> items = list(c);
        // Replace any existing entry with the same id.
        List<DownloadItem> filtered = new ArrayList<>();
        for (DownloadItem existing : items) {
            if (!existing.id.equals(item.id)) filtered.add(existing);
        }
        filtered.add(0, item);
        save(c, filtered);
    }

    // Removes the record and returns it (so the caller can delete the file).
    static DownloadItem remove(Context c, String id) {
        List<DownloadItem> items = list(c);
        DownloadItem removed = null;
        List<DownloadItem> filtered = new ArrayList<>();
        for (DownloadItem existing : items) {
            if (existing.id.equals(id)) {
                removed = existing;
            } else {
                filtered.add(existing);
            }
        }
        save(c, filtered);
        return removed;
    }

    private static void save(Context c, List<DownloadItem> items) {
        JSONArray array = new JSONArray();
        for (DownloadItem item : items) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", item.id);
                o.put("title", item.title);
                o.put("uploader", item.uploader);
                o.put("uri", item.uri);
                o.put("thumbnailUrl", item.thumbnailUrl);
                o.put("quality", item.quality);
                o.put("searchText", item.searchText);
                array.put(o);
            } catch (Exception ignored) {
                // skip malformed entry
            }
        }
        prefs(c).edit().putString(KEY_ITEMS, array.toString()).apply();
    }

    static String getFolderUri(Context c) {
        return prefs(c).getString(KEY_FOLDER_URI, "");
    }

    static void setFolderUri(Context c, String uri) {
        prefs(c).edit().putString(KEY_FOLDER_URI, uri == null ? "" : uri).apply();
    }

    static String getNextPlayOrder(Context c) {
        String order = prefs(c).getString(KEY_NEXT_PLAY_ORDER, ORDER_SEQUENTIAL);
        return ORDER_RANDOM.equals(order) ? ORDER_RANDOM : ORDER_SEQUENTIAL;
    }

    static void setNextPlayOrder(Context c, String order) {
        prefs(c).edit()
                .putString(KEY_NEXT_PLAY_ORDER,
                        ORDER_RANDOM.equals(order) ? ORDER_RANDOM : ORDER_SEQUENTIAL)
                .apply();
    }

    static String getDefaultQuality(Context c) {
        String quality = prefs(c).getString(KEY_DEFAULT_QUALITY, QUALITY_HIGHEST);
        if (QUALITY_LOWEST.equals(quality) || QUALITY_HIGHEST.equals(quality)
                || "420".equals(quality) || "540".equals(quality)
                || "720".equals(quality) || "1080".equals(quality)) {
            return quality;
        }
        return QUALITY_HIGHEST;
    }

    static void setDefaultQuality(Context c, String quality) {
        prefs(c).edit().putString(KEY_DEFAULT_QUALITY, normalizeQuality(quality)).apply();
    }

    private static String normalizeQuality(String quality) {
        if (QUALITY_LOWEST.equals(quality) || QUALITY_HIGHEST.equals(quality)
                || "420".equals(quality) || "540".equals(quality)
                || "720".equals(quality) || "1080".equals(quality)) {
            return quality;
        }
        return QUALITY_HIGHEST;
    }
}
