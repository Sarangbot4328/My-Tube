package com.mytube.apk;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Real YouTube site (m.youtube.com) inside a WebView. Detects watch/shorts pages
 * and shows a single native bottom bar for ad-free play + download.
 */
final class YoutubeWebPane extends LinearLayout {
    interface Host {
        void onVideoDetected(String videoUrl, String pageTitle);

        void onNotVideoPage();

        void onAdFreePlay(String videoUrl, String pageTitle);

        void onDownload(String videoUrl, String pageTitle);
    }

    private static final String HOME = "https://m.youtube.com/";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable urlPoller = new Runnable() {
        @Override
        public void run() {
            pollUrlFromJs();
            mainHandler.postDelayed(this, 900);
        }
    };

    private Host host;
    private WebView webView;
    private ProgressBar progressBar;
    private TextView titleView;
    private LinearLayout videoBar;
    private TextView videoLabel;
    private String currentUrl = HOME;
    private String currentVideoUrl = "";
    private String currentTitle = "";
    private boolean started;

    public YoutubeWebPane(Context context) {
        super(context);
        init(context);
    }

    public YoutubeWebPane(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    void setHost(Host host) {
        this.host = host;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void init(Context context) {
        setOrientation(VERTICAL);
        setBackgroundColor(Color.BLACK);
        setLayoutParams(new FrameLayout.LayoutParams(-1, -1));

        LinearLayout toolbar = new LinearLayout(context);
        toolbar.setOrientation(HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setBackgroundColor(Color.rgb(15, 15, 15));
        toolbar.setPadding(dp(6), dp(6), dp(6), dp(6));

        Button back = toolButton(context, "←", v -> {
            if (webView != null && webView.canGoBack()) webView.goBack();
        });
        toolbar.addView(back, new LayoutParams(dp(44), dp(40)));

        Button home = toolButton(context, "홈", v -> goHome());
        toolbar.addView(home, new LayoutParams(dp(52), dp(40)));

        titleView = new TextView(context);
        titleView.setText("YouTube");
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(13);
        titleView.setMaxLines(1);
        titleView.setPadding(dp(8), 0, dp(8), 0);
        toolbar.addView(titleView, new LayoutParams(0, -2, 1));

        Button reload = toolButton(context, "↻", v -> {
            if (webView != null) webView.reload();
        });
        toolbar.addView(reload, new LayoutParams(dp(44), dp(40)));
        addView(toolbar, new LayoutParams(-1, -2));

        progressBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setVisibility(GONE);
        addView(progressBar, new LayoutParams(-1, dp(3)));

        FrameLayout stage = new FrameLayout(context);
        webView = new WebView(context);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setUserAgentString(
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36");
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setVisibility(newProgress >= 100 ? GONE : VISIBLE);
                progressBar.setProgress(newProgress);
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                if (title != null && !title.isEmpty()) {
                    currentTitle = title.replace(" - YouTube", "").trim();
                    titleView.setText(currentTitle);
                    if (!currentVideoUrl.isEmpty()) showVideoBar(currentTitle);
                }
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request != null && request.getUrl() != null) {
                    onUrlMaybeVideo(request.getUrl().toString(), false);
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                currentUrl = url == null ? "" : url;
                onUrlMaybeVideo(currentUrl, false);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                currentUrl = url == null ? "" : url;
                onUrlMaybeVideo(currentUrl, true);
                injectAdShield();
                // Remove leftover FAB from older builds if still in page cache.
                removeLegacyDownloadFab();
            }
        });
        stage.addView(webView, new FrameLayout.LayoutParams(-1, -1));
        addView(stage, new LayoutParams(-1, 0, 1));

        // Single bottom action bar (native) — no in-page download button.
        videoBar = new LinearLayout(context);
        videoBar.setOrientation(VERTICAL);
        videoBar.setBackgroundColor(Color.rgb(24, 24, 24));
        videoBar.setPadding(dp(12), dp(10), dp(12), dp(10));
        videoBar.setVisibility(GONE);

        videoLabel = new TextView(context);
        videoLabel.setTextColor(Color.rgb(203, 213, 225));
        videoLabel.setTextSize(12);
        videoLabel.setMaxLines(2);
        videoLabel.setPadding(0, 0, 0, dp(8));
        videoBar.addView(videoLabel, new LayoutParams(-1, -2));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);

        Button adFree = new Button(context);
        adFree.setText("광고없이 재생");
        adFree.setAllCaps(false);
        adFree.setTextColor(Color.WHITE);
        adFree.setBackgroundColor(Color.rgb(30, 64, 175));
        adFree.setOnClickListener(v -> {
            if (host != null && !currentVideoUrl.isEmpty()) {
                host.onAdFreePlay(currentVideoUrl, currentTitle);
            }
        });
        row.addView(adFree, new LayoutParams(0, dp(48), 1));

        Button download = new Button(context);
        download.setText("대기열 등록");
        download.setAllCaps(false);
        download.setTextColor(Color.WHITE);
        download.setBackgroundColor(Color.rgb(220, 38, 38));
        download.setOnClickListener(v -> {
            if (host != null && !currentVideoUrl.isEmpty()) {
                host.onDownload(currentVideoUrl, currentTitle);
            }
        });
        LayoutParams dlp = new LayoutParams(0, dp(48), 1);
        dlp.setMargins(dp(8), 0, 0, 0);
        row.addView(download, dlp);
        videoBar.addView(row, new LayoutParams(-1, -2));
        addView(videoBar, new LayoutParams(-1, -2));
    }

