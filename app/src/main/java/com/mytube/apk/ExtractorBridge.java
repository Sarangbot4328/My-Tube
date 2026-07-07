package com.mytube.apk;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ExtractorBridge {
    private static boolean initialized;

    // Well-known public InnerTube WEB API key, used as a fallback when the key
    // can't be scraped from the bootstrap HTML.
    private static final String DEFAULT_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8";
    private static final String DEFAULT_CLIENT_VERSION = "2.20240718.01.00";
    // Skips YouTube's EU/consent interstitial that otherwise returns an unusable page.
    private static final String CONSENT_COOKIE = "SOCS=CAI; CONSENT=YES+cb.20210328-17-p0.en+FX+000";

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
        VIEWS("CAMSAhAB"),
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
        String actualQuery = query == null || query.trim().isEmpty() ? defaultQuery(tab) : query.trim();
        if (tab == MainActivity.Tab.SHORTS && !actualQuery.toLowerCase().contains("shorts")) {
            actualQuery = actualQuery + " shorts";
        }
        return new SearchSession(actualQuery, tab, sort == null ? SortOrder.RELEVANCE : sort);
    }

    // Holds the cursor for one search so the UI can keep pulling pages as the user
    // scrolls (infinite scroll), instead of loading everything up front.
    static final class SearchSession {
        private static final int MODE_INIT = 0;
        private static final int MODE_NEWPIPE = 1;
        private static final int MODE_INNERTUBE = 2;
        private static final int MODE_DONE = 3;

        private final String actualQuery;
        private final MainActivity.Tab tab;
        private final SortOrder sort;
        private int mode = MODE_INIT;

        // NewPipe cursor.
        private SearchQueryHandler queryHandler;
        private Page nextPage;

        // InnerTube cursor.
        private String itUrl;
        private String itClientVersion = "";
        private Map<String, String> itHeaders;
        private String continuationToken = "";
        private String bootstrapHtml = "";

        SearchSession(String actualQuery, MainActivity.Tab tab, SortOrder sort) {
            this.actualQuery = actualQuery;
            this.tab = tab;
            this.sort = sort;
        }

        boolean hasMore() {
            switch (mode) {
                case MODE_INIT: return true;
                case MODE_NEWPIPE: return nextPage != null;
                case MODE_INNERTUBE: return !continuationToken.isEmpty();
                default: return false;
            }
        }

        // Fetches and returns the next page of results (empty list when exhausted).
        synchronized List<TubeItem> loadMore() throws Exception {
            switch (mode) {
                case MODE_INIT: return initFetch();
                case MODE_NEWPIPE: return newPipeNext();
                case MODE_INNERTUBE: return innerTubeNext();
                default: return new ArrayList<>();
            }
        }

        private List<TubeItem> initFetch() throws Exception {
            // Sorting isn't available through NewPipe, so only use it for the
            // default relevance order; otherwise go straight to InnerTube.
            if (sort == SortOrder.RELEVANCE) {
                try {
                    List<TubeItem> items = newPipeFirst();
                    if (!items.isEmpty()) { mode = MODE_NEWPIPE; return items; }
                } catch (Exception ignored) {
                    // fall through to InnerTube
                }
            }
            try {
                List<TubeItem> items = innerTubeFirst();
                if (!items.isEmpty()) { mode = MODE_INNERTUBE; return items; }
            } catch (Exception ignored) {
                // fall through to a one-shot HTML scrape
            }
            mode = MODE_DONE;
            try {
                return htmlScrape();
            } catch (Exception ignored) {
                return new ArrayList<>();
            }
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
            try {
                bootstrapHtml = httpGet("https://www.youtube.com/results?search_query="
                        + URLEncoder.encode(actualQuery, StandardCharsets.UTF_8.name()));
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
            itUrl = "https://www.youtube.com/youtubei/v1/search?key=" + apiKey + "&prettyPrint=false";

            String params = tab == MainActivity.Tab.CHANNELS ? "EgIQAg==" : sort.videoParams;
            String payload = "{"
                    + "\"context\":{\"client\":{"
                    + "\"clientName\":\"WEB\","
                    + "\"clientVersion\":\"" + jsonEscape(itClientVersion) + "\","
                    + "\"hl\":\"ko\",\"gl\":\"KR\""
                    + "}},"
                    + "\"query\":\"" + jsonEscape(actualQuery) + "\","
                    + "\"params\":\"" + params + "\""
                    + "}";
            String response = httpPost(itUrl, payload, itHeaders);
            List<TubeItem> items = parseRendererBlocks(response, tab, actualQuery);
            continuationToken = extractContinuationToken(response);
            return items;
        }

        private List<TubeItem> innerTubeNext() throws Exception {
            if (continuationToken.isEmpty()) { mode = MODE_DONE; return new ArrayList<>(); }
            String payload = "{"
                    + "\"context\":{\"client\":{"
                    + "\"clientName\":\"WEB\","
                    + "\"clientVersion\":\"" + jsonEscape(itClientVersion) + "\","
                    + "\"hl\":\"ko\",\"gl\":\"KR\""
                    + "}},"
                    + "\"continuation\":\"" + jsonEscape(continuationToken) + "\""
                    + "}";
            String response;
            try {
                response = httpPost(itUrl, payload, itHeaders);
            } catch (Exception e) {
                continuationToken = "";
                mode = MODE_DONE;
                return new ArrayList<>();
            }
            List<TubeItem> items = parseRendererBlocks(response, tab, actualQuery);
            continuationToken = extractContinuationToken(response);
            return items;
        }

        private List<TubeItem> htmlScrape() throws Exception {
            String html = bootstrapHtml;
            if (html.isEmpty()) {
                html = httpGet("https://www.youtube.com/results?search_query="
                        + URLEncoder.encode(actualQuery, StandardCharsets.UTF_8.name()));
            }
            return scrapeResultsHtml(html, tab, actualQuery);
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
        // 1) Bundled NewPipe extractor. Can throw or come back empty on outdated
        //    versions / new YouTube changes, so we must fall through on failure.
        try {
            PlaybackData data = newPipeResolve(url);
            if (data != null) return data;
        } catch (Exception ignored) {
            // fall through to the InnerTube player fallback
        }

        // 2) Fallback: ask YouTube's InnerTube player endpoint using the iOS
        //    client, which returns a directly-playable, ad-free HLS manifest
        //    (muxed audio+video) without any JS signature deciphering.
        PlaybackData fallback = innerTubeResolve(url);
        if (fallback != null) return fallback;

        throw new IllegalStateException("재생 가능한 스트림을 찾지 못했습니다.");
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

    // Raw iOS-client player response for a video id (used by playback and download).
    private static String iosPlayerResponse(String videoId) throws Exception {
        String payload = "{"
                + "\"context\":{\"client\":{"
                + "\"clientName\":\"IOS\","
                + "\"clientVersion\":\"" + IOS_CLIENT_VERSION + "\","
                + "\"deviceModel\":\"iPhone16,2\","
                + "\"hl\":\"ko\",\"gl\":\"KR\""
                + "}},"
                + "\"videoId\":\"" + jsonEscape(videoId) + "\","
                + "\"contentCheckOk\":true,\"racyCheckOk\":true"
                + "}";
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", IOS_USER_AGENT);
        headers.put("X-YouTube-Client-Name", "5");
        headers.put("X-YouTube-Client-Version", IOS_CLIENT_VERSION);
        return httpPost(
                "https://www.youtube.com/youtubei/v1/player?key=" + IOS_API_KEY + "&prettyPrint=false",
                payload,
                headers
        );
    }

    // A downloadable progressive (muxed audio+video) stream for one quality.
    static final class DownloadOption {
        final String label;   // e.g. "720p"
        final String url;
        final String ext;     // "mp4" / "webm"
        final String mime;
        DownloadOption(String label, String url, String ext, String mime) {
            this.label = label;
            this.url = url;
            this.ext = ext;
            this.mime = mime;
        }
    }

    // Lists the progressive (single-file) qualities that can be downloaded without
    // muxing. Adaptive-only videos return an empty list.
    static List<DownloadOption> downloadOptions(String url) throws Exception {
        init();
        String videoId = extractVideoId(url);
        if (videoId.isEmpty()) return new ArrayList<>();
        String response = iosPlayerResponse(videoId);

        List<DownloadOption> options = new ArrayList<>();
        int start = response.indexOf("\"formats\":[");
        if (start < 0) return options;
        start += "\"formats\":[".length();
        int end = response.indexOf(']', start);
        if (end < 0) return options;

        String[] entries = response.substring(start, end).split("\\},\\{");
        for (String entry : entries) {
            String streamUrl = cleanUrl(match(entry, "\"url\":\"([^\"]+)\""));
            if (streamUrl.isEmpty()) continue;   // ciphered stream, not directly usable
            String mime = clean(match(entry, "\"mimeType\":\"([^\"]+)\""));
            String label = match(entry, "\"qualityLabel\":\"([^\"]+)\"");
            String ext = mime.contains("webm") ? "webm" : "mp4";
            String cleanMime = mime.contains(";") ? mime.substring(0, mime.indexOf(';')) : mime;
            options.add(new DownloadOption(label.isEmpty() ? "기본 화질" : label, streamUrl, ext, cleanMime));
        }
        return options;
    }

    private static PlaybackData innerTubeResolve(String url) throws Exception {
        String videoId = extractVideoId(url);
        if (videoId.isEmpty()) return null;

        String response = iosPlayerResponse(videoId);

        // Only serve a playable video: bail if YouTube reports it can't be played.
        String status = match(response, "\"playabilityStatus\":\\{\"status\":\"([^\"]+)\"");
        if (!status.isEmpty() && !"OK".equalsIgnoreCase(status)) return null;

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
            if (id.isEmpty() || title.isEmpty()) continue;
            if (tab == MainActivity.Tab.SHORTS && !query.toLowerCase().contains("short")) continue;
            items.add(new TubeItem(
                    title,
                    (channel.isEmpty() ? "YouTube" : channel) + (length.isEmpty() ? "" : " · " + length),
                    "https://www.youtube.com/watch?v=" + id,
                    youtubeThumbnail(id),
                    true,
                    tab == MainActivity.Tab.SHORTS
            ));
            if (items.size() >= MAX_RESULTS) break;
        }
        return items;
    }

    private static String extractContinuationToken(String text) {
        return match(text, "\"continuationCommand\":\\{\"token\":\"([^\"]+)\"");
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
            String id = match(block, "\"videoId\":\"([^\"]+)\"");
            String title = clean(match(block, "\"title\":\\{\"runs\":\\[\\{\"text\":\"(.*?)\""));
            String channel = clean(match(block, "\"ownerText\":\\{\"runs\":\\[\\{\"text\":\"(.*?)\""));
            if (channel.isEmpty()) channel = clean(match(block, "\"shortBylineText\":\\{\"runs\":\\[\\{\"text\":\"(.*?)\""));
            String length = clean(match(block, "\"lengthText\":\\{\"simpleText\":\"(.*?)\""));
            if (id.isEmpty() || title.isEmpty()) continue;
            boolean shortForm = tab == MainActivity.Tab.SHORTS || block.contains("\"navigationEndpoint\":{\"commandMetadata\":{\"webCommandMetadata\":{\"url\":\"/shorts/");
            if (tab == MainActivity.Tab.SHORTS && !shortForm && !query.toLowerCase().contains("short")) continue;
            if (tab == MainActivity.Tab.VIDEOS && shortForm) continue;
            items.add(new TubeItem(
                    title,
                    (channel.isEmpty() ? "YouTube" : channel) + (length.isEmpty() ? "" : " · " + length),
                    "https://www.youtube.com/watch?v=" + id,
                    youtubeThumbnail(id),
                    true,
                    shortForm
            ));
            if (items.size() >= MAX_RESULTS) break;
        }
        return items;
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36");
        connection.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.7,en;q=0.5");
        connection.setRequestProperty("Cookie", CONSENT_COOKIE);
        try (InputStream in = connection.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String httpPost(String url, String json, Map<String, String> extraHeaders) throws Exception {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(body.length);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36");
        connection.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.7,en;q=0.5");
        connection.setRequestProperty("Cookie", CONSENT_COOKIE);
        connection.setRequestProperty("Origin", "https://www.youtube.com");
        connection.setRequestProperty("Referer", "https://www.youtube.com/");
        if (extraHeaders != null) {
            for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                connection.setRequestProperty(entry.getKey(), entry.getValue());
            }
        }
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
