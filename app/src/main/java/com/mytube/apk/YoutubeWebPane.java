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
import android.view.ViewGroup;
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

import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

/**
 * Real YouTube site with an in-page native player overlay (ad-free + app quality)
 * positioned over the web video area so the rest of the page stays natural.
 */
final class YoutubeWebPane extends LinearLayout {
    interface Host {
        void onVideoDetected(String videoUrl, String pageTitle);

        void onNotVideoPage();

        /** Auto / manual: start ad-free stream into the inline PlayerView. */
        void onInlinePlay(String videoUrl, String pageTitle);

        void onDownload(String videoUrl, String pageTitle);
    }

    private static final String HOME = "https://m.youtube.com/";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable urlPoller = new Runnable() {
        @Override
        public void run() {
            pollUrlFromJs();
            if (inlineVisible) measureAndPlacePlayer();
            mainHandler.postDelayed(this, 800);
        }
    };

    private Host host;
    private WebView webView;
    private FrameLayout stage;
    private ProgressBar progressBar;
    private TextView titleView;
    private LinearLayout videoBar;
    private TextView videoLabel;
    private FrameLayout playerSlot;
    private PlayerView inlinePlayerView;
    private TextView playerLoading;
    private View customFullscreenView;
    private FrameLayout.LayoutParams savedPlayerLp;
    private boolean fullscreenInline;

    private String currentUrl = HOME;
    private String currentVideoUrl = "";
    private String currentTitle = "";
    private boolean started;
    private boolean inlineVisible;
    private String lastInlineUrl = "";

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

    PlayerView getInlinePlayerView() {
        return inlinePlayerView;
    }

    boolean isInlineVisible() {
        return inlineVisible;
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
            if (fullscreenInline) {
                exitInlineFullscreen();
                return;
            }
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

        stage = new FrameLayout(context);
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

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                // HTML5 fullscreen from residual web player — cover stage.
                if (customFullscreenView != null) return;
                customFullscreenView = view;
                stage.addView(view, new FrameLayout.LayoutParams(-1, -1));
            }

