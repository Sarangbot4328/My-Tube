package com.mytube.apk;

import android.annotation.SuppressLint;
import android.animation.LayoutTransition;
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
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONTokener;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Real YouTube site (m.youtube.com). On watch/shorts pages shows a bottom bar:
 * «광고없이 재생» (full app player) + «대기열 등록».
 * Does NOT auto-overlay a player on the web page.
 */
final class YoutubeWebPane extends LinearLayout {
    interface Host {
        void onVideoDetected(String videoUrl, String pageTitle);

        void onNotVideoPage();

        void onAdFreePlay(String videoUrl, String pageTitle, List<TubeItem> relatedVideos);

        void onDownload(String videoUrl, String pageTitle);

        void onFullscreenChanged(boolean fullscreen);

        void onVideoPanelChanged(boolean videoPage, boolean expanded, boolean animate);
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
    private FrameLayout stage;
    private ProgressBar progressBar;
    private LinearLayout toolbar;
    private TextView titleView;
    private LinearLayout videoBar;
    private LinearLayout videoBarContent;
    private TextView videoBarToggle;
    private TextView videoLabel;
    private View customFullscreenView;
    private WebChromeClient.CustomViewCallback customFullscreenCallback;

    private String currentUrl = HOME;
    private String currentVideoUrl = "";
    private String currentTitle = "";
    private boolean started;
    private boolean videoBarExpanded;

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

        toolbar = new LinearLayout(context);
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
                progressBar.setVisibility(customFullscreenView != null || newProgress >= 100
                        ? GONE : VISIBLE);
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
                // YouTube HTML5 fullscreen inside WebView.
                if (customFullscreenView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customFullscreenView = view;
                customFullscreenCallback = callback;
                view.setBackgroundColor(Color.BLACK);
                stage.addView(view, new FrameLayout.LayoutParams(-1, -1));
                view.bringToFront();
                toolbar.setVisibility(GONE);
                progressBar.setVisibility(GONE);
                videoBar.setVisibility(GONE);
                if (host != null) host.onFullscreenChanged(true);
            }

            @Override
            public void onHideCustomView() {
                hideCustomFullscreen();
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(
                    WebView view, WebResourceRequest request) {
                if (request != null && request.getUrl() != null
                        && isAdResourceUrl(request.getUrl().toString())) {
                    return new WebResourceResponse(
                            "text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
                }
                return super.shouldInterceptRequest(view, request);
            }

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
                injectBackgroundPlayback();
                removeLegacyDownloadFab();
            }
        });
        stage.addView(webView, new FrameLayout.LayoutParams(-1, -1));
        addView(stage, new LayoutParams(-1, 0, 1));

        // Collapsed by default so the web page keeps almost all available height.
        videoBar = new LinearLayout(context);
        videoBar.setOrientation(VERTICAL);
        videoBar.setBackgroundColor(Color.rgb(24, 24, 24));
        videoBar.setPadding(0, 0, 0, 0);
        videoBar.setVisibility(GONE);
        LayoutTransition panelTransition = new LayoutTransition();
        panelTransition.setDuration(180);
        videoBar.setLayoutTransition(panelTransition);

        videoBarToggle = new TextView(context);
        videoBarToggle.setText("⌃");
        videoBarToggle.setTextColor(Color.rgb(203, 213, 225));
        videoBarToggle.setTextSize(20);
        videoBarToggle.setGravity(Gravity.CENTER);
        videoBarToggle.setBackgroundColor(Color.rgb(18, 18, 18));
        videoBarToggle.setContentDescription("재생 메뉴 열기");
        videoBarToggle.setOnClickListener(v ->
                setVideoBarExpanded(!videoBarExpanded, true));
        videoBar.addView(videoBarToggle, new LayoutParams(-1, dp(30)));

        videoBarContent = new LinearLayout(context);
        videoBarContent.setOrientation(VERTICAL);
        videoBarContent.setPadding(dp(12), dp(4), dp(12), dp(10));
        videoBarContent.setVisibility(GONE);

