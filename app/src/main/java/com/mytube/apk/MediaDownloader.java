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

// Saves a chosen quality to local storage. Progressive streams are written
// straight to disk; adaptive qualities download video and audio separately and
// combine MP4/H.264 or WebM/VP9 streams with the OS MediaMuxer (no ffmpeg needed).
final class MediaDownloader {
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 45_000;
    private static final int MAX_ATTEMPTS = 8;
    private static final int STALLS_BEFORE_URL_REFRESH = 3;
    private static final int MAX_CONSECUTIVE_STALLS = 10;
    private static final int MAX_SOURCE_REFRESHES = 5;
    private static final long RANGE_CHUNK_BYTES = 16L * 1024 * 1024;

    interface ProgressListener {
        void onProgress(String status);
    }

    private interface StreamWriter {
        void write(OutputStream out) throws Exception;
    }

    private interface SourceRefresher {
        MediaSource refresh() throws Exception;
    }

    private static final class MediaSource {
        final String url;
        final String userAgent;

        MediaSource(String url, String userAgent) {
            this.url = url;
            this.userAgent = userAgent;
        }
    }

    private MediaDownloader() {}

    // Returns the uri string of the saved file.
    static String save(Context context, ExtractorBridge.DownloadOption option, String pageUrl,
                       String title, ProgressListener listener) throws Exception {
        RequestPacer.beforeDownload();
        String fileName = safeFileName(title, option.ext);
        File cache = context.getCacheDir();
        final ExtractorBridge.DownloadOption[] activeOption = {option};
        SourceRefresher videoRefresher = () -> refreshedSource(pageUrl, activeOption, false);

        if (!option.muxed) {
            File tmp = File.createTempFile("mtp", ".tmp", cache);
            try {
                downloadToFile(new MediaSource(option.videoUrl, option.userAgent), videoRefresher,
                        tmp, "다운로드", listener);
                report(listener, "저장 중...");
                return writeToTarget(context, fileName, option.mime, out -> copyFile(tmp, out));
            } finally {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }

        boolean webm = "webm".equalsIgnoreCase(option.ext);
        File videoTmp = File.createTempFile("mtv", webm ? ".webm" : ".mp4", cache);
        File audioTmp = File.createTempFile("mta", webm ? ".webm" : ".m4a", cache);
        File muxed = File.createTempFile("mtmux", webm ? ".webm" : ".mp4", cache);
        try {
            // Keep one media connection active at a time. The previous 4-part Range
            // download combined with video/audio parallelism opened up to 8 CDN
            // connections and frequently triggered YouTube throttling (403/429).
            report(listener, "영상 다운로드…");
            downloadToFile(new MediaSource(option.videoUrl, option.userAgent), videoRefresher,
                    videoTmp, "영상", listener);
            report(listener, "오디오 다운로드…");
            ExtractorBridge.DownloadOption current = activeOption[0];
            SourceRefresher audioRefresher = () -> refreshedSource(pageUrl, activeOption, true);
            downloadToFile(new MediaSource(current.audioUrl, current.userAgent), audioRefresher,
                    audioTmp, "오디오", listener);
            report(listener, "영상·오디오 합치는 중...");
            try {
                muxToFile(videoTmp.getAbsolutePath(), audioTmp.getAbsolutePath(),
                        muxed.getAbsolutePath(), webm);
            } catch (Exception muxErr) {
                throw new IllegalStateException(
                        "영상·오디오 합치기 실패: "
                                + (muxErr.getMessage() == null ? muxErr.toString() : muxErr.getMessage()),
                        muxErr);
            }
            report(listener, "저장 중...");
            return writeToTarget(context, fileName, option.mime, out -> copyFile(muxed, out));
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

    private static MediaSource refreshedSource(String pageUrl,
                                               ExtractorBridge.DownloadOption[] activeOption,
                                               boolean audio) throws Exception {
        if (pageUrl == null || pageUrl.isEmpty()) {
            throw new java.io.IOException("다운로드 주소를 갱신할 원본 영상 주소가 없습니다.");
        }
        ExtractorBridge.DownloadOption refreshed =
                ExtractorBridge.refreshSameOption(pageUrl, activeOption[0]);
        if (refreshed == null) {
            throw new java.io.IOException("같은 화질의 다운로드 주소를 다시 찾지 못했습니다.");
        }
        String refreshedUrl = audio ? refreshed.audioUrl : refreshed.videoUrl;
        if (refreshedUrl == null || refreshedUrl.isEmpty()) {
            throw new java.io.IOException("갱신된 미디어 주소가 비어 있습니다.");
        }
        activeOption[0] = refreshed;
        return new MediaSource(refreshedUrl, refreshed.userAgent);
    }

    private static void downloadToFile(MediaSource initialSource, SourceRefresher refresher,
                                       File dest, String label, ProgressListener listener)
            throws Exception {
        MediaSource source = initialSource;
        long rangeTotal = -1L;
        try {
            rangeTotal = probeRangeTotal(source.url, source.userAgent);
        } catch (Exception probeError) {
            String message = messageOf(probeError);
            if (requiresFreshUrl(probeError)) {
                source = refresher.refresh();
                rangeTotal = probeRangeTotal(source.url, source.userAgent);
            } else if (message.contains("http 429")) {
                throw probeError;
            }
            // A server that rejects the one-byte probe may still allow a normal GET.
        }
        if (rangeTotal > 0L) {
            copyUrlInSequentialChunks(source, refresher, dest, rangeTotal, label, listener);
            if (dest.length() != rangeTotal) {
                throw new java.io.EOFException("다운로드 크기가 원본과 일치하지 않습니다.");
            }
            return;
        }

        Exception last = null;
        int consecutiveStalls = 0;
        int sourceRefreshes = 0;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                long already = dest.exists() ? dest.length() : 0L;
                copyUrlResumable(source.url, source.userAgent, dest, already, label, listener);
                if (dest.length() <= 0) throw new java.io.IOException("받은 데이터가 비어 있습니다.");
                return;
            } catch (Exception e) {
                last = e;
                if (isRetryable(e)) consecutiveStalls++;
                boolean shouldRefresh = requiresFreshUrl(e)
                        || consecutiveStalls >= STALLS_BEFORE_URL_REFRESH;
                if (shouldRefresh && sourceRefreshes < MAX_SOURCE_REFRESHES) {
                    try {
                        source = refresher.refresh();
                        sourceRefreshes++;
                        consecutiveStalls = 0;
                        report(listener, progressText(label, dest.length(), -1L)
                                + " · 다운로드 주소 갱신 중…");
                    } catch (Exception refreshError) {
                        if (requiresFreshUrl(e)) throw refreshError;
                    }
                }
                if ((!isRetryable(e) && !requiresFreshUrl(e)) || attempt == MAX_ATTEMPTS) break;
                report(listener, "연결이 끊겨 이어받는 중 (" + (attempt + 1)
                        + "/" + MAX_ATTEMPTS + ")…");
                try {
                    Thread.sleep(1_000L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last == null ? new java.io.IOException("다운로드 실패") : last;
    }

    private static HttpURLConnection openMedia(String url, String userAgent) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("User-Agent",
                userAgent == null || userAgent.isEmpty() ? HttpDownloader.BROWSER_UA : userAgent);
        connection.setRequestProperty("Accept", "*/*");
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.7,en;q=0.5");
        connection.setRequestProperty("Origin", "https://www.youtube.com");
        connection.setRequestProperty("Referer", "https://www.youtube.com/");
        // Stream URLs are resolved with the current YouTube session. Reuse that
        // session instead of replacing it with consent-only cookies.
        CookieStore.applyTo(connection);
        return connection;
    }

    /** Returns the full size only when the CDN confirms byte-range support. */
    private static long probeRangeTotal(String url, String userAgent) throws Exception {
        HttpURLConnection connection = openMedia(url, userAgent);
        connection.setRequestProperty("Range", "bytes=0-0");
        connection.setRequestProperty("Connection", "close");
        try {
            int code = connection.getResponseCode();
            if (code >= 400) {
                throw new java.io.IOException("서버 응답 오류 (HTTP " + code + ")");
            }
            if (code != 206) return -1L;
            return totalFromContentRange(connection.getHeaderField("Content-Range"));
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Downloads one bounded Range at a time. There is never more than one active
     * CDN connection, but a stalled 4K transfer can reconnect from the exact byte
     * already written instead of waiting on one huge response for several minutes.
     */
    private static void copyUrlInSequentialChunks(MediaSource initialSource,
                                                  SourceRefresher refresher, File dest,
                                                  long total, String label,
                                                  ProgressListener listener) throws Exception {
        if (dest.length() > total) {
            try (RandomAccessFile raf = new RandomAccessFile(dest, "rw")) {
                raf.setLength(0L);
            }
        }
        long done = dest.length();
        long[] lastReport = {done};
        int consecutiveStalls = 0;
        int sourceRefreshes = 0;
        MediaSource source = initialSource;

        while (done < total) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("다운로드가 중단되었습니다.");
            }
            long before = done;
            long end = Math.min(total - 1L, done + RANGE_CHUNK_BYTES - 1L);
            try {
                done = downloadRangeChunk(source.url, source.userAgent, dest, done, end, total,
                        label, listener, lastReport);
                consecutiveStalls = 0;
            } catch (Exception error) {
                long written = Math.min(dest.length(), total);
                // Meaningful forward progress should not consume a stall attempt.
                boolean advanced = written - before >= 64L * 1024L;
                if (advanced) {
                    consecutiveStalls = 0;
                } else {
                    consecutiveStalls++;
                }
                done = written;
                boolean shouldRefresh = requiresFreshUrl(error)
                        || (!advanced && consecutiveStalls >= STALLS_BEFORE_URL_REFRESH);
                if (shouldRefresh && sourceRefreshes < MAX_SOURCE_REFRESHES) {
                    try {
                        report(listener, progressText(label, done, total)
                                + " · 다운로드 주소 갱신 중…");
                        source = refresher.refresh();
                        sourceRefreshes++;
                        consecutiveStalls = 0;
                        continue;
                    } catch (Exception refreshError) {
                        if (requiresFreshUrl(error)) throw refreshError;
                    }
                }
                if (!isRetryable(error) || consecutiveStalls >= MAX_CONSECUTIVE_STALLS) {
                    throw error;
                }
                report(listener, progressText(label, done, total) + (advanced
                        ? " · 다음 구간 이어받는 중…"
                        : " · 연결 복구 중 (" + consecutiveStalls
                                + "/" + MAX_CONSECUTIVE_STALLS + ")…"));
                try {
                    Thread.sleep(advanced ? 100L : 750L * consecutiveStalls);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                }
            }
        }
        report(listener, progressText(label, total, total));
    }

    private static long downloadRangeChunk(String url, String userAgent, File dest,
                                           long start, long end, long total, String label,
                                           ProgressListener listener, long[] lastReport)
            throws Exception {
        HttpURLConnection connection = openMedia(url, userAgent);
        connection.setRequestProperty("Range", "bytes=" + start + "-" + end);
        connection.setRequestProperty("Connection", "close");
        try {
            int code = connection.getResponseCode();
            if (code == 416 && start == total) return total;
            if (code >= 400) {
                throw new java.io.IOException("서버 응답 오류 (HTTP " + code + ")");
            }
            if (code != 206) {
                throw new java.io.IOException("서버가 이어받기 요청을 거부했습니다 (HTTP " + code + ")");
            }
            String contentRange = connection.getHeaderField("Content-Range");
            long responseStart = startFromContentRange(contentRange);
            if (responseStart >= 0L && responseStart != start) {
                throw new java.io.IOException("서버의 이어받기 위치가 일치하지 않습니다.");
            }
            long responseTotal = totalFromContentRange(contentRange);
            if (responseTotal > 0L && responseTotal != total) {
                throw new java.io.IOException("갱신된 스트림의 전체 크기가 달라 이어받을 수 없습니다.");
            }

            long current = start;
            long remaining = end - start + 1L;
            try (InputStream in = connection.getInputStream();
                 RandomAccessFile raf = new RandomAccessFile(dest, "rw")) {
                raf.setLength(start);
                raf.seek(start);
                byte[] buffer = new byte[128 * 1024];
                while (remaining > 0L) {
                    int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (read < 0) break;
                    raf.write(buffer, 0, read);
                    current += read;
                    remaining -= read;
                    if (listener != null && current - lastReport[0] >= 1024 * 1024) {
                        lastReport[0] = current;
                        listener.onProgress(progressText(label, current, total));
                    }
                }
            }
            if (remaining > 0L) {
                throw new java.io.EOFException("구간 연결이 예상보다 일찍 종료되었습니다.");
            }
            return current;
        } finally {
            connection.disconnect();
        }
    }

    private static String messageOf(Exception e) {
        return e.getMessage() == null ? "" : e.getMessage().toLowerCase();
    }

    private static boolean isRetryable(Exception e) {
        if (e instanceof java.io.EOFException
                || e instanceof java.net.SocketTimeoutException
                || e instanceof java.net.ConnectException
                || e instanceof java.net.SocketException
                || e instanceof java.net.UnknownHostException) {
            return true;
        }
        String msg = messageOf(e);
        // 403/410 require a freshly resolved stream URL and 429 is an IP throttle;
        // repeating the same request only makes either condition worse.
        return msg.contains("http 408")
                || msg.contains("http 5")
                || msg.contains("timeout")
                || msg.contains("timed out")
                || msg.contains("failed to connect")
                || msg.contains("connection")
                || msg.contains("reset")
                || msg.contains("eof")
                || msg.contains("unexpected end of stream")
                || msg.contains("premature")
                || msg.contains("broken pipe")
                || msg.contains("unable to resolve")
                || msg.contains("network");
    }

    /** True when retrying the same signed media URL cannot succeed. */
    static boolean requiresFreshUrl(Exception e) {
        String msg = messageOf(e);
        return msg.contains("http 403") || msg.contains("http 410");
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
                String range = connection.getHeaderField("Content-Range");
                long expected = totalFromContentRange(range);
                if (expected > 0 && dest.length() == expected) return;
                throw new java.io.IOException("서버 응답 오류 (HTTP 416)");
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
            if (total > 0 && done < total) {
                throw new java.io.EOFException("연결이 예상보다 일찍 종료되었습니다.");
            }
            report(listener, progressText(label, done, total));
        } finally {
            connection.disconnect();
        }
    }

    private static long totalFromContentRange(String value) {
        if (value == null) return -1L;
        int slash = value.lastIndexOf('/');
        if (slash < 0 || slash + 1 >= value.length()) return -1L;
        try {
            return Long.parseLong(value.substring(slash + 1).trim());
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private static long startFromContentRange(String value) {
        if (value == null) return -1L;
        int space = value.indexOf(' ');
        int dash = value.indexOf('-', space + 1);
        if (space < 0 || dash <= space + 1) return -1L;
        try {
            return Long.parseLong(value.substring(space + 1, dash).trim());
        } catch (NumberFormatException ignored) {
            return -1L;
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

    private static void muxToFile(String videoPath, String audioPath, String outPath,
                                  boolean webm) throws Exception {
        MediaExtractor videoEx = new MediaExtractor();
        MediaExtractor audioEx = new MediaExtractor();
        MediaMuxer muxer = new MediaMuxer(outPath, webm
                ? MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                : MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
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
            if (size > 0) return Math.max(size, 4 * 1024 * 1024);
        }
        // 4K VP9 keyframes can exceed the old 4 MB fallback.
        return 16 * 1024 * 1024;
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