            @Override
            public void onHideCustomView() {
                if (customFullscreenView != null) {
                    stage.removeView(customFullscreenView);
                    customFullscreenView = null;
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
                injectHideNativeVideoCss();
                removeLegacyDownloadFab();
                if (!currentVideoUrl.isEmpty()) {
                    measureAndPlacePlayer();
                    maybeStartInline();
                }
            }
        });
        stage.addView(webView, new FrameLayout.LayoutParams(-1, -1));

        // Native player sits over the web video rectangle.
        playerSlot = new FrameLayout(context);
        playerSlot.setBackgroundColor(Color.BLACK);
        playerSlot.setVisibility(GONE);
        playerSlot.setElevation(dp(8));

        inlinePlayerView = new PlayerView(context);
        inlinePlayerView.setBackgroundColor(Color.BLACK);
        inlinePlayerView.setShutterBackgroundColor(Color.BLACK);
        inlinePlayerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        inlinePlayerView.setUseController(true);
        inlinePlayerView.setControllerShowTimeoutMs(2500);
        inlinePlayerView.setFullscreenButtonClickListener(enter -> {
            if (enter) enterInlineFullscreen();
            else exitInlineFullscreen();
        });
        playerSlot.addView(inlinePlayerView, new FrameLayout.LayoutParams(-1, -1));

        playerLoading = new TextView(context);
        playerLoading.setText("광고 없이 준비 중…");
        playerLoading.setTextColor(Color.WHITE);
        playerLoading.setGravity(Gravity.CENTER);
        playerLoading.setBackgroundColor(Color.argb(160, 0, 0, 0));
        playerLoading.setVisibility(GONE);
        playerSlot.addView(playerLoading, new FrameLayout.LayoutParams(-1, -1));

        stage.addView(playerSlot, new FrameLayout.LayoutParams(1, 1));
        addView(stage, new LayoutParams(-1, 0, 1));

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

        Button retry = new Button(context);
        retry.setText("다시 재생");
        retry.setAllCaps(false);
        retry.setTextColor(Color.WHITE);
        retry.setBackgroundColor(Color.rgb(30, 64, 175));
        retry.setOnClickListener(v -> {
            lastInlineUrl = "";
            maybeStartInline();
        });
        row.addView(retry, new LayoutParams(0, dp(48), 1));

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
        mainHandler.postDelayed(urlPoller, 800);
    }

    void reloadWithCookies() {
        CookieStore.pushToWebView();
        if (webView != null) webView.loadUrl(HOME);
    }

    void goHome() {
        hideInlinePlayer();
        CookieStore.pushToWebView();
        if (webView != null) webView.loadUrl(HOME);
        applyVideoState("", HOME);
    }

    boolean canGoBack() {
        if (fullscreenInline) return true;
        return webView != null && webView.canGoBack();
    }

    void goBack() {
        if (fullscreenInline) {
            exitInlineFullscreen();
            return;
        }
        if (webView != null && webView.canGoBack()) webView.goBack();
    }

    void onPause() {
        mainHandler.removeCallbacks(urlPoller);
        if (webView != null) webView.onPause();
    }

    void onResume() {
        if (webView != null) webView.onResume();
        mainHandler.removeCallbacks(urlPoller);
        mainHandler.postDelayed(urlPoller, 400);
    }

    void destroy() {
        mainHandler.removeCallbacks(urlPoller);
        if (inlinePlayerView != null) inlinePlayerView.setPlayer(null);
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

    void setInlineLoading(boolean loading) {
        if (playerLoading != null) {
            playerLoading.setVisibility(loading ? VISIBLE : GONE);
            if (loading) playerLoading.setText("광고 없이 준비 중…");
        }
    }

    void setInlineError(String message) {
        if (playerLoading != null) {
            playerLoading.setVisibility(VISIBLE);
            playerLoading.setText(message == null ? "재생 실패" : message);
        }
    }

    void hideInlinePlayer() {
        inlineVisible = false;
        lastInlineUrl = "";
        fullscreenInline = false;
        if (playerSlot != null) playerSlot.setVisibility(GONE);
        if (playerLoading != null) playerLoading.setVisibility(GONE);
        if (inlinePlayerView != null) inlinePlayerView.setPlayer(null);
    }

    void showInlineShell() {
        inlineVisible = true;
        if (playerSlot != null) playerSlot.setVisibility(VISIBLE);
        setInlineLoading(true);
        measureAndPlacePlayer();
        injectHideNativeVideoCss();
        pauseWebVideos();
    }

    private void enterInlineFullscreen() {
        if (playerSlot == null || stage == null || fullscreenInline) return;
        fullscreenInline = true;
        savedPlayerLp = (FrameLayout.LayoutParams) playerSlot.getLayoutParams();
        FrameLayout.LayoutParams full = new FrameLayout.LayoutParams(-1, -1);
        playerSlot.setLayoutParams(full);
        playerSlot.bringToFront();
    }

    private void exitInlineFullscreen() {
        if (!fullscreenInline || playerSlot == null) return;
        fullscreenInline = false;
        if (savedPlayerLp != null) playerSlot.setLayoutParams(savedPlayerLp);
        else measureAndPlacePlayer();
    }

    private void maybeStartInline() {
        if (currentVideoUrl.isEmpty() || host == null) return;
        if (currentVideoUrl.equals(lastInlineUrl) && inlineVisible) return;
        lastInlineUrl = currentVideoUrl;
        showInlineShell();
        host.onInlinePlay(currentVideoUrl, currentTitle);
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
        if (videoUrl.isEmpty() && finished) {
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
                hideInlinePlayer();
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
        if (changed) {
            if (host != null) host.onVideoDetected(videoUrl, currentTitle);
            lastInlineUrl = "";
            // Small delay so m.youtube player DOM exists.
            mainHandler.postDelayed(this::maybeStartInline, 500);
        }
    }

    private void showVideoBar(String title) {
        videoBar.setVisibility(VISIBLE);
        videoLabel.setText("무광고 재생 · " + (title == null ? "" : title));
    }

    private void hideVideoBar() {
        videoBar.setVisibility(GONE);
    }

    private void measureAndPlacePlayer() {
        if (webView == null || playerSlot == null || !inlineVisible || fullscreenInline) return;
        webView.evaluateJavascript(
                "(function(){"
                        + "function box(el){if(!el)return null;var r=el.getBoundingClientRect();"
                        + "if(r.width<40||r.height<40)return null;"
                        + "return [Math.round(r.left),Math.round(r.top),Math.round(r.width),Math.round(r.height)];}"
                        + "var sels=['ytm-player-large-container','#player-container-id','#player',"
                        + "'.html5-video-player','ytm-player-component','#movie_player',"
                        + "'ytm-single-column-watch-next-results-renderer ytm-player-large-container',"
                        + "'video.html5-main-video','video'];"
                        + "for(var i=0;i<sels.length;i++){"
                        + "var el=document.querySelector(sels[i]);"
                        + "var b=box(el);if(b)return JSON.stringify(b);}"
                        + "var vids=document.getElementsByTagName('video');"
                        + "for(var j=0;j<vids.length;j++){var b2=box(vids[j]);if(b2)return JSON.stringify(b2);}"
                        + "return '';"
                        + "})()",
                value -> {
                    String raw = stripJsString(value);
                    mainHandler.post(() -> applyPlayerRectJson(raw));
                });
    }

    private void applyPlayerRectJson(String raw) {
        if (raw == null || raw.isEmpty() || playerSlot == null || stage == null) {
            // Fallback: top 16:9 band
            int w = stage.getWidth();
            if (w <= 0) w = getWidth();
            int h = (int) (w * 9f / 16f);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, Math.max(h, dp(180)));
            lp.topMargin = 0;
            lp.leftMargin = 0;
            playerSlot.setLayoutParams(lp);
            return;
        }
        try {
            String s = raw.trim();
            if (s.startsWith("[")) s = s.substring(1);
            if (s.endsWith("]")) s = s.substring(0, s.length() - 1);
            String[] p = s.split(",");
            if (p.length < 4) return;
            int left = (int) Float.parseFloat(p[0].trim());
            int top = (int) Float.parseFloat(p[1].trim());
            int width = (int) Float.parseFloat(p[2].trim());
            int height = (int) Float.parseFloat(p[3].trim());
            // WebView CSS px ≈ density-independent; convert to view coords.
            float density = getResources().getDisplayMetrics().density;
            // getBoundingClientRect is in CSS pixels relative to webview viewport.
            int l = Math.round(left);
            int t = Math.round(top);
            int w = Math.round(width);
            int h = Math.round(height);
            if (w < dp(80) || h < dp(45)) return;
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
            lp.leftMargin = Math.max(0, l);
            lp.topMargin = Math.max(0, t);
            playerSlot.setLayoutParams(lp);
            playerSlot.bringToFront();
        } catch (Exception ignored) {
        }
    }

    private void injectHideNativeVideoCss() {
        if (webView == null) return;
        String js = "(function(){try{"
                + "var css='video,.html5-video-player .video-stream,"
                + "ytm-player-large-container video,#player video"
                + "{opacity:0!important;pointer-events:none!important}"
                + ".ytp-chrome-bottom,.ytp-gradient-bottom,.ytp-pause-overlay"
                + "{opacity:0!important;pointer-events:none!important}';"
                + "if(!document.getElementById('mytube-hide-vid')){"
                + "var s=document.createElement('style');s.id='mytube-hide-vid';s.textContent=css;"
                + "document.documentElement.appendChild(s);}"
                + "}catch(e){}})();";
        webView.evaluateJavascript(js, null);
    }

    private void pauseWebVideos() {
        if (webView == null) return;
        webView.evaluateJavascript(
                "(function(){try{var vs=document.getElementsByTagName('video');"
                        + "for(var i=0;i<vs.length;i++){try{vs[i].pause();vs[i].muted=true;}catch(e){}}}"
                        + "catch(e){}})();",
                null);
    }

    private void injectAdShield() {
        if (webView == null) return;
        String js = "(function(){try{"
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
                + "}catch(e){}})();";
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

    static String thumbnailUrlFor(String videoUrl) {
        String id = ExtractorBridge.videoIdOf(videoUrl);
        if (id.isEmpty()) return "";
        return "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg";
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
