package com.mytube.apk;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

final class ImageLoader {
    private final ExecutorService executor;
    private final Handler mainHandler;
    private final Map<String, Bitmap> cache = new ConcurrentHashMap<>();

    ImageLoader(ExecutorService executor, Handler mainHandler) {
        this.executor = executor;
        this.mainHandler = mainHandler;
    }

    void load(String url, ImageView target) {
        target.setImageDrawable(null);
        if (url == null || url.isEmpty()) return;
        Bitmap cached = cache.get(url);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        target.setTag(url);
        executor.execute(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(15000);
                try (InputStream input = connection.getInputStream()) {
                    Bitmap bitmap = BitmapFactory.decodeStream(input);
                    if (bitmap != null) cache.put(url, bitmap);
                    mainHandler.post(() -> {
                        if (url.equals(target.getTag()) && bitmap != null) target.setImageBitmap(bitmap);
                    });
                }
            } catch (Exception ignored) {
            }
        });
    }
}
