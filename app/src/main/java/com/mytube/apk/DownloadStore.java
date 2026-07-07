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
                        o.optString("quality")
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
}
