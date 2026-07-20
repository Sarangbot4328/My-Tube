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
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

// Saves a chosen quality to local storage. Progressive streams are written
// straight to disk; adaptive (1080p+) qualities download the mp4 video and m4a
// audio separately and combine them with the OS MediaMuxer (no ffmpeg needed).
final class MediaDownloader {
    private static final int CONNECT_TIMEOUT_MS = 45_000;
    private static final int READ_TIMEOUT_MS = 180_000;
    private static final int MAX_ATTEMPTS = 5;
    /** Parallel Range workers for faster single-file pulls (when server allows). */
    private static final int PARALLEL_PARTS = 4;
    private static final long PARALLEL_MIN_BYTES = 2L * 1024 * 1024;

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
        RequestPacer.beforeDownload();
        String fileName = safeFileName(title, option.ext);
        File cache = context.getCacheDir();

        if (!option.muxed) {
            File tmp = File.createTempFile("mtp", ".tmp", cache);
            try {
                downloadToFile(option.videoUrl, option.userAgent, tmp, "다운로드", listener);
                report(listener, "저장 중...");
                return writeToTarget(context, fileName, "video/mp4", out -> copyFile(tmp, out));
            } finally {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }

        File videoTmp = File.createTempFile("mtv", ".mp4", cache);
        File audioTmp = File.createTempFile("mta", ".m4a", cache);
        File muxed = File.createTempFile("mtmux", ".mp4", cache);
        try {
            // Video + audio in parallel (big speedup for 1080p muxed).
            report(listener, "영상·오디오 동시 다운로드…");
            ExecutorService pair = Executors.newFixedThreadPool(2);
            try {
                Future<?> fv = pair.submit(() -> {
                    downloadToFile(option.videoUrl, option.userAgent, videoTmp, "영상", listener);
                    return null;
                });
                Future<?> fa = pair.submit(() -> {
                    downloadToFile(option.audioUrl, option.userAgent, audioTmp, "오디오", listener);
                    return null;
                });
                fv.get();
                fa.get();
            } finally {
                pair.shutdownNow();
            }
            report(listener, "영상·오디오 합치는 중...");
            try {
                muxToFile(videoTmp.getAbsolutePath(), audioTmp.getAbsolutePath(), muxed.getAbsolutePath());
            } catch (Exception muxErr) {
                throw new IllegalStateException(
                        "영상·오디오 합치기 실패: "
                                + (muxErr.getMessage() == null ? muxErr.toString() : muxErr.getMessage()),
                        muxErr);
            }
            report(listener, "저장 중...");
            return writeToTarget(context, fileName, "video/mp4", out -> copyFile(muxed, out));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            videoTmp.delete();
            //noinspection ResultOfMethodCallIgnored
            audioTmp.delete();
            //noinspection ResultOfMethodCallIgnored
            muxed.delete();
        }
    }

    /** Delete leftover download temp files (failed mux / interrupted downloads). */
    static int clearTempFiles(Context context) {
        int removed = 0;
        removed += clearMatching(context.getCacheDir());
        File ext = context.getExternalCacheDir();
        if (ext != null) removed += clearMatching(ext);
        return removed;
    }

    private static int clearMatching(File dir) {
        if (dir == null || !dir.isDirectory()) return 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        int n = 0;
        for (File f : files) {
            String name = f.getName();
            if (name.startsWith("mtp") || name.startsWith("mtv") || name.startsWith("mta")
                    || name.startsWith("mtmux") || name.endsWith(".tmp")
                    || name.startsWith("DownloadQueue") || name.startsWith("mytube_tmp")) {
                if (f.isFile() && f.delete()) n++;
            }
        }
        return n;
    }

    private static void report(ProgressListener listener, String status) {
        if (listener != null) listener.onProgress(status);
    }

