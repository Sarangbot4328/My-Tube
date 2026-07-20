package com.mytube.apk;

import android.os.Build;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ExtractorBridge {
    private static boolean initialized;

    // Well-known public InnerTube WEB API key, used as a fallback when the key
    // can't be scraped from the bootstrap HTML.
    private static final String DEFAULT_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8";
    private static final String DEFAULT_CLIENT_VERSION = "2.20240718.01.00";

    // iOS InnerTube client — its player response exposes a directly-playable HLS
    // manifest (muxed audio+video) with no JS signature deciphering required.
    private static final String IOS_API_KEY = "AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc";
    private static final String IOS_CLIENT_VERSION = "19.29.1";
    private static final String IOS_USER_AGENT =
            "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X)";

    // Per-page item cap (one upstream page is ~20 items; this is a safety bound).
    private static final int MAX_RESULTS = 100;

    // Search result ordering. The token is YouTube's InnerTube "params" value that
    // encodes "type=video + sortBy"; NewPipe doesn't expose sorting, so any non-
    // relevance sort is served through the InnerTube path.
    enum SortOrder {
        RELEVANCE("CAASAhAB"),
        DATE("CAISAhAB"),
        RATING("CAESAhAB");
        final String videoParams;
        SortOrder(String videoParams) { this.videoParams = videoParams; }
    }

    static synchronized void init() {
        if (initialized) return;
        NewPipe.init(new HttpDownloader(), new Localization("ko", "KR"), new ContentCountry("KR"));
        initialized = true;
    }

    static SearchSession newSearch(String query, MainActivity.Tab tab, SortOrder sort) {
        init();
        SortOrder order = sort == null ? SortOrder.RELEVANCE : sort;
        String trimmed = query == null ? "" : query.trim();
        // Empty query + login + default sort → personalized home (FEwhat_to_watch).
        // Explicit search text or non-relevance sort still uses search APIs.
        if (trimmed.isEmpty()
                && order == SortOrder.RELEVANCE
                && CookieStore.hasAuthCookies()) {
            return new SearchSession("", tab, order, true);
        }
        String actualQuery = trimmed.isEmpty() ? defaultQuery(tab) : trimmed;
        if (tab == MainActivity.Tab.SHORTS && !actualQuery.toLowerCase().contains("shorts")) {
            actualQuery = actualQuery + " shorts";
        }
        return new SearchSession(actualQuery, tab, order, false);
    }

    // Holds the cursor for one search so the UI can keep pulling pages as the user
    // scrolls (infinite scroll), instead of loading everything up front.
    static final class SearchSession {
        private static final int MODE_INIT = 0;
        private static final int MODE_NEWPIPE = 1;
        private static final int MODE_INNERTUBE = 2;
        private static final int MODE_HOME = 3;
        private static final int MODE_DONE = 4;

        private String actualQuery;
        private final MainActivity.Tab tab;
        private final SortOrder sort;
        private boolean homeFeed;
        private int mode = MODE_INIT;

        // NewPipe cursor.
        private SearchQueryHandler queryHandler;
        private Page nextPage;

        // InnerTube cursor (search or home browse).
        private String itUrl;
        private String itClientVersion = "";
        private String apiKeyCache = "";
        private Map<String, String> itHeaders;
        private String continuationToken = "";
        private String bootstrapHtml = "";

        SearchSession(String actualQuery, MainActivity.Tab tab, SortOrder sort, boolean homeFeed) {
            this.actualQuery = actualQuery;
            this.tab = tab;
            this.sort = sort;
            this.homeFeed = homeFeed;
        }

        /** True when listing the logged-in account's YouTube home recommendations. */
        boolean isHomeFeed() {
            return homeFeed;
        }

        boolean hasMore() {
            switch (mode) {
                case MODE_INIT: return true;
                case MODE_NEWPIPE: return nextPage != null;
                case MODE_INNERTUBE:
                case MODE_HOME: return !continuationToken.isEmpty();
                default: return false;
            }
        }

        // Fetches and returns the next page of results (empty list when exhausted).
        synchronized List<TubeItem> loadMore() throws Exception {
            switch (mode) {
                case MODE_INIT: return initFetch();
                case MODE_NEWPIPE:
                    RequestPacer.beforeSearchPage();
                    return newPipeNext();
                case MODE_INNERTUBE:
                    RequestPacer.beforeSearchPage();
                    return innerTubeNext();
                case MODE_HOME:
                    RequestPacer.beforeSearchPage();
                    return homeNext();
                default: return new ArrayList<>();
            }
        }

        private List<TubeItem> initFetch() throws Exception {
            if (homeFeed) {
                try {
                    RequestPacer.beforeSearchPage();
                    List<TubeItem> home = homeFirst();
                    if (!home.isEmpty()) {
                        mode = MODE_HOME;
                        return home;
                    }
                } catch (Exception ignored) {
                    // fall back to keyword search below
                }
                // Login present but home empty/failed — generic popular search.
                homeFeed = false;
                actualQuery = defaultQuery(tab);
                mode = MODE_INIT;
            }
            // Sorting isn't available through NewPipe, so only use it for the
            // default relevance order; otherwise go straight to InnerTube.
            // Avoid hammering every fallback immediately — that pattern burns IP
            // reputation faster than PC's single yt-dlp path.
            if (sort == SortOrder.RELEVANCE) {
                try {
                    RequestPacer.beforeSearchPage();
                    List<TubeItem> items = newPipeFirst();
                    if (!items.isEmpty()) { mode = MODE_NEWPIPE; return items; }
                } catch (Exception ignored) {
                    // fall through to InnerTube after a short gap
                    RequestPacer.beforeSearchPage();
                }
            }
            try {
                RequestPacer.beforeSearchPage();
                List<TubeItem> items = innerTubeFirst();
                if (!items.isEmpty()) { mode = MODE_INNERTUBE; return items; }
            } catch (Exception ignored) {
                // only scrape HTML as last resort; skip if we already have bootstrap
            }
            mode = MODE_DONE;
            if (bootstrapHtml != null && !bootstrapHtml.isEmpty()) {
                try {
                    return htmlScrape();
                } catch (Exception ignored) {
                    return new ArrayList<>();
                }
            }
            return new ArrayList<>();
        }

        private void ensureWebClient() throws Exception {
            if (itHeaders != null && itUrl != null && !itClientVersion.isEmpty()) return;
            try {
                bootstrapHtml = httpGet("https://www.youtube.com/");
            } catch (Exception ignored) {
                bootstrapHtml = "";
            }
            String apiKey = match(bootstrapHtml, "\"INNERTUBE_API_KEY\":\"([^\"]+)\"");
            itClientVersion = match(bootstrapHtml, "\"INNERTUBE_CLIENT_VERSION\":\"([^\"]+)\"");
            if (apiKey.isEmpty()) apiKey = DEFAULT_API_KEY;
            if (itClientVersion.isEmpty()) itClientVersion = DEFAULT_CLIENT_VERSION;
            itHeaders = new HashMap<>();
            itHeaders.put("X-YouTube-Client-Name", "1");
            itHeaders.put("X-YouTube-Client-Version", itClientVersion);
            // itUrl set by caller (search vs browse)
            this.apiKeyCache = apiKey;
        }

        private String webClientContextJson() {
            return "\"context\":{\"client\":{"
                    + "\"clientName\":\"WEB\","
                    + "\"clientVersion\":\"" + jsonEscape(itClientVersion) + "\","
                    + "\"hl\":\"ko\",\"gl\":\"KR\","
                    + "\"userAgent\":\"" + jsonEscape(HttpDownloader.BROWSER_UA) + "\""
                    + "},\"user\":{\"lockedSafetyMode\":false}}";
        }

        private List<TubeItem> homeFirst() throws Exception {
            ensureWebClient();
            itUrl = "https://www.youtube.com/youtubei/v1/browse?key="
                    + apiKeyCache + "&prettyPrint=false";
            String payload = "{"
                    + webClientContextJson() + ","
                    + "\"browseId\":\"FEwhat_to_watch\""
                    + "}";
            String response = httpPost(itUrl, payload, itHeaders, true);
            List<TubeItem> items = parseFeedItems(response, tab);
            continuationToken = extractContinuationToken(response);
            // Home often nests videos sparsely; pull one more page if thin.
            if (items.size() < 12 && !continuationToken.isEmpty()) {
                List<TubeItem> more = homeNext();
                items = mergeDedupe(items, more);
            }
            return items;
        }

        private List<TubeItem> homeNext() throws Exception {
            if (continuationToken.isEmpty()) { mode = MODE_DONE; return new ArrayList<>(); }
            String payload = "{"
                    + webClientContextJson() + ","
                    + "\"continuation\":\"" + jsonEscape(continuationToken) + "\""
                    + "}";
            String response;
            try {
                response = httpPost(itUrl, payload, itHeaders, true);
            } catch (Exception e) {
                continuationToken = "";
                mode = MODE_DONE;
                return new ArrayList<>();
            }
            List<TubeItem> items = parseFeedItems(response, tab);
            continuationToken = extractContinuationToken(response);
            if (items.isEmpty() && continuationToken.isEmpty()) mode = MODE_DONE;
            return items;
        }

        private List<TubeItem> newPipeFirst() throws Exception {
            List<String> filters = Arrays.asList(tab == MainActivity.Tab.CHANNELS
                    ? YoutubeSearchQueryHandlerFactory.CHANNELS
                    : YoutubeSearchQueryHandlerFactory.VIDEOS);
            queryHandler = ServiceList.YouTube.getSearchQHFactory().fromQuery(actualQuery, filters, "");
            SearchInfo info = SearchInfo.getInfo(ServiceList.YouTube.getSearchExtractor(queryHandler));
            nextPage = info.getNextPage();
            return mapNewPipeItems(info.getRelatedItems(), tab);
        }

        private List<TubeItem> newPipeNext() throws Exception {
            if (nextPage == null) { mode = MODE_DONE; return new ArrayList<>(); }
            ListExtractor.InfoItemsPage<InfoItem> more =
                    SearchInfo.getMoreItems(ServiceList.YouTube, queryHandler, nextPage);
            nextPage = more.getNextPage();
            return mapNewPipeItems(more.getItems(), tab);
        }

        private List<TubeItem> innerTubeFirst() throws Exception {
            ensureWebClient();
            // Prefer results HTML for key scrape when searching (richer page).
            try {
                String resultsHtml = httpGet("https://www.youtube.com/results?search_query="
                        + URLEncoder.encode(actualQuery, StandardCharsets.UTF_8.name()));
                if (resultsHtml != null && !resultsHtml.isEmpty()) bootstrapHtml = resultsHtml;
                String apiKey = match(bootstrapHtml, "\"INNERTUBE_API_KEY\":\"([^\"]+)\"");
                String ver = match(bootstrapHtml, "\"INNERTUBE_CLIENT_VERSION\":\"([^\"]+)\"");
                if (!apiKey.isEmpty()) apiKeyCache = apiKey;
                if (!ver.isEmpty()) {
                    itClientVersion = ver;
                    itHeaders.put("X-YouTube-Client-Version", itClientVersion);
                }
            } catch (Exception ignored) {
                // keep homepage bootstrap
            }
            itUrl = "https://www.youtube.com/youtubei/v1/search?key="
                    + apiKeyCache + "&prettyPrint=false";

            String params = tab == MainActivity.Tab.CHANNELS ? "EgIQAg==" : sort.videoParams;
            String payload = "{"
                    + webClientContextJson() + ","
                    + "\"query\":\"" + jsonEscape(actualQuery) + "\","
                    + "\"params\":\"" + params + "\""
                    + "}";
            String response = httpPost(itUrl, payload, itHeaders, true);
            List<TubeItem> items = parseRendererBlocks(response, tab, actualQuery);
            sortItems(items);
            continuationToken = extractContinuationToken(response);
            return items;
        }

        private List<TubeItem> innerTubeNext() throws Exception {
            if (continuationToken.isEmpty()) { mode = MODE_DONE; return new ArrayList<>(); }
            String payload = "{"
                    + webClientContextJson() + ","
                    + "\"continuation\":\"" + jsonEscape(continuationToken) + "\""
                    + "}";
            String response;
            try {
                response = httpPost(itUrl, payload, itHeaders, true);
            } catch (Exception e) {
                continuationToken = "";
                mode = MODE_DONE;
                return new ArrayList<>();
            }
            List<TubeItem> items = parseRendererBlocks(response, tab, actualQuery);
            sortItems(items);
            continuationToken = extractContinuationToken(response);
            return items;
        }

        private List<TubeItem> htmlScrape() throws Exception {
            String html = bootstrapHtml;
            if (html.isEmpty()) {
                html = httpGet("https://www.youtube.com/results?search_query="
                        + URLEncoder.encode(actualQuery, StandardCharsets.UTF_8.name()));
            }
            List<TubeItem> items = scrapeResultsHtml(html, tab, actualQuery);
            sortItems(items);
            return items;
        }

        private void sortItems(List<TubeItem> items) {
            if (sort == SortOrder.DATE) {
                items.sort(Comparator.comparingLong(item -> item.publishedAgeSeconds));
            }
        }
    }

    private static List<TubeItem> mapNewPipeItems(List<InfoItem> related, MainActivity.Tab tab) {
        List<TubeItem> items = new ArrayList<>();
        for (InfoItem item : related) {
            if (item instanceof StreamInfoItem) {
                StreamInfoItem stream = (StreamInfoItem) item;
                if (tab == MainActivity.Tab.SHORTS && !stream.isShortFormContent()) continue;
                if (tab == MainActivity.Tab.VIDEOS && stream.isShortFormContent()) continue;
                String videoId = extractVideoId(stream.getUrl());
                String thumb = videoId.isEmpty() ? bestImageUrl(stream.getThumbnails()) : youtubeThumbnail(videoId);
                items.add(new TubeItem(
                        stream.getName(),
                        stream.getUploaderName() + " · " + formatDuration(stream.getDuration()),
                        stream.getUrl(),
                        thumb,
                        true,
                        stream.isShortFormContent()
                ));
            } else if (tab == MainActivity.Tab.CHANNELS) {
                items.add(new TubeItem(
                        item.getName(),
                        "채널",
                        item.getUrl(),
                        bestImageUrl(item.getThumbnails()),
                        false,
                        false
                ));
            }
            if (items.size() >= MAX_RESULTS) break;
        }
        return items;
    }

    // Canonical YouTube thumbnail for a video id. hqdefault is always present and,
    // center-cropped to 16:9 in the UI, matches what youtube.com shows.
    private static String youtubeThumbnail(String videoId) {
        return videoId == null || videoId.isEmpty()
                ? ""
                : "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
    }

    static PlaybackData resolve(String url) throws Exception {
        init();
        // Login cookies help rate-limits, but some player paths return empty
        // streams when a WEB session is attached. Try with session, then guest.
        PlaybackData data = resolveOnce(url);
        if (data != null) return data;
        if (CookieStore.hasAuthCookies()) {
            CookieStore.setGuestMode(true);
            try {
                data = resolveOnce(url);
                if (data != null) return data;
            } finally {
                CookieStore.setGuestMode(false);
            }
        }
        throw new IllegalStateException(
                "재생 가능한 스트림을 찾지 못했습니다. YouTube가 일시적으로 차단했을 수 있어요 — "
                        + "잠시 후 다시 시도하거나 Wi-Fi/데이터를 전환해 보세요. "
                        + "로그인을 썼다면 설정에서 쿠키를 지우고 다시 로그인해 보세요.");
    }

    private static PlaybackData resolveOnce(String url) {
        // 1) Bundled NewPipe extractor.
        try {
            PlaybackData data = newPipeResolve(url);
            if (data != null) return data;
        } catch (Exception ignored) {
            // fall through
        }
        // 2) InnerTube player clients (ANDROID_VR / TV / iOS) — HLS/progressive.
        try {
            return innerTubeResolve(url);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static PlaybackData newPipeResolve(String url) throws Exception {
        StreamInfo info = StreamInfo.getInfo(ServiceList.YouTube, url);
        String mediaUrl = firstNonEmpty(info.getHlsUrl(), info.getDashMpdUrl());
        boolean manifest = mediaUrl != null && !mediaUrl.isEmpty();

        if (mediaUrl == null || mediaUrl.isEmpty()) {
            VideoStream best = info.getVideoStreams().stream()
                    .filter(stream -> !stream.isVideoOnly() && stream.isUrl())
                    .max(Comparator.comparingInt(VideoStream::getHeight))
                    .orElse(null);
            if (best == null) {
                best = info.getVideoOnlyStreams().stream()
                        .filter(VideoStream::isUrl)
                        .max(Comparator.comparingInt(VideoStream::getHeight))
                        .orElse(null);
            }
            if (best != null) mediaUrl = best.getContent();
        }

        if (mediaUrl == null || mediaUrl.isEmpty()) return null;

        String description = info.getDescription() == null ? "" : info.getDescription().getContent();
        String uploadDate = info.getTextualUploadDate();
        List<String> tags = info.getTags() == null ? new ArrayList<>() : info.getTags();
        String likeCount = info.getLikeCount() >= 0 ? formatCount(info.getLikeCount()) : "";
        return new PlaybackData(
                info.getName(),
                info.getUploaderName(),
                mediaUrl,
                manifest,
                info.getViewCount(),
                likeCount,
                uploadDate,
                description,
                tags
        );
    }

    // One InnerTube "player" client. YouTube periodically blocks individual
    // clients, so we try several and use whichever still returns streams.
    private static final class PlayerClient {
        final String name, version, apiKey, userAgent, clientNameHeader, extraContext;
        PlayerClient(String name, String version, String apiKey, String userAgent,
                     String clientNameHeader, String extraContext) {
            this.name = name;
            this.version = version;
            this.apiKey = apiKey;
            this.userAgent = userAgent;
            this.clientNameHeader = clientNameHeader;
            this.extraContext = extraContext;   // trailing-comma JSON fragment or ""
        }
    }

    private static final String ANDROID_API_KEY = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w";
    // Prefer clients similar to PC yt-dlp HQ_CLIENT_PRIORITY (android_vr / tv first).
    private static final PlayerClient[] PLAYER_CLIENTS = {
            new PlayerClient("ANDROID_VR", "1.60.19", ANDROID_API_KEY,
                    "com.google.android.apps.youtube.vr.oculus/1.60.19 "
                            + "(Linux; U; Android 12L; en_US; Quest 3) gzip", "28",
                    "\"androidSdkVersion\":32,\"deviceModel\":\"Quest 3\","),
            new PlayerClient("TVHTML5", "7.20240724.13.00", DEFAULT_API_KEY,
                    "Mozilla/5.0 (SMART-TV; Linux; Tizen 6.0) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) 76.0.3809.146 TV Safari/537.36", "7", ""),
            new PlayerClient("IOS", IOS_CLIENT_VERSION, IOS_API_KEY, IOS_USER_AGENT, "5",
                    "\"deviceModel\":\"iPhone16,2\","),
    };

    private static String playerResponse(String videoId, PlayerClient client) throws Exception {
        String payload = "{"
                + "\"context\":{\"client\":{"
                + "\"clientName\":\"" + client.name + "\","
                + "\"clientVersion\":\"" + client.version + "\","
                + client.extraContext
                + "\"hl\":\"ko\",\"gl\":\"KR\""
                + "}},"
                + "\"videoId\":\"" + jsonEscape(videoId) + "\","
                + "\"contentCheckOk\":true,\"racyCheckOk\":true"
                + "}";
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", client.userAgent);
        headers.put("X-YouTube-Client-Name", client.clientNameHeader);
        headers.put("X-YouTube-Client-Version", client.version);
        return httpPost(
                "https://www.youtube.com/youtubei/v1/player?key=" + client.apiKey + "&prettyPrint=false",
                payload,
                headers
        );
    }

    private static boolean isPlayable(String response) {
        String status = match(response, "\"playabilityStatus\":\\{\"status\":\"([^\"]+)\"");
        return status.isEmpty() || "OK".equalsIgnoreCase(status);
    }

    // A downloadable quality. When muxed==false, videoUrl is a complete progressive
    // file. When muxed==true, the separate video and audio streams are combined into
    // either MP4 (H.264/AAC) or WebM (VP9/Opus). YouTube normally uses WebM for 4K.
    // userAgent must match the client that issued the stream URL (403 otherwise).
    static final class DownloadOption {
        final String label;      // e.g. "1080p"
        final String videoUrl;
        final String audioUrl;   // null for progressive
        final String ext;        // "mp4" / "webm"
        final String mime;
        final boolean muxed;
        final int height;
        final String userAgent;
        DownloadOption(String label, String videoUrl, String audioUrl, String ext,
                       String mime, boolean muxed, int height, String userAgent) {
            this.label = label;
            this.videoUrl = videoUrl;
            this.audioUrl = audioUrl;
            this.ext = ext;
            this.mime = mime;
            this.muxed = muxed;
            this.height = height;
            this.userAgent = userAgent == null || userAgent.isEmpty()
                    ? HttpDownloader.BROWSER_UA : userAgent;
        }
    }

    // Lists downloadable qualities: progressive (single file), adaptive MP4, and
    // adaptive VP9 WebM up to 4K. Android can mux VP9 into WebM from API 24 and
    // Opus audio into WebM from API 29, so Opus-based 4K is offered on API 29+.
    static List<DownloadOption> downloadOptions(String url) throws Exception {
        init();
        // Prefer NewPipe: it resolves de-throttled, deciphered stream URLs for the
        // same videos we can play (the iOS client omits direct URLs for many official
        // videos, which is why downloads showed "no qualities"). Fall back to players.
        try {
            RequestPacer.beforeApiCall();
            List<DownloadOption> options = newPipeDownloadOptions(url);
            if (!options.isEmpty()) return options;
        } catch (Exception ignored) {
            // fall through
        }
        return playerDownloadOptions(url);
    }

    /** Re-resolve a single height after 403 / expired URL (PC-style retry). */
    static DownloadOption refreshOption(String url, DownloadOption previous) throws Exception {
        if (previous == null) return null;
        List<DownloadOption> options = downloadOptions(url);
        DownloadOption best = null;
        int bestDiff = Integer.MAX_VALUE;
        for (DownloadOption o : options) {
            if (o.height == previous.height && o.muxed == previous.muxed) return o;
            int diff = Math.abs(o.height - previous.height);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = o;
            }
        }
        return best;
    }

    /**
     * Re-resolves the exact media representations used by an in-progress download.
     * Matching itags prevents resumed bytes from two different encodes being mixed.
     */
    static DownloadOption refreshSameOption(String url, DownloadOption previous) throws Exception {
        if (previous == null) return null;
        List<DownloadOption> options = downloadOptions(url);
        String previousVideoItag = itagOf(previous.videoUrl);
        String previousAudioItag = itagOf(previous.audioUrl);

        if (!previousVideoItag.isEmpty()) {
            for (DownloadOption option : options) {
                if (!previousVideoItag.equals(itagOf(option.videoUrl))) continue;
                if (!previousAudioItag.isEmpty()
                        && !previousAudioItag.equals(itagOf(option.audioUrl))) continue;
                return option;
            }
            return null;
        }

        // Direct media URLs normally contain an itag. Keep a conservative fallback
        // for clients that omit it while preserving quality, container and layout.
        for (DownloadOption option : options) {
            if (option.height == previous.height
                    && option.muxed == previous.muxed
                    && option.ext.equalsIgnoreCase(previous.ext)) {
                return option;
            }
        }
        return null;
    }

    private static String itagOf(String mediaUrl) {
        if (mediaUrl == null || mediaUrl.isEmpty()) return "";
        Matcher matcher = Pattern.compile(
                "(?:[?&]|%26)itag(?:=|%3[dD])(\\d+)", Pattern.CASE_INSENSITIVE)
                .matcher(mediaUrl);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static List<DownloadOption> newPipeDownloadOptions(String url) throws Exception {
        StreamInfo info = StreamInfo.getInfo(ServiceList.YouTube, url);
        Map<Integer, DownloadOption> byHeight = new LinkedHashMap<>();
        String ua = HttpDownloader.BROWSER_UA;

        // Progressive muxed streams already contain audio + video.
        for (VideoStream vs : info.getVideoStreams()) {
            if (!vs.isUrl() || vs.isVideoOnly()) continue;
            String streamUrl = vs.getContent();
            if (streamUrl == null || streamUrl.isEmpty()) continue;
            int height = parseHeight(vs.getResolution());
            String ext = suffixOf(vs.getFormat());
            byHeight.put(height, new DownloadOption(
                    resolutionLabel(vs.getResolution(), height), streamUrl, null,
                    ext, "webm".equalsIgnoreCase(ext) ? "video/webm" : "video/mp4",
                    false, height, ua));
        }

        AudioStream bestM4aAudio = null;
        AudioStream bestWebmAudio = null;
        for (AudioStream as : info.getAudioStreams()) {
            if (!as.isUrl() || as.getContent() == null || as.getContent().isEmpty()) continue;
            if (as.getFormat() == MediaFormat.M4A) {
                if (bestM4aAudio == null
                        || as.getAverageBitrate() > bestM4aAudio.getAverageBitrate()) {
                    bestM4aAudio = as;
                }
            } else if (isMuxableWebmAudio(as)) {
                if (bestWebmAudio == null
                        || as.getAverageBitrate() > bestWebmAudio.getAverageBitrate()) {
                    bestWebmAudio = as;
                }
            }
        }

        for (VideoStream vs : info.getVideoOnlyStreams()) {
            if (!vs.isUrl()) continue;
            String streamUrl = vs.getContent();
            if (streamUrl == null || streamUrl.isEmpty()) continue;
            int height = parseHeight(vs.getResolution());
            if (byHeight.containsKey(height)) continue;   // progressive already covers it

            if (vs.getFormat() == MediaFormat.MPEG_4 && bestM4aAudio != null) {
                byHeight.put(height, new DownloadOption(
                        resolutionLabel(vs.getResolution(), height), streamUrl, bestM4aAudio.getContent(),
                        "mp4", "video/mp4", true, height, ua));
            } else if (vs.getFormat() == MediaFormat.WEBM
                    && bestWebmAudio != null && isVp8OrVp9(vs.getCodec())) {
                byHeight.put(height, new DownloadOption(
                        resolutionLabel(vs.getResolution(), height), streamUrl, bestWebmAudio.getContent(),
                        "webm", "video/webm", true, height, ua));
            }
        }

        List<DownloadOption> options = new ArrayList<>(byHeight.values());
        options.sort((a, b) -> Integer.compare(b.height, a.height));
        return options;
    }

    private static boolean isMuxableWebmAudio(AudioStream stream) {
        MediaFormat format = stream.getFormat();
        if (format != MediaFormat.WEBMA && format != MediaFormat.WEBMA_OPUS
                && format != MediaFormat.OPUS) {
            return false;
        }
        String codec = stream.getCodec() == null ? "" : stream.getCodec().toLowerCase(Locale.US);
        boolean opus = format == MediaFormat.WEBMA_OPUS || format == MediaFormat.OPUS
                || codec.contains("opus");
        return !opus || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }

    private static boolean isVp8OrVp9(String codec) {
        if (codec == null) return false;
        String value = codec.toLowerCase(Locale.US);
        return value.startsWith("vp8") || value.startsWith("vp08")
                || value.startsWith("vp9") || value.startsWith("vp09");
    }

    private static int parseHeight(String resolution) {
        if (resolution == null) return 0;
        Matcher matcher = Pattern.compile("(\\d+)").matcher(resolution);
        return matcher.find() ? intOf(matcher.group(1)) : 0;
    }

    private static String resolutionLabel(String resolution, int height) {
        if (resolution != null && !resolution.isEmpty()) return resolution;
        return height > 0 ? height + "p" : "기본 화질";
    }

    private static String suffixOf(MediaFormat format) {
        if (format == null) return "mp4";
        String suffix = format.getSuffix();
        return suffix == null || suffix.isEmpty() ? "mp4" : suffix;
    }

    private static List<DownloadOption> playerDownloadOptions(String url) throws Exception {
        String videoId = extractVideoId(url);
        if (videoId.isEmpty()) return new ArrayList<>();
        // Try each client; use the first that yields downloadable qualities.
        for (PlayerClient client : PLAYER_CLIENTS) {
            try {
                RequestPacer.beforeApiCall();
                String response = playerResponse(videoId, client);
                if (!isPlayable(response)) continue;
                List<DownloadOption> options = parseDownloadOptions(response, client.userAgent);
                if (!options.isEmpty()) return options;
            } catch (Exception ignored) {
                // try the next client
            }
        }
        return new ArrayList<>();
    }

    private static List<DownloadOption> parseDownloadOptions(String response, String userAgent) {
        // Height -> option, keeping progressive (needs no muxing) when available.
        Map<Integer, DownloadOption> byHeight = new LinkedHashMap<>();

        for (String obj : jsonArrayObjects(response, "formats")) {
            String streamUrl = cleanUrl(match(obj, "\"url\":\"([^\"]+)\""));
            if (streamUrl.isEmpty()) continue;
            int height = intOf(match(obj, "\"height\":(\\d+)"));
            String mime = clean(match(obj, "\"mimeType\":\"([^\"]+)\""));
            String ext = mime.contains("webm") ? "webm" : "mp4";
            byHeight.put(height, new DownloadOption(
                    qualityLabel(obj, height), streamUrl, null, ext,
                    "webm".equals(ext) ? "video/webm" : "video/mp4",
                    false, height, userAgent));
        }

        // Best audio track for each output container.
        String bestM4aAudioUrl = "";
        int bestM4aAudioBitrate = -1;
        String bestWebmAudioUrl = "";
        int bestWebmAudioBitrate = -1;
        for (String obj : jsonArrayObjects(response, "adaptiveFormats")) {
            String mime = clean(match(obj, "\"mimeType\":\"([^\"]+)\""));
            String audioUrl = cleanUrl(match(obj, "\"url\":\"([^\"]+)\""));
            if (audioUrl.isEmpty()) continue;
            int bitrate = intOf(match(obj, "\"bitrate\":(\\d+)"));
            if (mime.startsWith("audio/mp4") && bitrate > bestM4aAudioBitrate) {
                bestM4aAudioBitrate = bitrate;
                bestM4aAudioUrl = audioUrl;
            } else if (mime.startsWith("audio/webm")
                    && webmAudioSupportedByPlatform(obj) && bitrate > bestWebmAudioBitrate) {
                bestWebmAudioBitrate = bitrate;
                bestWebmAudioUrl = audioUrl;
            }
        }

        for (String obj : jsonArrayObjects(response, "adaptiveFormats")) {
            String mime = clean(match(obj, "\"mimeType\":\"([^\"]+)\""));
            String videoUrl = cleanUrl(match(obj, "\"url\":\"([^\"]+)\""));
            if (videoUrl.isEmpty()) continue;
            int height = intOf(match(obj, "\"height\":(\\d+)"));
            if (height <= 0 || byHeight.containsKey(height)) continue;

            if (mime.startsWith("video/mp4") && obj.contains("avc1")
                    && !bestM4aAudioUrl.isEmpty()) {
                byHeight.put(height, new DownloadOption(
                        qualityLabel(obj, height), videoUrl, bestM4aAudioUrl,
                        "mp4", "video/mp4", true, height, userAgent));
            } else if (mime.startsWith("video/webm") && containsVp8OrVp9Codec(obj)
                    && !bestWebmAudioUrl.isEmpty()) {
                byHeight.put(height, new DownloadOption(
                        qualityLabel(obj, height), videoUrl, bestWebmAudioUrl,
                        "webm", "video/webm", true, height, userAgent));
            }
        }

        List<DownloadOption> options = new ArrayList<>(byHeight.values());
        options.sort((a, b) -> Integer.compare(b.height, a.height));
        return options;
    }

    private static boolean webmAudioSupportedByPlatform(String formatJson) {
        boolean opus = formatJson != null && formatJson.toLowerCase(Locale.US).contains("opus");
        return !opus || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }

    private static boolean containsVp8OrVp9Codec(String formatJson) {
        if (formatJson == null) return false;
        String value = formatJson.toLowerCase(Locale.US);
        return value.contains("vp8") || value.contains("vp08")
                || value.contains("vp9") || value.contains("vp09");
    }

    private static String qualityLabel(String obj, int height) {
        String label = match(obj, "\"qualityLabel\":\"([^\"]+)\"");
        if (!label.isEmpty()) return label;
        return height > 0 ? height + "p" : "기본 화질";
    }

    private static int intOf(String value) {
        if (value == null || value.isEmpty()) return 0;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // Extracts each top-level {..} object from the JSON array value of KEY,
    // correctly handling nested objects/arrays and quoted strings.
    private static List<String> jsonArrayObjects(String text, String key) {
        List<String> objects = new ArrayList<>();
        int keyIndex = text.indexOf("\"" + key + "\":[");
        if (keyIndex < 0) return objects;
        int i = keyIndex + key.length() + 4;   // position just after the '['
        int arrayDepth = 1;
        int objectDepth = 0;
        int objectStart = -1;
        boolean inString = false;
        boolean escaped = false;
        for (; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\') { escaped = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '{') {
                if (objectDepth == 0) objectStart = i;
                objectDepth++;
            } else if (c == '}') {
                objectDepth--;
                if (objectDepth == 0 && objectStart >= 0) {
                    objects.add(text.substring(objectStart, i + 1));
                    objectStart = -1;
                }
            } else if (c == '[') {
                arrayDepth++;
            } else if (c == ']') {
                arrayDepth--;
                if (arrayDepth == 0) break;
            }
        }
        return objects;
    }

    private static PlaybackData innerTubeResolve(String url) throws Exception {
        String videoId = extractVideoId(url);
        if (videoId.isEmpty()) return null;

        // Try each client and use the first that returns a playable stream.
        for (PlayerClient client : PLAYER_CLIENTS) {
            try {
                String response = playerResponse(videoId, client);
                if (!isPlayable(response)) continue;
                PlaybackData data = parsePlayback(response);
                if (data != null) return data;
            } catch (Exception ignored) {
                // try the next client
            }
        }
        return null;
    }

    private static PlaybackData parsePlayback(String response) {
        String title = matchJsonString(response, "title");
        String uploader = matchJsonString(response, "author");
        String description = matchJsonString(response, "shortDescription");
        String viewCountStr = match(response, "\"viewCount\":\"(\\d+)\"");
        long viewCount = viewCountStr.isEmpty() ? -1 : parseLong(viewCountStr);
        String uploadDate = match(response, "\"publishDate\":\"([0-9\\-]+)\"");
        List<String> tags = extractKeywords(response);

        // Prefer the HLS manifest: one URL, muxed audio+video, adaptive, ad-free.
        String hls = cleanUrl(match(response, "\"hlsManifestUrl\":\"([^\"]+)\""));
        if (!hls.isEmpty()) {
            return new PlaybackData(title, uploader, hls, true, viewCount, "", uploadDate, description, tags);
        }

        // Otherwise a progressive muxed stream (has both audio and video in one URL).
        String progressive = firstProgressiveUrl(response);
        if (!progressive.isEmpty()) {
            return new PlaybackData(title, uploader, progressive, false, viewCount, "", uploadDate, description, tags);
        }

        return null;
    }

    // Reads videoDetails.keywords (["a","b",...]) into a list, capped for display.
    private static List<String> extractKeywords(String response) {
        List<String> tags = new ArrayList<>();
        int start = response.indexOf("\"keywords\":[");
        if (start < 0) return tags;
        int end = response.indexOf(']', start);
        if (end < 0) return tags;
        Matcher matcher = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"")
                .matcher(response.substring(start + "\"keywords\":[".length(), end));
        while (matcher.find() && tags.size() < 15) {
            String tag = clean(matcher.group(1));
            if (!tag.isEmpty()) tags.add(tag);
        }
        return tags;
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // "1234567" -> "1,234,567"
    static String formatCount(long count) {
        if (count < 0) return "";
        return String.format("%,d", count);
    }

    // Captures a JSON string value for KEY, correctly handling escaped characters.
    private static String matchJsonString(String text, String key) {
        Matcher matcher = Pattern.compile("\"" + key + "\":\"((?:\\\\.|[^\"\\\\])*)\"", Pattern.DOTALL)
                .matcher(text);
        return matcher.find() ? clean(matcher.group(1)) : "";
    }

    static String videoIdOf(String url) {
        return extractVideoId(url);
    }

    private static String extractVideoId(String url) {
        if (url == null) return "";
        String id = match(url, "[?&]v=([A-Za-z0-9_-]{11})");
        if (!id.isEmpty()) return id;
        id = match(url, "/shorts/([A-Za-z0-9_-]{11})");
        if (!id.isEmpty()) return id;
        id = match(url, "youtu\\.be/([A-Za-z0-9_-]{11})");
        if (!id.isEmpty()) return id;
        return match(url, "/embed/([A-Za-z0-9_-]{11})");
    }

    // Picks the last (highest-quality) muxed progressive stream URL from the
    // player response's "formats" array — adaptiveFormats are video- or
    // audio-only, so we deliberately stop before them.
    private static String firstProgressiveUrl(String response) {
        int start = response.indexOf("\"formats\":[");
        if (start < 0) return "";
        int end = response.indexOf("\"adaptiveFormats\"", start);
        String section = end > start ? response.substring(start, end) : response.substring(start);
        Matcher matcher = Pattern.compile("\"url\":\"([^\"]+)\"").matcher(section);
        String best = "";
        while (matcher.find()) best = matcher.group(1);
        return cleanUrl(best);
    }

    private static String defaultQuery(MainActivity.Tab tab) {
        switch (tab) {
            case HOME:
                return "한국 인기 영상";
            case SHORTS:
                return "인기 shorts";
            case CHANNELS:
                return "인기 채널";
            case VIDEOS:
            case YOUTUBE:
            default:
                return "인기 동영상";
        }
    }

    private static String bestImageUrl(List<Image> images) {
        if (images == null || images.isEmpty()) return "";
        Image best = images.stream()
                .max(Comparator.comparingInt(image -> Math.max(image.getWidth(), image.getHeight())))
                .orElse(images.get(0));
        return best.getUrl();
    }

    private static String firstNonEmpty(String first, String second) {
        if (first != null && !first.isEmpty()) return first;
        if (second != null && !second.isEmpty()) return second;
        return "";
    }

    private static String formatDuration(long seconds) {
        if (seconds <= 0) return "";
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, s);
        return String.format("%d:%02d", m, s);
    }

    // Last-resort: scrape the search results HTML page directly.
    private static List<TubeItem> scrapeResultsHtml(String html, MainActivity.Tab tab, String query) {
        List<TubeItem> items = new ArrayList<>();
        if (html == null || html.isEmpty()) return items;

        if (tab == MainActivity.Tab.CHANNELS) {
            for (String block : extractJsonObjects(html, "\"channelRenderer\":{")) {
                String id = match(block, "\"channelId\":\"([^\"]+)\"");
                String title = clean(match(block, "\"title\":\\{\"simpleText\":\"(.*?)\""));
                if (title.isEmpty()) title = clean(match(block, "\"title\":\\{\"runs\":\\[\\{\"text\":\"(.*?)\""));
                if (id.isEmpty() || title.isEmpty()) continue;
                items.add(new TubeItem(
                        title,
                        "채널",
                        "https://www.youtube.com/channel/" + id,
                        bestThumbnailFromBlock(block),
                        false,
                        false
                ));
                if (items.size() >= 30) break;
            }
            return items;
        }

        for (String block : extractJsonObjects(html, "\"videoRenderer\":{")) {
            String id = match(block, "\"videoId\":\"([^\"]+)\"");
            String title = clean(match(block, "\"title\":\\{\"runs\":\\[\\{\"text\":\"(.*?)\""));
            String channel = clean(match(block, "\"ownerText\":\\{\"runs\":\\[\\{\"text\":\"(.*?)\""));
            String length = clean(match(block, "\"lengthText\":\\{\"simpleText\":\"(.*?)\""));
            String published = clean(match(block, "\"publishedTimeText\":\\{\"simpleText\":\"(.*?)\""));
            if (id.isEmpty() || title.isEmpty()) continue;
            if (tab == MainActivity.Tab.SHORTS && !query.toLowerCase().contains("short")) continue;
            items.add(new TubeItem(
                    title,
                    subtitle(channel, length, published),
                    "https://www.youtube.com/watch?v=" + id,
                    youtubeThumbnail(id),
                    true,
                    tab == MainActivity.Tab.SHORTS,
                    parseRelativeAgeSeconds(published)
            ));
            if (items.size() >= MAX_RESULTS) break;
        }
        return items;
    }

    private static String extractContinuationToken(String text) {
        if (text == null || text.isEmpty()) return "";
        String token = match(text, "\"continuationCommand\":\\{\"token\":\"([^\"]+)\"");
        if (!token.isEmpty()) return token;
        token = match(text, "\"nextContinuationData\":\\{\"continuation\":\"([^\"]+)\"");
        if (!token.isEmpty()) return token;
        token = match(text, "\"continuation\":\\{\"reloadContinuationData\":\\{\"continuation\":\"([^\"]+)\"");
        if (!token.isEmpty()) return token;
        // Last resort: long continuation-looking tokens on home feed.
        Matcher m = Pattern.compile("\"token\":\"([A-Za-z0-9_%\\-]{40,})\"").matcher(text);
        if (m.find()) return m.group(1);
        return "";
    }

    private static List<TubeItem> mergeDedupe(List<TubeItem> first, List<TubeItem> second) {
        List<TubeItem> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (TubeItem item : first) {
            String key = item.url == null ? item.title : item.url;
            if (seen.add(key)) out.add(item);
        }
        if (second != null) {
            for (TubeItem item : second) {
                String key = item.url == null ? item.title : item.url;
                if (seen.add(key)) out.add(item);
            }
        }
        return out;
    }

    /** Home / browse feed: videoRenderer + compact/grid + loose videoId cards. */
    private static List<TubeItem> parseFeedItems(String text, MainActivity.Tab tab) {
        List<TubeItem> items = parseRendererBlocks(text, tab, "");
        items = mergeDedupe(items, parseRendererBlocksByMarker(text, "\"compactVideoRenderer\":{", tab));
        items = mergeDedupe(items, parseRendererBlocksByMarker(text, "\"gridVideoRenderer\":{", tab));
        items = mergeDedupe(items, parseRendererBlocksByMarker(text, "\"reelItemRenderer\":{", tab));
        // Newer lockup cards often only expose videoId + accessibility title.
        if (items.size() < 8) {
            items = mergeDedupe(items, parseLockupStyleItems(text));
        }
        return items;
    }

    private static List<TubeItem> parseLockupStyleItems(String text) {
        List<TubeItem> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Matcher idMatcher = Pattern.compile("\"videoId\":\"([A-Za-z0-9_-]{11})\"").matcher(text);
        while (idMatcher.find() && items.size() < MAX_RESULTS) {
            String id = idMatcher.group(1);
            if (!seen.add(id)) continue;
            int from = Math.max(0, idMatcher.start() - 400);
            int to = Math.min(text.length(), idMatcher.end() + 1200);
            String window = text.substring(from, to);
            String title = clean(match(window, "\"content\":\"([^\"]{2,120})\""));
            if (title.isEmpty()) {
                title = clean(match(window, "\"label\":\"([^\"]{2,120})\""));
            }
            if (title.isEmpty()) {
                title = clean(match(window, "\"title\":\\{\"runs\":\\[\\{\"text\":\"(.*?)\""));
            }
            if (title.isEmpty() || title.length() < 2) continue;
            // Skip pure metadata labels.
            if (title.matches("(?i)\\d+:\\d+.*") || title.contains("조회수") && title.length() < 20) continue;
            String channel = clean(match(window, "\"shortBylineText\":\\{\"runs\":\\[\\{\"text\":\"(.*?)\""));
            if (channel.isEmpty()) {
                channel = clean(match(window, "\"ownerText\":\\{\"runs\":\\[\\{\"text\":\"(.*?)\""));
            }
            items.add(new TubeItem(
                    title,
                    subtitle(channel, "", ""),
                    "https://www.youtube.com/watch?v=" + id,
                    youtubeThumbnail(id),
                    true,
                    window.contains("/shorts/"),
                    Long.MAX_VALUE
            ));
        }
        return items;
    }

    private static List<TubeItem> parseRendererBlocksByMarker(String text, String marker, MainActivity.Tab tab) {
        List<TubeItem> items = new ArrayList<>();
        for (String block : extractJsonObjects(text, marker)) {
            TubeItem item = videoItemFromBlock(block, tab, "");
            if (item != null) items.add(item);
            if (items.size() >= MAX_RESULTS) break;
        }
        return items;
    }

    private static List<TubeItem> parseRendererBlocks(String text, MainActivity.Tab tab, String query) {
        List<TubeItem> items = new ArrayList<>();

        if (tab == MainActivity.Tab.CHANNELS) {
            for (String block : extractJsonObjects(text, "\"channelRenderer\":{")) {
                String id = match(block, "\"channelId\":\"([^\"]+)\"");
                String title = clean(match(block, "\"title\":\\{\"simpleText\":\"(.*?)\""));
                if (title.isEmpty()) title = clean(match(block, "\"title\":\\{\"runs\":\\[\\{\"text\":\"(.*?)\""));
                if (id.isEmpty() || title.isEmpty()) continue;
                items.add(new TubeItem(
                        title,
                        "채널",
                        "https://www.youtube.com/channel/" + id,
                        bestThumbnailFromBlock(block),
                        false,
                        false
                ));
                if (items.size() >= 30) break;
            }
            return items;
        }

        for (String block : extractJsonObjects(text, "\"videoRenderer\":{")) {
            TubeItem item = videoItemFromBlock(block, tab, query);
            if (item != null) items.add(item);
            if (items.size() >= MAX_RESULTS) break;
        }
        return items;
    }

    private static TubeItem videoItemFromBlock(String block, MainActivity.Tab tab, String query) {
        String id = match(block, "\"videoId\":\"([A-Za-z0-9_-]{11})\"");
        if (id.isEmpty()) id = match(block, "\"videoId\":\"([^\"]+)\"");
        String title = clean(match(block, "\"title\":\\{\"runs\":\\[\\{\"text\":\"(.*?)\""));
        if (title.isEmpty()) title = clean(match(block, "\"title\":\\{\"simpleText\":\"(.*?)\""));
        if (title.isEmpty()) title = clean(match(block, "\"headline\":\\{\"simpleText\":\"(.*?)\""));
        String channel = clean(match(block, "\"ownerText\":\\{\"runs\":\\[\\{\"text\":\"(.*?)\""));
        if (channel.isEmpty()) channel = clean(match(block, "\"shortBylineText\":\\{\"runs\":\\[\\{\"text\":\"(.*?)\""));
        if (channel.isEmpty()) channel = clean(match(block, "\"longBylineText\":\\{\"runs\":\\[\\{\"text\":\"(.*?)\""));
        String length = clean(match(block, "\"lengthText\":\\{\"simpleText\":\"(.*?)\""));
        if (length.isEmpty()) {
            length = clean(match(block, "\"simpleText\":\"(\\d{1,2}:\\d{2}(?::\\d{2})?)\""));
        }
        String published = clean(match(block, "\"publishedTimeText\":\\{\"simpleText\":\"(.*?)\""));
        if (id.isEmpty() || title.isEmpty()) return null;
        boolean shortForm = tab == MainActivity.Tab.SHORTS
                || block.contains("/shorts/")
                || block.contains("reelItemRenderer")
                || markerIsReel(block);
        if (tab == MainActivity.Tab.SHORTS && !shortForm
                && query != null && !query.toLowerCase().contains("short")) {
            return null;
        }
        if (tab == MainActivity.Tab.VIDEOS && shortForm) return null;
        return new TubeItem(
                title,
                subtitle(channel, length, published),
                shortForm
                        ? "https://www.youtube.com/shorts/" + id
                        : "https://www.youtube.com/watch?v=" + id,
                youtubeThumbnail(id),
                true,
                shortForm,
                parseRelativeAgeSeconds(published)
        );
    }

    private static boolean markerIsReel(String block) {
        return block.contains("\"style\":\"REEL\"") || block.contains("\"isReelItem\":true");
    }

    private static String subtitle(String channel, String length, String published) {
        List<String> parts = new ArrayList<>();
        parts.add(channel.isEmpty() ? "YouTube" : channel);
        if (!length.isEmpty()) parts.add(length);
        if (!published.isEmpty()) parts.add(published);
        return String.join(" · ", parts);
    }

    private static long parseRelativeAgeSeconds(String text) {
        if (text == null || text.trim().isEmpty()) return Long.MAX_VALUE;
        String value = text.trim().toLowerCase();
        if (value.contains("방금") || value.contains("실시간") || value.contains("live")
                || value.contains("streamed")) {
            return 0;
        }
        if (value.contains("어제") || value.contains("yesterday")) return 24L * 60L * 60L;

        long absoluteAge = parseAbsoluteDateAgeSeconds(value);
        if (absoluteAge >= 0) return absoluteAge;

        Matcher matcher = Pattern.compile("(\\d+)\\s*([가-힣a-z]+)").matcher(value);
        if (!matcher.find()) return Long.MAX_VALUE;
        long amount = parseLong(matcher.group(1));
        String unit = matcher.group(2);
        if (unit.startsWith("초") || unit.startsWith("sec")) return amount;
        if (unit.startsWith("분") || unit.startsWith("min")) return amount * 60L;
        if (unit.startsWith("시간") || unit.startsWith("hour")) return amount * 60L * 60L;
        if (unit.startsWith("일") || unit.startsWith("day")) return amount * 24L * 60L * 60L;
        if (unit.startsWith("주") || unit.startsWith("week")) return amount * 7L * 24L * 60L * 60L;
        if (unit.startsWith("개월") || unit.startsWith("달") || unit.startsWith("month")) {
            return amount * 30L * 24L * 60L * 60L;
        }
        if (unit.startsWith("년") || unit.startsWith("year")) {
            return amount * 365L * 24L * 60L * 60L;
        }
        return Long.MAX_VALUE;
    }

    private static long parseAbsoluteDateAgeSeconds(String value) {
        Matcher korean = Pattern.compile("(\\d{4})\\s*[.년/-]\\s*(\\d{1,2})\\s*[.월/-]\\s*(\\d{1,2})").matcher(value);
        if (korean.find()) {
            return ageSeconds(korean.group(1), korean.group(2), korean.group(3));
        }
        Matcher iso = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})").matcher(value);
        if (iso.find()) {
            return ageSeconds(iso.group(1), iso.group(2), iso.group(3));
        }
        return -1;
    }

    private static long ageSeconds(String year, String month, String day) {
        try {
            LocalDate published = LocalDate.of(intOf(year), intOf(month), intOf(day));
            long days = ChronoUnit.DAYS.between(published, LocalDate.now());
            return Math.max(0, days) * 24L * 60L * 60L;
        } catch (Exception e) {
            return -1;
        }
    }

    private static String httpGet(String url) throws Exception {
        RequestPacer.beforeApiCall();
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", HttpDownloader.BROWSER_UA);
        connection.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.7,en;q=0.5");
        connection.setRequestProperty("Origin", "https://www.youtube.com");
        connection.setRequestProperty("Referer", "https://www.youtube.com/");
        CookieStore.applyTo(connection);
        try (InputStream in = connection.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String httpPost(String url, String json, Map<String, String> extraHeaders) throws Exception {
        return httpPost(url, json, extraHeaders, false);
    }

    /**
     * @param webAuth true for WEB browse/search (SAPISIDHASH). false for player clients
     *                (iOS/ANDROID_VR/TV) — auth hash + login cookies there often empty streams.
     */
    private static String httpPost(String url, String json, Map<String, String> extraHeaders, boolean webAuth)
            throws Exception {
        RequestPacer.beforeApiCall();
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(body.length);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("User-Agent", HttpDownloader.BROWSER_UA);
        connection.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.7,en;q=0.5");
        connection.setRequestProperty("Origin", "https://www.youtube.com");
        connection.setRequestProperty("Referer", "https://www.youtube.com/");
        // Player clients: consent only (guest). WEB search/home: full session (+ optional hash).
        if (webAuth) {
            CookieStore.applyTo(connection, true);
        } else {
            connection.setRequestProperty("Cookie", "SOCS=CAI; CONSENT=YES+cb.20210328-17-p0.en+FX+000");
        }
        if (extraHeaders != null) {
            for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                if ("Cookie".equalsIgnoreCase(entry.getKey()) && webAuth && CookieStore.hasAuthCookies()) {
                    continue;
                }
                connection.setRequestProperty(entry.getKey(), entry.getValue());
            }
        }
        if (webAuth && CookieStore.hasAuthCookies()) CookieStore.applyTo(connection, true);
        connection.getOutputStream().write(body);
        InputStream input = connection.getResponseCode() >= 400
                ? connection.getErrorStream()
                : connection.getInputStream();
        if (input == null) return "";
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static List<String> extractJsonObjects(String text, String marker) {
        List<String> blocks = new ArrayList<>();
        int searchFrom = 0;
        while (true) {
            int markerIndex = text.indexOf(marker, searchFrom);
            if (markerIndex < 0) break;
            int start = markerIndex + marker.length() - 1;
            int depth = 0;
            boolean inString = false;
            boolean escaped = false;
            for (int i = start; i < text.length(); i++) {
                char c = text.charAt(i);
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (c == '\\') {
                    escaped = true;
                    continue;
                }
                if (c == '"') {
                    inString = !inString;
                    continue;
                }
                if (inString) continue;
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        blocks.add(text.substring(start, i + 1));
                        searchFrom = i + 1;
                        break;
                    }
                }
            }
            if (searchFrom <= markerIndex) break;
        }
        return blocks;
    }

    private static String bestThumbnailFromBlock(String block) {
        Matcher matcher = Pattern.compile("\"url\":\"(https(?::|\\\\u003a)[^\"]+)\"").matcher(block);
        String url = "";
        while (matcher.find()) url = matcher.group(1);
        return cleanUrl(url);
    }

    private static String match(String text, String regex) {
        Matcher matcher = Pattern.compile(regex, Pattern.DOTALL).matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String clean(String value) {
        return decodeUnicodeEscapes(value == null ? "" : value)
                .replace("\\u0026", "&")
                .replace("\\u003d", "=")
                .replace("\\u003a", ":")
                .replace("\\\"", "\"")
                .replace("\\/", "/")
                .replace("&amp;", "&")
                .trim();
    }

    private static String cleanUrl(String value) {
        return clean(value).replace("\\u003d", "=");
    }

    private static String jsonEscape(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\':
                    out.append("\\\\");
                    break;
                case '"':
                    out.append("\\\"");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    out.append(c);
            }
        }
        return out.toString();
    }

    private static String decodeUnicodeEscapes(String value) {
        Matcher matcher = Pattern.compile("\\\\u([0-9a-fA-F]{4})").matcher(value);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            char decoded = (char) Integer.parseInt(matcher.group(1), 16);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(String.valueOf(decoded)));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }
}