    private Button toolButton(Context context, String text, OnClickListener click) {
        Button b = new Button(context);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.rgb(40, 40, 40));
        b.setOnClickListener(click);
        b.setPadding(0, 0, 0, 0);
        return b;
    }

    void start() {
        if (started && webView != null && webView.getUrl() != null) {
            CookieStore.pushToWebView();
            return;
        }
        started = true;
        CookieStore.pushToWebView();
        if (webView != null) webView.loadUrl(HOME);
        mainHandler.removeCallbacks(urlPoller);
        mainHandler.postDelayed(urlPoller, 1000);
    }

    void reloadWithCookies() {
        // After login: CookieManager already has the session — do not corrupt it.
        CookieStore.pushToWebView();
        if (webView != null) {
            webView.loadUrl(HOME);
        }
    }

    void goHome() {
        CookieStore.pushToWebView();
        if (webView != null) webView.loadUrl(HOME);
        applyVideoState("", HOME);
    }

    boolean canGoBack() {
        return webView != null && webView.canGoBack();
    }

    void goBack() {
        if (webView != null && webView.canGoBack()) webView.goBack();
    }

    void onPause() {
        mainHandler.removeCallbacks(urlPoller);
        if (webView != null) webView.onPause();
    }

    void onResume() {
        if (webView != null) webView.onResume();
        mainHandler.removeCallbacks(urlPoller);
        mainHandler.postDelayed(urlPoller, 500);
    }

    void destroy() {
        mainHandler.removeCallbacks(urlPoller);
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
    }

    String getCurrentVideoUrl() {
        return currentVideoUrl;
    }

    String getCurrentTitle() {
        return currentTitle;
    }

    private void pollUrlFromJs() {
        if (webView == null) return;
        webView.evaluateJavascript(
                "(function(){try{return location.href}catch(e){return ''}})()",
                value -> {
                    String href = stripJsString(value);
                    if (href.isEmpty()) return;
                    mainHandler.post(() -> onUrlMaybeVideo(href, true));
                });
    }

    private void onUrlMaybeVideo(String url, boolean finished) {
        if (url == null) url = "";
        currentUrl = url;
        String videoUrl = normalizeVideoUrl(url);
        if (videoUrl.isEmpty() && finished && webView != null) {
            // SPA: path may lag; poll handles most cases.
            applyVideoState("", url);
            return;
        }
        applyVideoState(videoUrl, url);
    }

    private void applyVideoState(String videoUrl, String pageUrl) {
        if (videoUrl == null || videoUrl.isEmpty()) {
            if (!currentVideoUrl.isEmpty()) {
                currentVideoUrl = "";
                hideVideoBar();
                if (host != null) host.onNotVideoPage();
            } else {
                hideVideoBar();
            }
            return;
        }
        boolean changed = !videoUrl.equals(currentVideoUrl);
        currentVideoUrl = videoUrl;
        if (currentTitle.isEmpty() || "YouTube".equals(currentTitle)) {
            currentTitle = "YouTube 영상";
        }
        showVideoBar(currentTitle);
        if (changed && host != null) host.onVideoDetected(videoUrl, currentTitle);
    }

    private void showVideoBar(String title) {
        videoBar.setVisibility(VISIBLE);
        videoLabel.setText("영상 · " + (title == null ? "" : title));
    }

    private void hideVideoBar() {
        videoBar.setVisibility(GONE);
    }

    private void injectAdShield() {
        if (webView == null) return;
        String js = "(function(){"
                + "try{"
                + "var css='ytd-ad-slot-renderer,.ytp-ad-module,.video-ads,"
                + ".ytp-ad-player-overlay,.ytp-ad-overlay-container,"
                + "ytd-promoted-sparkles-web-renderer,ytd-display-ad-renderer,"
                + "#player-ads,.ad-container,"
                + "ytm-promoted-sparkles-text-search-renderer,"
                + "ytm-promoted-sparkles-web-renderer{display:none!important;height:0!important}';"
                + "if(!document.getElementById('mytube-ad-css')){"
                + "var s=document.createElement('style');s.id='mytube-ad-css';s.textContent=css;"
                + "document.documentElement.appendChild(s);}"
                + "if(!window.__mytubeAdTimer){window.__mytubeAdTimer=setInterval(function(){"
                + "var skip=document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,"
                + ".ytp-skip-ad-button,.ytp-ad-skip-button-container button');"
                + "if(skip){try{skip.click()}catch(e){}}"
                + "},700);}"
                + "}catch(e){}"
                + "})();";
        webView.evaluateJavascript(js, null);
    }

    private void removeLegacyDownloadFab() {
        if (webView == null) return;
        webView.evaluateJavascript(
                "(function(){try{var b=document.getElementById('mytube-dl-fab');"
                        + "if(b&&b.parentNode)b.parentNode.removeChild(b);}catch(e){}})();",
                null);
    }

    static String normalizeVideoUrl(String url) {
        if (url == null || url.isEmpty()) return "";
        // Ignore cookie consent / account interstitial URLs
        String lower = url.toLowerCase();
        if (lower.contains("accounts.google") || lower.contains("consent.youtube")
                || lower.contains("consent.google") || lower.contains("cookie")) {
            // still try extract video id if embedded
        }
        String id = ExtractorBridge.videoIdOf(url);
        if (id.isEmpty()) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?:/shorts/|v=|youtu\\.be/)([A-Za-z0-9_-]{11})")
                    .matcher(url);
            if (m.find()) id = m.group(1);
        }
        if (id.isEmpty()) return "";
        if (url.contains("/shorts/")) return "https://www.youtube.com/shorts/" + id;
        return "https://www.youtube.com/watch?v=" + id;
    }

    private static String stripJsString(String value) {
        if (value == null || value.equals("null")) return "";
        String s = value.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
            s = s.replace("\\/", "/").replace("\\\"", "\"").replace("\\u003d", "=");
        }
        return s;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
