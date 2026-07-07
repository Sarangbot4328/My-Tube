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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

// Saves a chosen quality to local storage. Progressive streams are written
// straight to disk; adaptive (1080p+) qualities download the mp4 video and m4a
// audio separately and combine them with the OS MediaMuxer (no ffmpeg needed).
final class MediaDownloader {
    private static final String USER_AGENT =
            "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X)";

    // Split each stream into chunks fetched in parallel — googlevideo throttles a
    // single connection, so several short Range requests are far faster.
    private static final int CHUNK_SIZE = 8 * 1024 * 1024;
    private static final int MAX_PARALLEL = 4;

    interface ProgressListener {
        void onProgress(String status);
    }

    private interface StreamWriter {
        void write(OutputStream out) throws Exception;
    }

    private MediaDownloader() {}

    // Returns the uri string of the saved file.
    static String save(Context context, ExtractorBridge.DownloadOption option, String title,
                       ProgressListener listener) throws Exception {
        String fileName = safeFileName(title, option.ext);
        File cache = context.getCacheDir();

        if (!option.muxed) {
            File tmp = File.createTempFile("mtp", ".tmp", cache);
            try {
                downloadToFile(option.videoUrl, tmp, "다운로드", listener);
                report(listener, "저장 중...");
                return writeToTarget(context, fileName, "video/mp4", out -> copyFile(tmp, out));
            } finally {
                tmp.delete();
            }
        }

        File videoTmp = File.createTempFile("mtv", ".mp4", cache);
        File audioTmp = File.createTempFile("mta", ".m4a", cache);
        File muxed = File.createTempFile("mtmux", ".mp4", cache);
        try {
            downloadToFile(option.videoUrl, videoTmp, "영상 다운로드", listener);
            downloadToFile(option.audioUrl, audioTmp, "오디오 다운로드", listener);
            report(listener, "영상·오디오 합치는 중...");
            muxToFile(videoTmp.getAbsolutePath(), audioTmp.getAbsolutePath(), muxed.getAbsolutePath());
            report(listener, "저장 중...");
            return writeToTarget(context, fileName, "video/mp4", out -> copyFile(muxed, out));
        } finally {
            videoTmp.delete();
            audioTmp.delete();
            muxed.delete();
        }
    }

    private static void report(ProgressListener listener, String status) {
        if (listener != null) listener.onProgress(status);
    }

    // Downloads a URL into dest using parallel Range requests. Falls back to a
    // single stream when the server doesn't report a length / support ranges.
    private static void downloadToFile(String url, File dest, String label, ProgressListener listener)
            throws Exception {
        long total = probeLength(url);
        if (total <= 0) {
            try (OutputStream out = new FileOutputStream(dest)) {
                copyUrlSingle(url, out, label, listener);
            }
            return;
        }

        try (RandomAccessFile raf = new RandomAccessFile(dest, "rw")) {
            raf.setLength(total);
        }

        List<long[]> chunks = new ArrayList<>();
        for (long start = 0; start < total; start += CHUNK_SIZE) {
            chunks.add(new long[]{start, Math.min(start + CHUNK_SIZE - 1, total - 1)});
        }

        final long finalTotal = total;
        final AtomicLong downloaded = new AtomicLong(0);
        final AtomicLong lastReport = new AtomicLong(0);
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(MAX_PARALLEL, chunks.size()));
        List<Future<?>> futures = new ArrayList<>();
        for (long[] range : chunks) {
            final long start = range[0];
            final long end = range[1];
            futures.add(pool.submit(() -> {
                downloadChunk(url, dest, start, end, downloaded, finalTotal, lastReport, label, listener);
                return null;
            }));
        }
        pool.shutdown();
        try {
            for (Future<?> f : futures) f.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw new IOException(cause == null ? e.toString() : cause.getMessage());
        } finally {
            pool.shutdownNow();
        }
        if (listener != null) listener.onProgress(progressText(label, finalTotal, finalTotal));
    }

    private static void downloadChunk(String url, File dest, long start, long end,
                                      AtomicLong downloaded, long total, AtomicLong lastReport,
                                      String label, ProgressListener listener) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(60000);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Range", "bytes=" + start + "-" + end);
        try {
            int code = connection.getResponseCode();
            if (code >= 400) throw new IOException("서버 응답 오류 (HTTP " + code + ")");
            try (InputStream in = connection.getInputStream();
                 RandomAccessFile raf = new RandomAccessFile(dest, "rw")) {
                raf.seek(start);
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    raf.write(buffer, 0, read);
                    long done = downloaded.addAndGet(read);
                    if (listener != null && done - lastReport.get() >= 1024 * 1024) {
                        lastReport.set(done);
                        listener.onProgress(progressText(label, done, total));
                    }
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    // Returns the total content length via a ranged probe, or -1 when the server
    // doesn't support Range requests (caller then uses a single stream).
    private static long probeLength(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(20000);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Range", "bytes=0-0");
            int code = connection.getResponseCode();
            String contentRange = connection.getHeaderField("Content-Range");
            if (code == 206 && contentRange != null) {
                int slash = contentRange.lastIndexOf('/');
                if (slash >= 0) {
                    try {
                        return Long.parseLong(contentRange.substring(slash + 1).trim());
                    } catch (NumberFormatException ignored) {
                        return -1;
                    }
                }
            }
        } catch (Exception ignored) {
            // no range support / network issue — caller falls back to single stream
        } finally {
            if (connection != null) connection.disconnect();
        }
        return -1;
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

    private static void copyUrlSingle(String url, OutputStream out, String label, ProgressListener listener)
            throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(60000);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        try {
            int code = connection.getResponseCode();
            if (code >= 400) {
                throw new java.io.IOException("서버 응답 오류 (HTTP " + code + ")");
            }
            long total = connection.getContentLengthLong();
            long done = 0;
            long lastReport = 0;
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    done += read;
                    if (listener != null && done - lastReport >= 1024 * 1024) {
                        lastReport = done;
                        listener.onProgress(progressText(label, done, total));
                    }
                }
                out.flush();
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String progressText(String label, long done, long total) {
        if (total > 0) {
            int pct = (int) (done * 100 / total);
            return label + " " + pct + "% (" + (done / (1024 * 1024)) + "/" + (total / (1024 * 1024)) + "MB)";
        }
        return label + " " + (done / (1024 * 1024)) + "MB";
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
