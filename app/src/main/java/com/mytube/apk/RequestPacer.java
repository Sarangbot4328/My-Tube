package com.mytube.apk;

/**
 * Spaces out YouTube HTML/API calls so bursts of search pages + fallbacks look
 * less like a scraper. Downloads also share this gate lightly.
 */
final class RequestPacer {
    private static final long MIN_API_GAP_MS = 450L;
    private static final long MIN_SEARCH_PAGE_GAP_MS = 700L;
    private static final long MIN_DOWNLOAD_GAP_MS = 200L;

    private static long lastApiAt;
    private static long lastSearchPageAt;
    private static long lastDownloadAt;

    private RequestPacer() {}

    static void beforeApiCall() {
        pace(MIN_API_GAP_MS, true);
    }

    static void beforeSearchPage() {
        synchronized (RequestPacer.class) {
            long now = System.currentTimeMillis();
            long waitApi = lastApiAt + MIN_API_GAP_MS - now;
            long waitPage = lastSearchPageAt + MIN_SEARCH_PAGE_GAP_MS - now;
            long wait = Math.max(waitApi, waitPage);
            if (wait > 0) sleep(wait);
            long done = System.currentTimeMillis();
            lastApiAt = done;
            lastSearchPageAt = done;
        }
    }

    static void beforeDownload() {
        synchronized (RequestPacer.class) {
            long now = System.currentTimeMillis();
            long wait = lastDownloadAt + MIN_DOWNLOAD_GAP_MS - now;
            if (wait > 0) sleep(wait);
            lastDownloadAt = System.currentTimeMillis();
        }
    }

    private static void pace(long minGapMs, boolean updateApi) {
        synchronized (RequestPacer.class) {
            long now = System.currentTimeMillis();
            long wait = lastApiAt + minGapMs - now;
            if (wait > 0) sleep(wait);
            if (updateApi) lastApiAt = System.currentTimeMillis();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
