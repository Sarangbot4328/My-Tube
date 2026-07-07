package com.mytube.apk;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ExtractorBridge {
    private static boolean initialized;

    static synchronized void init() {
        if (initialized) return;
        NewPipe.init(new HttpDownloader(), new Localization("ko", "KR"), new ContentCountry("KR"));
        initialized = true;
    }

    static List<TubeItem> search(String query, MainActivity.Tab tab) throws Exception {
        init();
        String actualQuery = query == null || query.trim().isEmpty() ? defaultQuery(tab) : query.trim();
        if (tab == MainActivity.Tab.SHORTS && !actualQuery.toLowerCase().contains("shorts")) {
            actualQuery = actualQuery + " shorts";
        }

        List<String> filters = Arrays.asList(tab == MainActivity.Tab.CHANNELS
                ? YoutubeSearchQueryHandlerFactory.CHANNELS
                : YoutubeSearchQueryHandlerFactory.VIDEOS);
        SearchInfo info = SearchInfo.getInfo(ServiceList.YouTube.getSearchExtractor(actualQuery, filters, ""));
        List<TubeItem> items = new ArrayList<>();
        for (InfoItem item : info.getRelatedItems()) {
            if (item instanceof StreamInfoItem) {
                StreamInfoItem stream = (StreamInfoItem) item;
                if (tab == MainActivity.Tab.SHORTS && !stream.isShortFormContent()) continue;
                if (tab == MainActivity.Tab.VIDEOS && stream.isShortFormContent()) continue;
                items.add(new TubeItem(
                        stream.getName(),
                        stream.getUploaderName() + " · " + formatDuration(stream.getDuration()),
                        stream.getUrl(),
                        bestImageUrl(stream.getThumbnails()),
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
        }
        if (items.isEmpty()) {
            items.addAll(fallbackSearch(actualQuery, tab));
        }
        return items;
    }

    static PlaybackData resolve(String url) throws Exception {
        init();
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

        if (mediaUrl == null || mediaUrl.isEmpty()) {
            throw new IllegalStateException("재생 가능한 스트림을 찾지 못했습니다.");
        }

        return new PlaybackData(info.getName(), info.getUploaderName(), mediaUrl, manifest);
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

    private static List<TubeItem> fallbackSearch(String query, MainActivity.Tab tab) throws Exception {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name());
        String html = httpGet("https://www.youtube.com/results?search_query=" + encoded);
        List<TubeItem> items = new ArrayList<>();

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
                    bestThumbnailFromBlock(block),
                    true,
                    tab == MainActivity.Tab.SHORTS
            ));
            if (items.size() >= 30) break;
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
        try (InputStream in = connection.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
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
