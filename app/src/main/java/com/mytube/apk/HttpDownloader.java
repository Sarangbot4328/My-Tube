package com.mytube.apk;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class HttpDownloader extends Downloader {
    static final String BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    @Override
    public Response execute(Request request) throws IOException, ReCaptchaException {
        RequestPacer.beforeApiCall();
        HttpURLConnection connection = (HttpURLConnection) new URL(request.url()).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestMethod(request.httpMethod());
        connection.setRequestProperty("User-Agent", BROWSER_UA);
        connection.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.7,en;q=0.5");
        connection.setRequestProperty("Origin", "https://www.youtube.com");
        connection.setRequestProperty("Referer", "https://www.youtube.com/");
        CookieStore.applyTo(connection);

        Map<String, List<String>> headers = request.headers();
        if (headers != null) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) continue;
                // Prefer our session Cookie over NewPipe's empty/consent-only value.
                if ("Cookie".equalsIgnoreCase(entry.getKey()) && CookieStore.hasAuthCookies()) {
                    continue;
                }
                connection.setRequestProperty(entry.getKey(), String.join(",", entry.getValue()));
            }
        }

        // Ensure consent/login cookie wins even if a library header overwrote it.
        if (CookieStore.hasAuthCookies()) {
            CookieStore.applyTo(connection);
        }

        byte[] body = request.dataToSend();
        if (body != null && body.length > 0) {
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
        }

        int code = connection.getResponseCode();
        String responseBody = "";
        if (!"HEAD".equalsIgnoreCase(request.httpMethod())) {
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream != null) responseBody = readToString(stream);
        }

        return new Response(
                code,
                connection.getResponseMessage(),
                connection.getHeaderFields() == null ? Collections.emptyMap() : connection.getHeaderFields(),
                responseBody,
                connection.getURL().toString()
        );
    }

    private static String readToString(InputStream input) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