        videoLabel = new TextView(context);
        videoLabel.setTextColor(Color.rgb(203, 213, 225));
        videoLabel.setTextSize(12);
        videoLabel.setMaxLines(2);
        videoLabel.setPadding(0, 0, 0, dp(8));
        videoBarContent.addView(videoLabel, new LayoutParams(-1, -2));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);

        Button adFree = new Button(context);
        adFree.setText("광고없이 재생");
        adFree.setAllCaps(false);
        adFree.setTextColor(Color.WHITE);
        adFree.setBackgroundColor(Color.rgb(30, 64, 175));
        adFree.setOnClickListener(v -> {
            if (host != null && !currentVideoUrl.isEmpty()) {
                playAdFreeWithRelatedVideos();
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
        videoBarContent.addView(row, new LayoutParams(-1, -2));
        videoBar.addView(videoBarContent, new LayoutParams(-1, -2));
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
        CookieStore.pushToWebView();
        if (webView != null) webView.loadUrl(HOME);
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

    void armBackgroundPlayback() {
        if (webView == null || currentVideoUrl.isEmpty()) return;
        webView.evaluateJavascript(
                "(function(){try{var v=document.querySelector("
                        + "'#movie_player video,video.html5-main-video,video');"
                        + "if(v&&!v.paused&&!v.ended){window.__mytubeBgVideo=v;"
                        + "window.__mytubeBgArmed=true;try{v.play()}catch(e){}}"
                        + "}catch(e){}})();",
                null);
    }

    void disarmBackgroundPlayback() {
        if (webView == null) return;
        webView.evaluateJavascript(
                "(function(){window.__mytubeBgArmed=false;"
                        + "window.__mytubeBgVideo=null;})()",
                null);
    }

    void destroy() {
        mainHandler.removeCallbacks(urlPoller);
        exitFullscreen();
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

    /** Kept for MainActivity compatibility — no in-page player anymore. */
    boolean isInlineVisible() {
        return false;
    }

    void hideInlinePlayer() {
        // no-op
    }

    boolean isFullscreen() {
        return customFullscreenView != null;
    }

    boolean exitFullscreen() {
        if (customFullscreenView == null) return false;
        WebChromeClient.CustomViewCallback callback = customFullscreenCallback;
        hideCustomFullscreen();
        if (callback != null) callback.onCustomViewHidden();
        return true;
    }

    private void hideCustomFullscreen() {
        if (customFullscreenView == null) return;
        stage.removeView(customFullscreenView);
        customFullscreenView = null;
        customFullscreenCallback = null;
        toolbar.setVisibility(VISIBLE);
        progressBar.setVisibility(GONE);
        if (!currentVideoUrl.isEmpty()) showVideoBar(currentTitle);
        if (host != null) host.onFullscreenChanged(false);
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

    /**
     * Takes a snapshot of the recommendation cards currently rendered on the
     * YouTube watch page before the WebView is paused behind the native player.
     */
    private void playAdFreeWithRelatedVideos() {
        if (host == null || currentVideoUrl.isEmpty()) return;
        final String videoUrl = currentVideoUrl;
        final String pageTitle = currentTitle;
        if (webView == null) {
            host.onAdFreePlay(videoUrl, pageTitle, new ArrayList<>());
            return;
        }

        String currentId = ExtractorBridge.videoIdOf(videoUrl);
        String js = "(function(){try{"
                + "var currentId='" + currentId + "',seen={},out=[];"
                + "var cards=document.querySelectorAll("
                + "'ytm-compact-video-renderer,ytm-video-with-context-renderer,"
                + "ytm-rich-item-renderer,ytm-reel-item-renderer,"
                + "ytm-playlist-panel-video-renderer,ytd-compact-video-renderer,"
                + "ytd-rich-item-renderer,yt-lockup-view-model,"
                + "ytm-shorts-lockup-view-model,ytm-video-with-context-renderer');"
                + "function add(a,card){if(!a||out.length>=80)return;"
                + "var href=a.href||a.getAttribute('href')||'';"
                + "var m=href.match(/(?:[?&]v=|\\/shorts\\/)([A-Za-z0-9_-]{11})/);"
                + "if(!m||m[1]===currentId||seen[m[1]])return;seen[m[1]]=1;"
                + "var n=card.querySelector('.media-item-headline,h3,[role=heading]');"
                + "var title=((n&&(n.getAttribute('aria-label')||n.textContent))"
                + "||a.getAttribute('aria-label')||a.title||'YouTube video')"
                + ".replace(/\\s+/g,' ').trim();"
                + "out.push({url:(href.indexOf('/shorts/')>=0"
                + "?'https://www.youtube.com/shorts/':'https://www.youtube.com/watch?v=')"
                + "+m[1],title:title});}"
                + "for(var i=0;i<cards.length;i++){"
                + "var links=cards[i].querySelectorAll('a[href]');"
                + "for(var j=0;j<links.length;j++)add(links[j],cards[i]);}"
                + "if(!out.length){var links=document.querySelectorAll("
                + "'a[href*=\"watch?v=\"],a[href*=\"/shorts/\"]');"
                + "for(var k=0;k<links.length;k++)add(links[k],links[k].parentElement||document.body);}"
                + "return JSON.stringify(out);"
                + "}catch(e){return '[]';}})()";
        webView.evaluateJavascript(js, value -> {
            List<TubeItem> related = parseRelatedVideos(value, videoUrl);
            mainHandler.post(() -> {
                if (host != null) host.onAdFreePlay(videoUrl, pageTitle, related);
            });
        });
    }

    private List<TubeItem> parseRelatedVideos(String value, String currentVideoUrl) {
        List<TubeItem> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String currentId = ExtractorBridge.videoIdOf(currentVideoUrl);
        try {
            Object decoded = new JSONTokener(value == null ? "[]" : value).nextValue();
            String json = decoded instanceof String ? (String) decoded : String.valueOf(decoded);
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                org.json.JSONObject entry = array.optJSONObject(i);
                if (entry == null) continue;
                String url = normalizeVideoUrl(entry.optString("url", ""));
                String id = ExtractorBridge.videoIdOf(url);
                if (id.isEmpty() || id.equals(currentId) || !seen.add(id)) continue;
                String title = entry.optString("title", "").trim();
                if (title.isEmpty()) title = "YouTube video";
                items.add(new TubeItem(
                        title,
                        "YouTube related video",
                        url,
                        thumbnailUrlFor(url),
                        true,
                        url.contains("/shorts/")
                ));
            }
        } catch (Exception ignored) {
            // A YouTube DOM change must not prevent the selected video from playing.
        }
        return items;
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
                if (host != null) host.onNotVideoPage();
            } else {
                hideVideoBar();
            }
            return;
        }
        boolean changed = !videoUrl.equals(currentVideoUrl);
        currentVideoUrl = videoUrl;
        if (changed) setVideoBarExpanded(false, false);
        if (currentTitle.isEmpty() || "YouTube".equals(currentTitle)) {
            currentTitle = "YouTube 영상";
        }
        showVideoBar(currentTitle);
        if (changed && host != null) host.onVideoDetected(videoUrl, currentTitle);
    }

    private void showVideoBar(String title) {
        videoBar.setVisibility(customFullscreenView == null ? VISIBLE : GONE);
        videoLabel.setText("영상 · " + (title == null ? "" : title));
    }

    private void hideVideoBar() {
        setVideoBarExpanded(false, false);
        videoBar.setVisibility(GONE);
    }

    private void setVideoBarExpanded(boolean expanded, boolean animate) {
        if (videoBarContent == null || videoBarToggle == null) return;
        videoBarExpanded = expanded;
        if (host != null) {
            host.onVideoPanelChanged(!currentVideoUrl.isEmpty(), expanded, animate);
        }
        videoBarToggle.setText(expanded ? "⌄" : "⌃");
        videoBarToggle.setContentDescription(expanded ? "재생 메뉴 닫기" : "재생 메뉴 열기");
        videoBarContent.animate().cancel();

        if (!animate) {
            videoBarContent.setAlpha(1f);
            videoBarContent.setTranslationY(0f);
            videoBarContent.setVisibility(expanded ? VISIBLE : GONE);
            return;
        }

        if (expanded) {
            videoBarContent.setAlpha(0f);
            videoBarContent.setTranslationY(dp(14));
            videoBarContent.setVisibility(VISIBLE);
            videoBarContent.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(180)
                    .start();
        } else {
            videoBarContent.animate()
                    .alpha(0f)
                    .translationY(dp(14))
                    .setDuration(150)
                    .withEndAction(() -> {
                        if (!videoBarExpanded) {
                            videoBarContent.setVisibility(GONE);
                            videoBarContent.setAlpha(1f);
                            videoBarContent.setTranslationY(0f);
                        }
                    })
                    .start();
        }
    }

    private void injectAdShield() {
        if (webView == null) return;
        String js = "(function(){try{"
                + "var css='ytd-ad-slot-renderer,.ytp-ad-module,.video-ads,"
                + ".ytp-ad-player-overlay,.ytp-ad-overlay-container,"
                + ".ytp-ad-image-overlay,.ytp-ad-action-interstitial,"
                + ".ytp-ad-player-overlay-instream-info,.ytp-ad-survey,"
                + "ytd-promoted-sparkles-web-renderer,ytd-display-ad-renderer,"
                + "ytd-action-companion-ad-renderer,ytd-in-feed-ad-layout-renderer,"
                + "ytd-banner-promo-renderer,ytm-companion-ad-renderer,"
                + "ytm-promoted-video-renderer,#player-ads,.ad-container,"
                + "ytm-promoted-sparkles-text-search-renderer,"
                + "ytm-promoted-sparkles-web-renderer{display:none!important;height:0!important}';"
                + "if(!document.getElementById('mytube-ad-css')){"
                + "var s=document.createElement('style');s.id='mytube-ad-css';s.textContent=css;"
                + "document.documentElement.appendChild(s);}"
                + "window.__mytubeAdShield=function(){try{"
                + "var buttons=document.querySelectorAll("
                + "'.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,"
                + ".ytp-ad-skip-button-container button,.ytp-ad-overlay-close-button,"
                + "button[aria-label*=\\\"Skip\\\"],button[aria-label*=\\\"건너뛰기\\\"]');"
                + "for(var i=0;i<buttons.length;i++){try{buttons[i].click()}catch(x){}}"
                + "var player=document.querySelector('#movie_player,.html5-video-player');"
                + "var showing=!!(player&&(player.classList.contains('ad-showing')"
                + "||player.classList.contains('ad-interrupting')));"
                + "var state=window.__mytubeAdPlayback;"
                + "if(showing){var video=player.querySelector('video');"
                + "if(video){if(!state||state.video!==video){state={video:video,"
                + "muted:video.muted,volume:video.volume,rate:video.playbackRate};"
                + "window.__mytubeAdPlayback=state;}"
                + "try{video.muted=true;video.volume=0;video.playbackRate=16;}catch(x){}"
                + "try{var d=video.duration;if(isFinite(d)&&d>0.1){video.currentTime=d;}}catch(x){}}"
                + "for(var j=0;j<buttons.length;j++){try{buttons[j].click()}catch(x){}}"
                + "}else if(state){try{if(state.video){state.video.muted=state.muted;"
                + "state.video.volume=state.volume;state.video.playbackRate=state.rate||1;}}catch(x){}"
                + "window.__mytubeAdPlayback=null;}"
                + "}catch(e){}};"
                + "if(!window.__mytubeAdTimer){window.__mytubeAdTimer=setInterval("
                + "window.__mytubeAdShield,250);"
                + "document.addEventListener('yt-navigate-finish',window.__mytubeAdShield,true);"
                + "document.addEventListener('loadedmetadata',window.__mytubeAdShield,true);}"
                + "window.__mytubeAdShield();"
                + "}catch(e){}})();";
        webView.evaluateJavascript(js, null);
    }

    private void injectBackgroundPlayback() {
        if (webView == null) return;
        String js = "(function(){try{if(window.__mytubeBgInstalled)return;"
                + "window.__mytubeBgInstalled=true;"
                + "var nativePause=HTMLMediaElement.prototype.pause;"
                + "HTMLMediaElement.prototype.pause=function(){"
                + "if(window.__mytubeBgArmed&&window.__mytubeBgVideo===this)return;"
                + "return nativePause.apply(this,arguments);};"
                + "window.__mytubeBgKeepAlive=function(){try{"
                + "var v=window.__mytubeBgVideo;"
                + "if(window.__mytubeBgArmed&&v&&!v.ended&&v.paused){"
                + "var p=v.play();if(p&&p.catch)p.catch(function(){});}"
                + "}catch(e){}};"
                + "if(!window.__mytubeBgTimer){window.__mytubeBgTimer=setInterval("
                + "window.__mytubeBgKeepAlive,750);}"
                + "document.addEventListener('play',function(e){"
                + "if(e.target instanceof HTMLMediaElement){"
                + "window.__mytubeLastPlaying=e.target;}},true);"
                + "document.addEventListener('visibilitychange',function(){"
                + "if(document.hidden){var v=window.__mytubeLastPlaying||"
                + "document.querySelector('#movie_player video,video.html5-main-video,video');"
                + "if(v&&!v.paused&&!v.ended){window.__mytubeBgVideo=v;"
                + "window.__mytubeBgArmed=true;}"
                + "if(window.__mytubeBgArmed){"
                + "Promise.resolve().then(window.__mytubeBgKeepAlive);"
                + "setTimeout(window.__mytubeBgKeepAlive,100);}}},true);"
                + "}catch(e){}})();";
        webView.evaluateJavascript(js, null);
    }

    private static boolean isAdResourceUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        String lower = url.toLowerCase(java.util.Locale.US);
        return lower.contains("googleads.g.doubleclick.net")
                || lower.contains("ad.doubleclick.net")
                || lower.contains("static.doubleclick.net")
                || lower.contains("googleadservices.com")
                || lower.contains("googlesyndication.com")
                || lower.contains("imasdk.googleapis.com")
                || lower.contains("/pagead/")
                || lower.contains("/api/stats/ads")
                || lower.contains("/ptracking")
                || lower.contains("/get_midroll_info");
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