    private static void downloadToFile(String url, String userAgent, File dest, String label,
                                       ProgressListener listener) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                //noinspection ResultOfMethodCallIgnored
                if (dest.exists()) dest.delete();
                // Prefer multi-connection Range when Content-Length is known.
                if (tryParallelDownload(url, userAgent, dest, label, listener)) {
                    if (dest.length() <= 0) throw new java.io.IOException("받은 데이터가 비어 있습니다.");
                    return;
                }
                copyUrlResumable(url, userAgent, dest, 0, label, listener);
                if (dest.length() <= 0) throw new java.io.IOException("받은 데이터가 비어 있습니다.");
                return;
            } catch (Exception e) {
                last = e;
                if (!isRetryable(e) || attempt == MAX_ATTEMPTS) break;
                report(listener, "연결 재시도 " + attempt + "/" + MAX_ATTEMPTS + "…");
                try {
                    Thread.sleep(700L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                //noinspection ResultOfMethodCallIgnored
                dest.delete();
            }
        }
        throw last == null ? new java.io.IOException("다운로드 실패") : last;
    }

    /** @return true if parallel download completed; false if should fall back to single stream */
    private static boolean tryParallelDownload(String url, String userAgent, File dest,
                                               String label, ProgressListener listener) {
        long total = probeContentLength(url, userAgent);
        if (total < PARALLEL_MIN_BYTES) return false;
        ExecutorService pool = Executors.newFixedThreadPool(PARALLEL_PARTS);
        try {
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
            try (RandomAccessFile raf = new RandomAccessFile(dest, "rw")) {
                raf.setLength(total);
            }
            AtomicLong done = new AtomicLong(0);
            AtomicLong lastReport = new AtomicLong(0);
            List<Callable<Void>> tasks = new ArrayList<>();
            long part = (total + PARALLEL_PARTS - 1) / PARALLEL_PARTS;
            for (int i = 0; i < PARALLEL_PARTS; i++) {
                final long start = i * part;
                if (start >= total) break;
                final long end = Math.min(total - 1, start + part - 1);
                tasks.add(() -> {
                    downloadRangeToFile(url, userAgent, dest, start, end);
                    long now = done.addAndGet(end - start + 1);
                    if (listener != null && now - lastReport.get() >= 1024 * 1024) {
                        lastReport.set(now);
                        listener.onProgress(progressText(label + "×" + PARALLEL_PARTS, now, total));
                    }
                    return null;
                });
            }
            List<Future<Void>> futures = pool.invokeAll(tasks);
            for (Future<Void> f : futures) f.get();
            if (dest.length() < total) {
                // incomplete — let caller fall back
                //noinspection ResultOfMethodCallIgnored
                dest.delete();
                return false;
            }
            report(listener, progressText(label, total, total));
            return true;
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
            return false;
        } finally {
            pool.shutdownNow();
        }
    }

    private static long probeContentLength(String url, String userAgent) {
        HttpURLConnection c = null;
        try {
            c = openMedia(url, userAgent);
            c.setRequestMethod("HEAD");
            int code = c.getResponseCode();
            if (code >= 200 && code < 300) {
                long len = c.getContentLengthLong();
                if (len > 0) return len;
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.disconnect();
        }
        // Some CDNs reject HEAD — try GET Range 0-0
        try {
            c = openMedia(url, userAgent);
            c.setRequestProperty("Range", "bytes=0-0");
            int code = c.getResponseCode();
            String cr = c.getHeaderField("Content-Range");
            if (cr != null) {
                int slash = cr.lastIndexOf('/');
                if (slash > 0) {
                    return Long.parseLong(cr.substring(slash + 1).trim());
                }
            }
            if (code >= 200 && code < 300) return c.getContentLengthLong();
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.disconnect();
        }
        return -1;
    }

    private static void downloadRangeToFile(String url, String userAgent, File dest, long start, long end)
            throws Exception {
        HttpURLConnection connection = openMedia(url, userAgent);
        connection.setRequestProperty("Range", "bytes=" + start + "-" + end);
        try {
            int code = connection.getResponseCode();
            if (code != 206 && code != 200) {
                throw new java.io.IOException("Range HTTP " + code);
            }
            try (InputStream in = connection.getInputStream();
                 RandomAccessFile raf = new RandomAccessFile(dest, "rw")) {
                raf.seek(start);
                byte[] buffer = new byte[256 * 1024];
                long remaining = end - start + 1;
                while (remaining > 0) {
                    int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (n < 0) break;
                    raf.write(buffer, 0, n);
                    remaining -= n;
                }
                if (remaining > 0) throw new java.io.IOException("Range incomplete");
            }
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection openMedia(String url, String userAgent) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("User-Agent",
                userAgent == null || userAgent.isEmpty() ? HttpDownloader.BROWSER_UA : userAgent);
        connection.setRequestProperty("Accept", "*/*");
        connection.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.7,en;q=0.5");
        connection.setRequestProperty("Origin", "https://www.youtube.com");
        connection.setRequestProperty("Referer", "https://www.youtube.com/");
        connection.setRequestProperty("Cookie", "SOCS=CAI; CONSENT=YES+");
        return connection;
    }

    private static String messageOf(Exception e) {
        return e.getMessage() == null ? "" : e.getMessage().toLowerCase();
    }

    private static boolean isRetryable(Exception e) {
        String msg = messageOf(e);
        return msg.contains("http 403")
                || msg.contains("http 429")
                || msg.contains("http 5")
                || msg.contains("timeout")
                || msg.contains("timed out")
                || msg.contains("failed to connect")
                || msg.contains("connection")
                || msg.contains("reset")
                || msg.contains("eof")
                || msg.contains("broken pipe")
                || msg.contains("unable to resolve")
                || msg.contains("network");
    }

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
                // fall back
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

    /**
     * Sequential download with optional Range resume when {@code already > 0}.
     * Long connect/read timeouts reduce mid-transfer "failed to connect" on slow mobile nets.
     */
    private static void copyUrlResumable(String url, String userAgent, File dest, long already,
                                         String label, ProgressListener listener) throws Exception {
        HttpURLConnection connection = openMedia(url, userAgent);
        if (already > 0) {
            connection.setRequestProperty("Range", "bytes=" + already + "-");
        }
        try {
            int code = connection.getResponseCode();
            if (code == 416) {
                // Already complete according to server
                return;
            }
            if (code >= 400) {
                throw new java.io.IOException("서버 응답 오류 (HTTP " + code + ")");
            }
            boolean partial = (code == 206);
            long total = connection.getContentLengthLong();
            if (partial && total > 0) total = already + total;
            else if (!partial) {
                already = 0;
                //noinspection ResultOfMethodCallIgnored
                dest.delete();
            }
            long done = already;
            long lastReport = already;
            try (InputStream in = connection.getInputStream();
                 RandomAccessFile raf = new RandomAccessFile(dest, "rw")) {
                if (partial && already > 0) raf.seek(already);
                else raf.setLength(0);
                byte[] buffer = new byte[128 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    raf.write(buffer, 0, read);
                    done += read;
                    if (listener != null && done - lastReport >= 1024 * 1024) {
                        lastReport = done;
                        listener.onProgress(progressText(label, done, total));
                    }
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String progressText(String label, long done, long total) {
        if (total > 0) {
            int pct = (int) Math.min(100, done * 100 / total);
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
        }
    }

    static String safeFileName(String title, String ext) {
        String base = title == null ? "video" : title;
        base = base.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (base.length() > 80) base = base.substring(0, 80);
        if (base.isEmpty()) base = "video";
        return base + "." + (ext == null || ext.isEmpty() ? "mp4" : ext);
    }
}
