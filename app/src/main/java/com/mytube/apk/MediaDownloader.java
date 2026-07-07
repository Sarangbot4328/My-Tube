package com.mytube.apk;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Environment;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;

// Saves a chosen quality to local storage. Progressive streams are written
// straight to disk; adaptive (1080p+) qualities download the mp4 video and m4a
// audio separately and combine them with the OS MediaMuxer (no ffmpeg needed).
final class MediaDownloader {
    private static final String USER_AGENT =
            "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X)";

    private interface StreamWriter {
        void write(OutputStream out) throws Exception;
    }

    private MediaDownloader() {}

    // Returns the uri string of the saved file.
    static String save(Context context, ExtractorBridge.DownloadOption option, String title) throws Exception {
        String fileName = safeFileName(title, option.ext);
        if (!option.muxed) {
            return writeToTarget(context, fileName, "video/mp4", out -> copyUrl(option.videoUrl, out));
        }

        File cache = context.getCacheDir();
        File videoTmp = File.createTempFile("mtv", ".mp4", cache);
        File audioTmp = File.createTempFile("mta", ".m4a", cache);
        File muxed = File.createTempFile("mtmux", ".mp4", cache);
        try {
            try (OutputStream vo = new FileOutputStream(videoTmp)) { copyUrl(option.videoUrl, vo); }
            try (OutputStream ao = new FileOutputStream(audioTmp)) { copyUrl(option.audioUrl, ao); }
            muxToFile(videoTmp.getAbsolutePath(), audioTmp.getAbsolutePath(), muxed.getAbsolutePath());
            return writeToTarget(context, fileName, "video/mp4", out -> copyFile(muxed, out));
        } finally {
            videoTmp.delete();
            audioTmp.delete();
            muxed.delete();
        }
    }

    // Writes to the user's chosen folder (SAF) when set, else the app's own
    // external Movies dir (which needs no runtime permission).
    private static String writeToTarget(Context context, String fileName, String mime, StreamWriter writer)
            throws Exception {
        String folderUri = DownloadStore.getFolderUri(context);
        if (folderUri != null && !folderUri.isEmpty()) {
            try {
                DocumentFile dir = DocumentFile.fromTreeUri(context, Uri.parse(folderUri));
                if (dir != null && dir.canWrite()) {
                    DocumentFile existing = dir.findFile(fileName);
                    if (existing != null) existing.delete();
                    DocumentFile file = dir.createFile(mime, fileName);
                    if (file != null) {
                        try (OutputStream out = context.getContentResolver().openOutputStream(file.getUri())) {
                            if (out != null) {
                                writer.write(out);
                                return file.getUri().toString();
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
                // chosen folder unusable — fall back to app storage
            }
        }
        File dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (dir == null) dir = context.getFilesDir();
        if (!dir.exists()) dir.mkdirs();
        File file = new File(dir, fileName);
        try (OutputStream out = new FileOutputStream(file)) {
            writer.write(out);
        }
        return Uri.fromFile(file).toString();
    }

    private static void copyUrl(String url, OutputStream out) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(60000);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        try (InputStream in = connection.getInputStream()) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            out.flush();
        } finally {
            connection.disconnect();
        }
    }

    private static void copyFile(File file, OutputStream out) throws Exception {
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            out.flush();
        }
    }

    // Remuxes a separate mp4 video and m4a audio into a single mp4.
    private static void muxToFile(String videoPath, String audioPath, String outPath) throws Exception {
        MediaExtractor videoEx = new MediaExtractor();
        MediaExtractor audioEx = new MediaExtractor();
        MediaMuxer muxer = new MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        try {
            videoEx.setDataSource(videoPath);
            audioEx.setDataSource(audioPath);

            int videoTrack = selectTrack(videoEx, "video/");
            int audioTrack = selectTrack(audioEx, "audio/");
            if (videoTrack < 0) throw new IllegalStateException("영상 트랙을 찾지 못했습니다.");

            MediaFormat videoFormat = videoEx.getTrackFormat(videoTrack);
            int outVideo = muxer.addTrack(videoFormat);
            int outAudio = -1;
            MediaFormat audioFormat = null;
            if (audioTrack >= 0) {
                audioFormat = audioEx.getTrackFormat(audioTrack);
                outAudio = muxer.addTrack(audioFormat);
            }

            muxer.start();
            copySamples(videoEx, muxer, outVideo, maxInputSize(videoFormat));
            if (audioTrack >= 0) copySamples(audioEx, muxer, outAudio, maxInputSize(audioFormat));
            muxer.stop();
        } finally {
            try { muxer.release(); } catch (Exception ignored) {}
            videoEx.release();
            audioEx.release();
        }
    }

    private static int selectTrack(MediaExtractor extractor, String mimePrefix) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(mimePrefix)) {
                extractor.selectTrack(i);
                return i;
            }
        }
        return -1;
    }

    private static int maxInputSize(MediaFormat format) {
        if (format != null && format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            int size = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
            if (size > 0) return Math.max(size, 1024 * 1024);
        }
        return 4 * 1024 * 1024;
    }

    private static void copySamples(MediaExtractor extractor, MediaMuxer muxer, int outTrack, int bufferSize) {
        ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int size = extractor.readSampleData(buffer, 0);
            if (size < 0) break;
            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = extractor.getSampleTime();
            info.flags = (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                    ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
            muxer.writeSampleData(outTrack, buffer, info);
            extractor.advance();
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
