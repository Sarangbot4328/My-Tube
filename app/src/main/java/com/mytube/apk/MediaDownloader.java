package com.mytube.apk;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

// Streams a progressive (muxed) video URL to local storage. When the user has
// picked a folder (SAF tree uri) the file is written there; otherwise it goes to
// the app's own external Movies dir, which needs no runtime permission.
final class MediaDownloader {
    private static final String USER_AGENT =
            "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X)";

    private MediaDownloader() {}

    // Returns the uri string of the saved file.
    static String download(Context context, String url, String fileName, String mime) throws Exception {
        String folderUri = DownloadStore.getFolderUri(context);
        if (folderUri != null && !folderUri.isEmpty()) {
            try {
                return downloadToTree(context, Uri.parse(folderUri), url, fileName, mime);
            } catch (Exception e) {
                // chosen folder no longer writable — fall back to app storage
            }
        }
        return downloadToAppDir(context, url, fileName);
    }

    private static String downloadToAppDir(Context context, String url, String fileName) throws Exception {
        File dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (dir == null) dir = context.getFilesDir();
        if (!dir.exists()) dir.mkdirs();
        File file = new File(dir, fileName);
        try (OutputStream out = new FileOutputStream(file)) {
            copy(url, out);
        }
        return Uri.fromFile(file).toString();
    }

    private static String downloadToTree(Context context, Uri treeUri, String url,
                                         String fileName, String mime) throws Exception {
        DocumentFile dir = DocumentFile.fromTreeUri(context, treeUri);
        if (dir == null || !dir.canWrite()) {
            throw new IllegalStateException("폴더에 쓸 수 없습니다.");
        }
        DocumentFile existing = dir.findFile(fileName);
        if (existing != null) existing.delete();
        DocumentFile file = dir.createFile(mime == null || mime.isEmpty() ? "video/mp4" : mime, fileName);
        if (file == null) throw new IllegalStateException("파일을 만들 수 없습니다.");
        try (OutputStream out = context.getContentResolver().openOutputStream(file.getUri())) {
            if (out == null) throw new IllegalStateException("출력 스트림을 열 수 없습니다.");
            copy(url, out);
        }
        return file.getUri().toString();
    }

    private static void copy(String url, OutputStream out) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(60000);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        try (InputStream in = connection.getInputStream()) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        } finally {
            connection.disconnect();
        }
    }

    // Deletes the physical file backing a download record.
    static void deleteFile(Context context, String uriString) {
        if (uriString == null || uriString.isEmpty()) return;
        try {
            Uri uri = Uri.parse(uriString);
            String scheme = uri.getScheme();
            if ("file".equals(scheme)) {
                String path = uri.getPath();
                if (path != null) new File(path).delete();
            } else if ("content".equals(scheme)) {
                DocumentFile file = DocumentFile.fromSingleUri(context, uri);
                if (file != null) file.delete();
            }
        } catch (Exception ignored) {
            // best-effort deletion
        }
    }

    // Safe file name derived from a video title.
    static String safeFileName(String title, String ext) {
        String base = title == null ? "video" : title;
        base = base.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (base.length() > 80) base = base.substring(0, 80);
        if (base.isEmpty()) base = "video";
        return base + "." + (ext == null || ext.isEmpty() ? "mp4" : ext);
    }
}
