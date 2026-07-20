package com.mytube.apk;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single-worker download queue: only one YouTube save runs at a time; extra taps
 * enqueue and wait (no parallel downloads).
 */
final class DownloadQueue {
    interface Listener {
        void onStatus(String message);

        void onQueueChanged(int pending, boolean active);

        void onItemCompleted(DownloadItem item);

        void onItemFailed(String title, String error);
    }

    static final class Job {
        final String videoUrl;
        final String title;
        final String uploader;
        final String thumbnailUrl;
        final String searchText;
        final ExtractorBridge.DownloadOption option;

        Job(String videoUrl, String title, String uploader, String thumbnailUrl,
            String searchText, ExtractorBridge.DownloadOption option) {
            this.videoUrl = videoUrl;
            this.title = title == null ? "video" : title;
            this.uploader = uploader == null ? "" : uploader;
            this.thumbnailUrl = thumbnailUrl == null ? "" : thumbnailUrl;
            this.searchText = searchText == null ? "" : searchText;
            this.option = option;
        }

        String id() {
            String id = ExtractorBridge.videoIdOf(videoUrl);
            return id.isEmpty() ? MediaDownloader.safeFileName(title, option.ext) : id;
        }
    }

    private final Context app;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinkedList<Job> pending = new LinkedList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Listener listener;
    private Job active;

    DownloadQueue(Context context) {
        this.app = context.getApplicationContext();
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    synchronized int pendingCount() {
        return pending.size() + (running.get() ? 1 : 0);
    }

    synchronized boolean isActive() {
        return running.get();
    }

    synchronized List<String> pendingTitles() {
        List<String> titles = new ArrayList<>();
        if (active != null) titles.add("▶ " + active.title);
        for (Job j : pending) titles.add("· " + j.title);
        return titles;
    }

    /** Enqueue a job. Returns queue position (1 = running or next). */
    synchronized int enqueue(Job job) {
        if (job == null || job.option == null) return -1;
        String id = job.id();
        // Dedupe: already queued / active
        if (active != null && id.equals(active.id())) return 1;
        for (Job j : pending) {
            if (id.equals(j.id())) return pending.size() + (running.get() ? 1 : 0);
        }
        if (DownloadStore.exists(app, id)) {
            main.post(() -> {
                if (listener != null) {
                    listener.onStatus("이미 다운로드됨: " + job.title);
                }
            });
            return -2;
        }
        pending.addLast(job);
        int pos = pending.size() + (running.get() ? 1 : 0);
        notifyQueue();
        pumpLocked();
        return pos;
    }

    synchronized void clearPending() {
        pending.clear();
        notifyQueue();
    }

    void shutdown() {
        worker.shutdownNow();
    }

    private void pumpLocked() {
        if (running.get()) return;
        if (pending.isEmpty()) {
            active = null;
            notifyQueue();
            return;
        }
        active = pending.removeFirst();
        running.set(true);
        notifyQueue();
        final Job job = active;
        worker.execute(() -> runJob(job));
    }

    private void runJob(Job job) {
        MediaDownloader.ProgressListener progress = status -> main.post(() -> {
            if (listener != null) {
                int wait = 0;
                synchronized (DownloadQueue.this) {
                    wait = pending.size();
                }
                String suffix = wait > 0 ? " · 대기 " + wait + "개" : "";
                listener.onStatus(status + " · " + job.title + suffix);
            }
        });
        main.post(() -> {
            if (listener != null) {
                listener.onStatus("대기열 시작 · " + job.title);
            }
        });
        try {
            ExtractorBridge.DownloadOption option = job.option;
            String savedUri;
            try {
                savedUri = MediaDownloader.save(app, option, job.title, progress);
            } catch (Exception first) {
                if (MediaDownloader.requiresFreshUrl(first)) {
                    main.post(() -> {
                        if (listener != null) listener.onStatus("스트림 재발급 후 재시도 · " + job.title);
                    });
                    ExtractorBridge.DownloadOption refreshed =
                            ExtractorBridge.refreshOption(job.videoUrl, option);
                    if (refreshed == null) throw first;
                    option = refreshed;
                    savedUri = MediaDownloader.save(app, option, job.title, progress);
                } else {
                    throw first;
                }
            }
            String id = job.id();
            DownloadItem item = new DownloadItem(
                    id, job.title, job.uploader, savedUri, job.thumbnailUrl, option.label, job.searchText);
            DownloadStore.add(app, item);
            main.post(() -> {
                if (listener != null) {
                    listener.onItemCompleted(item);
                    listener.onStatus("✓ 완료: " + job.title);
                }
            });
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            if (message.contains("HTTP 429")) {
                message = "YouTube가 연결을 일시 제한했습니다 (HTTP 429). "
                        + "잠시 후 다시 시도하거나 로그인 쿠키를 확인하세요.";
            }
            // Shorten noisy network dumps
            if (message.length() > 180) message = message.substring(0, 180) + "…";
            final String shown = message;
            main.post(() -> {
                if (listener != null) {
                    listener.onItemFailed(job.title, shown);
                    listener.onStatus("✗ 실패: " + job.title + " · " + shown);
                }
            });
        } finally {
            synchronized (this) {
                running.set(false);
                active = null;
                pumpLocked();
            }
        }
    }

    private void notifyQueue() {
        final int pendingCount = pending.size();
        final boolean activeNow = running.get();
        main.post(() -> {
            if (listener != null) listener.onQueueChanged(pendingCount, activeNow);
        });
    }
}
