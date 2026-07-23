package com.mytube.apk;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Rational;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.TrackSelectionDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    public enum Tab { YOUTUBE, HOME, VIDEOS, SHORTS, CHANNELS }

    private static final int SCREEN_SEARCH = 0;
    private static final int SCREEN_DOWNLOADS = 1;
    private static final int SCREEN_SETTINGS = 2;
    private static final int REQUEST_FOLDER = 42;
    private static final int REQUEST_LOGIN = 43;
    private static final int REQUEST_COOKIES_FILE = 44;

    private static final class SortOption {
        final String label;
        final ExtractorBridge.SortOrder order;
        SortOption(String label, ExtractorBridge.SortOrder order) {
            this.label = label;
            this.order = order;
        }
    }

    private static final class QualityOption {
        final String label;
        final String value;
        final int targetHeight;

        QualityOption(String label, String value, int targetHeight) {
            this.label = label;
            this.value = value;
            this.targetHeight = targetHeight;
        }
    }

    private static final SortOption[] SORTS = {
            new SortOption("관련성", ExtractorBridge.SortOrder.RELEVANCE),
            new SortOption("최신", ExtractorBridge.SortOrder.DATE),
            new SortOption("평점", ExtractorBridge.SortOrder.RATING),
    };

    private static final QualityOption[] QUALITY_OPTIONS = {
            new QualityOption("최저", DownloadStore.QUALITY_LOWEST, -1),
            new QualityOption("420p", "420", 420),
            new QualityOption("540p", "540", 540),
            new QualityOption("720p", "720", 720),
            new QualityOption("1080p", "1080", 1080),
            new QualityOption("최고", DownloadStore.QUALITY_HIGHEST, -2),
    };

    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable offlinePositionSaver = new Runnable() {
        @Override
        public void run() {
            if (!offlinePlaying || currentOfflineItemId.isEmpty()) return;
            persistOfflinePlaybackPosition();
            if (player != null && player.isPlaying()) {
                mainHandler.postDelayed(this, 5_000L);
            }
        }
    };
    private final List<Button> sortButtons = new ArrayList<>();
    private final List<Button> navButtons = new ArrayList<>();
    private final List<TubeItem> results = new ArrayList<>();
    private final List<TubeItem> webRelatedResults = new ArrayList<>();

    private ImageLoader imageLoader;
    private ExoPlayer player;

    // Screens.
    private LinearLayout searchScreen;
    private LinearLayout downloadScreen;
    private LinearLayout settingsScreen;
    private LinearLayout bottomNav;
    private LinearLayout appRoot;
    private TextView downloadStatus;
    private int currentScreen = SCREEN_SEARCH;
    private int playerReturnScreen = SCREEN_SEARCH;

    // YouTube site (WebView) / list mode + ad-free player overlay.
    private YoutubeWebPane youtubeWeb;
    private FrameLayout youtubeStage;
    private LinearLayout webModeRoot;
    private LinearLayout listModeRoot;
    private PlayerView playerView;
    private LinearLayout playerChrome;
    private LinearLayout playerLayer;
    private TextView playerTitleView;
    private Button qualityButton;
    private Button playerDownloadButton;
    private Button viewWebButton;
    private Button viewListButton;
    private TextView youtubeViewModeText;
    private DownloadQueue downloadQueue;

    // Legacy search UI fields kept nullable for offline helpers (unused in web mode).
    private EditText searchInput;
    private LinearLayout searchRow;
    private LinearLayout sortRow;
    private TextView statusView;
    private ScrollView resultsScroll;
    private LinearLayout resultsList;
    private LinearLayout playerBar;
    private Button downloadButton;
    private ScrollView metaScroll;
    private TextView metaView;

    // Downloads / settings views.
    private LinearLayout downloadList;
    private TextView downloadEmpty;
    private EditText downloadSearchInput;
    private TextView folderText;
    private TextView cookieStatusText;
    private TextView nextPlayOrderText;
    private Button sequentialOrderButton;
    private Button randomOrderButton;
    private TextView defaultQualityText;
    private final List<Button> defaultQualityButtons = new ArrayList<>();

    private ExtractorBridge.SortOrder currentSort = ExtractorBridge.SortOrder.RELEVANCE;
    private ExtractorBridge.SearchSession session;
    private TubeItem currentItem;
    private PlaybackData currentPlaybackData;
    private final Random random = new Random();
    private final Set<String> autoplayPlayedKeys = new HashSet<>();

    private boolean fullscreen;
    private boolean webFullscreen;
    private boolean restoreDownloadStatusAfterWebFullscreen;
    private boolean playing;
    private boolean offlinePlaying;
    private boolean loadingMore;
    private boolean preparingAutoplay;
    private boolean webAutoplayActive;
    private boolean defaultQualityApplied;
    private boolean playerControlsVisible = true;
    private String currentOfflineItemId = "";
    private int searchToken;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CookieStore.init(this);
        imageLoader = new ImageLoader(executor, mainHandler);
        downloadQueue = new DownloadQueue(this);
        downloadQueue.setListener(new DownloadQueue.Listener() {
            @Override
            public void onStatus(String message) {
                showDownloadStatus(message);
            }

            @Override
            public void onQueueChanged(int pending, boolean active) {
                // keep banner visible while queue works
            }

            @Override
            public void onItemCompleted(DownloadItem item) {
                if (currentScreen == SCREEN_DOWNLOADS) refreshDownloadList();
                mainHandler.postDelayed(() -> {
                    if (downloadQueue != null && !downloadQueue.isActive()
                            && downloadQueue.pendingCount() == 0) {
                        hideDownloadStatus();
                    }
                }, 4000);
            }

            @Override
            public void onItemFailed(String title, String error) {
                showDownloadStatus("✗ " + title + " · " + error);
            }
        });
        player = new ExoPlayer.Builder(this).build();
        setContentView(buildUi());
        // Reflect the resolution currently playing on the 화질 button.
        player.addListener(new Player.Listener() {
            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                updateQualityLabel();
            }

            @Override
            public void onTracksChanged(Tracks tracks) {
                updateQualityLabel();
                applyDefaultQualityIfNeeded(tracks);
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_ENDED) {
                    if (offlinePlaying && !currentOfflineItemId.isEmpty()) {
                        DownloadStore.clearPlaybackPosition(MainActivity.this, currentOfflineItemId);
                    }
                    maybeAutoPlayNext();
                }
                updateKeepScreenOnForPlayer();
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (offlinePlaying) {
                    if (isPlaying) {
                        scheduleOfflinePositionSave();
                    } else {
                        persistOfflinePlaybackPosition();
                        mainHandler.removeCallbacks(offlinePositionSaver);
                    }
                }
                updateKeepScreenOnForPlayer();
            }
        });
        showScreen(SCREEN_SEARCH);
        applyYoutubeViewMode(false);
    }

    private void updateQualityLabel() {
        if (qualityButton == null) return;
        int height = player.getVideoSize().height;
        qualityButton.setText(height > 0 ? "화질 " + height + "p" : "화질");
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        appRoot = root;
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(248, 249, 251));
        // targetSdk 35 draws edge-to-edge, so pad by the system bar insets to keep
        // the search bar out from under the status bar and the nav out from under
        // the gesture bar.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (webFullscreen || fullscreen) {
                v.setPadding(0, 0, 0, 0);
            } else {
                v.setPadding(0, insets.getSystemWindowInsetTop(), 0,
                        insets.getSystemWindowInsetBottom());
            }
            return insets;
        });

        FrameLayout content = new FrameLayout(this);
        searchScreen = buildSearchScreen();
        downloadScreen = buildDownloadScreen();
        settingsScreen = buildSettingsScreen();
        content.addView(searchScreen);
        content.addView(downloadScreen);
        content.addView(settingsScreen);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        // Persistent download progress banner (visible across all screens).
        downloadStatus = new TextView(this);
        downloadStatus.setVisibility(View.GONE);
        downloadStatus.setBackgroundColor(Color.rgb(30, 41, 59));
        downloadStatus.setTextColor(Color.WHITE);
        downloadStatus.setTextSize(13);
        downloadStatus.setPadding(dp(16), dp(10), dp(16), dp(10));
        downloadStatus.setOnClickListener(v -> hideDownloadStatus());
        root.addView(downloadStatus, new LinearLayout.LayoutParams(-1, -2));

        bottomNav = new LinearLayout(this);
        bottomNav.setOrientation(LinearLayout.HORIZONTAL);
        bottomNav.setBackgroundColor(Color.rgb(11, 15, 20));
        addNavButton("유튜브", SCREEN_SEARCH);
        addNavButton("다운로드", SCREEN_DOWNLOADS);
        addNavButton("설정", SCREEN_SETTINGS);
        root.addView(bottomNav, new LinearLayout.LayoutParams(-1, dp(60)));

        return root;
    }

    // ---- YouTube tab: web site mode OR classic list mode --------------------

    private LinearLayout buildSearchScreen() {
        // Outer shell: two modes stacked + shared ad-free player overlay.
        FrameLayout shell = new FrameLayout(this);
        shell.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        // Wrap in LinearLayout for searchScreen type field (visibility toggling).
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        screen.setBackgroundColor(Color.BLACK);
        screen.addView(shell, new LinearLayout.LayoutParams(-1, -1));

        webModeRoot = buildWebModeRoot();
        listModeRoot = buildListModeRoot();
        shell.addView(webModeRoot, new FrameLayout.LayoutParams(-1, -1));
        shell.addView(listModeRoot, new FrameLayout.LayoutParams(-1, -1));

        // Shared player overlay on top of either mode.
        playerLayer = new LinearLayout(this);
        playerLayer.setOrientation(LinearLayout.VERTICAL);
        playerLayer.setBackgroundColor(Color.BLACK);
        playerLayer.setVisibility(View.GONE);
        playerLayer.setTag("playerLayer");

        playerView = new PlayerView(this);
        playerView.setPlayer(player);
        playerView.setBackgroundColor(Color.BLACK);
        playerView.setShutterBackgroundColor(Color.BLACK);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        playerView.setFullscreenButtonClickListener(this::setFullscreen);
        playerView.setControllerShowTimeoutMs(3_500);
        playerLayer.addView(playerView, new LinearLayout.LayoutParams(-1, 0, 1));

        playerChrome = new LinearLayout(this);
        playerChrome.setOrientation(LinearLayout.HORIZONTAL);
        playerChrome.setGravity(Gravity.CENTER_VERTICAL);
        playerChrome.setPadding(dp(8), dp(6), dp(8), dp(14));
        GradientDrawable chromeFade = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(235, 0, 0, 0), Color.argb(175, 0, 0, 0), Color.TRANSPARENT});
        playerChrome.setBackground(chromeFade);

        Button closePlayer = new Button(this);
        closePlayer.setText("✕ 닫기");
        styleIntegratedPlayerButton(closePlayer, Color.WHITE);
        closePlayer.setOnClickListener(v -> closePlayer());
        playerChrome.addView(closePlayer, new LinearLayout.LayoutParams(dp(74), dp(44)));

        playerTitleView = new TextView(this);
        playerTitleView.setTextColor(Color.WHITE);
        playerTitleView.setTextSize(14);
        playerTitleView.setSingleLine(true);
        playerTitleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        playerTitleView.setPadding(dp(6), 0, dp(6), 0);
        playerChrome.addView(playerTitleView, new LinearLayout.LayoutParams(0, dp(44), 1));

        qualityButton = new Button(this);
        qualityButton.setText("화질");
        styleIntegratedPlayerButton(qualityButton, Color.WHITE);
        qualityButton.setOnClickListener(v -> showQualityDialog());
        playerChrome.addView(qualityButton, new LinearLayout.LayoutParams(dp(86), dp(44)));

        playerDownloadButton = new Button(this);
        playerDownloadButton.setText("저장");
        styleIntegratedPlayerButton(playerDownloadButton, Color.rgb(255, 145, 145));
        playerDownloadButton.setOnClickListener(v -> onDownloadClicked());
        playerChrome.addView(playerDownloadButton, new LinearLayout.LayoutParams(dp(64), dp(44)));

        FrameLayout playerOverlay = playerView.getOverlayFrameLayout();
        FrameLayout.LayoutParams chromeLp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        if (playerOverlay != null) playerOverlay.addView(playerChrome, chromeLp);
        else playerView.addView(playerChrome, chromeLp);
        playerView.setControllerVisibilityListener(
                (PlayerView.ControllerVisibilityListener) visibility -> {
                    playerControlsVisible = visibility == View.VISIBLE;
                    updatePlayerChromeVisibility();
                });
        shell.addView(playerLayer, new FrameLayout.LayoutParams(-1, -1));

        downloadButton = playerDownloadButton;
        metaView = playerTitleView;
        metaScroll = null;
        playerBar = playerChrome;

        return screen;
    }

    private void styleIntegratedPlayerButton(Button button, int textColor) {
        button.setAllCaps(false);
        button.setTextColor(textColor);
        button.setTextSize(12);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(4), 0, dp(4), 0);
        button.setBackgroundColor(Color.TRANSPARENT);
    }

    private LinearLayout buildWebModeRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        youtubeStage = new FrameLayout(this);
        youtubeWeb = new YoutubeWebPane(this);
        youtubeWeb.setHost(new YoutubeWebPane.Host() {
            @Override public void onVideoDetected(String videoUrl, String pageTitle) {}
            @Override public void onNotVideoPage() {}
            @Override
            public void onAdFreePlay(String videoUrl, String pageTitle,
                                     List<TubeItem> relatedVideos) {
                playWebVideo(videoUrl, pageTitle, relatedVideos);
            }
            @Override
            public void onDownload(String videoUrl, String pageTitle) {
                downloadWebVideo(videoUrl, pageTitle);
            }
            @Override
            public void onFullscreenChanged(boolean fullscreen) {
                setWebFullscreen(fullscreen);
            }
        });
        youtubeStage.addView(youtubeWeb, new FrameLayout.LayoutParams(-1, -1));
        root.addView(youtubeStage, new LinearLayout.LayoutParams(-1, -1));
        return root;
    }

    private LinearLayout buildListModeRoot() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(Color.rgb(248, 249, 251));

        searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setPadding(dp(12), dp(10), dp(12), dp(8));

        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setHint("검색어 (비우면 추천/인기)");
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchInput.setTextColor(Color.rgb(15, 23, 42));
        searchInput.setHintTextColor(Color.rgb(100, 116, 139));
        searchInput.setBackgroundColor(Color.WHITE);
        searchInput.setPadding(dp(12), 0, dp(12), 0);
        searchInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch();
                return true;
            }
            return false;
        });
        searchRow.addView(searchInput, new LinearLayout.LayoutParams(0, dp(46), 1));

        Button searchButton = new Button(this);
        searchButton.setText("검색");
        searchButton.setOnClickListener(v -> runSearch());
        searchRow.addView(searchButton, new LinearLayout.LayoutParams(dp(78), dp(46)));
        screen.addView(searchRow);

        sortRow = new LinearLayout(this);
        sortRow.setOrientation(LinearLayout.HORIZONTAL);
        sortRow.setPadding(dp(10), 0, dp(10), dp(6));
        for (SortOption option : SORTS) addSortButton(sortRow, option);
        screen.addView(sortRow);

        statusView = new TextView(this);
        statusView.setTextColor(Color.rgb(71, 85, 105));
        statusView.setTextSize(14);
        statusView.setPadding(dp(16), dp(8), dp(16), dp(8));
        screen.addView(statusView, new LinearLayout.LayoutParams(-1, dp(42)));

        resultsScroll = new ScrollView(this);
        resultsList = new LinearLayout(this);
        resultsList.setOrientation(LinearLayout.VERTICAL);
        resultsList.setPadding(dp(10), 0, dp(10), dp(10));
        resultsScroll.addView(resultsList);
        resultsScroll.setOnScrollChangeListener((v, sx, sy, osx, osy) -> {
            View child = resultsScroll.getChildAt(0);
            if (child == null) return;
            int remaining = child.getHeight() - (resultsScroll.getHeight() + sy);
            if (remaining < dp(600)) maybeLoadMore();
        });
        screen.addView(resultsScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        return screen;
    }

    private void applyYoutubeViewMode(boolean fromSettings) {
        String mode = DownloadStore.getYoutubeViewMode(this);
        boolean web = DownloadStore.VIEW_WEB.equals(mode);
        if (webModeRoot != null) webModeRoot.setVisibility(web ? View.VISIBLE : View.GONE);
        if (listModeRoot != null) listModeRoot.setVisibility(web ? View.GONE : View.VISIBLE);
        if (web) {
            if (youtubeWeb != null) youtubeWeb.start();
        } else {
            if (youtubeWeb != null) youtubeWeb.onPause();
            if (fromSettings || (results != null && results.isEmpty())) {
                runSearch();
            }
        }
        refreshYoutubeViewModeUi();
    }

    private View playerLayerView() {
        return playerLayer;
    }

    private boolean isWebViewMode() {
        return DownloadStore.VIEW_WEB.equals(DownloadStore.getYoutubeViewMode(this));
    }

    /** Full app player (settings quality + no ads). Triggered by bottom «광고없이 재생». */
    private void playWebVideo(String videoUrl, String title, List<TubeItem> relatedVideos) {
        if (videoUrl == null || videoUrl.isEmpty()) return;
        String thumb = YoutubeWebPane.thumbnailUrlFor(videoUrl);
        TubeItem item = new TubeItem(
                title == null || title.isEmpty() ? "YouTube 영상" : title,
                "YouTube",
                videoUrl,
                thumb,
                true,
                videoUrl.contains("/shorts/")
        );
        webRelatedResults.clear();
        if (relatedVideos != null) webRelatedResults.addAll(relatedVideos);
        webAutoplayActive = true;
        // Force full overlay player path (not list-mode branch only).
        playFullOverlay(item, true);
    }

    private void downloadWebVideo(String videoUrl, String title) {
        if (videoUrl == null || videoUrl.isEmpty()) {
            Toast.makeText(this, "다운로드할 영상이 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        String thumb = YoutubeWebPane.thumbnailUrlFor(videoUrl);
        TubeItem item = new TubeItem(
                title == null || title.isEmpty() ? "YouTube 영상" : title,
                "YouTube",
                videoUrl,
                thumb,
                true,
                videoUrl.contains("/shorts/")
        );
        currentItem = item;
        onDownloadClicked();
    }

    private Button addPlayerBarButton(String label, View.OnClickListener onClick) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setPadding(dp(6), 0, dp(6), 0);
        button.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(40));
        lp.setMargins(dp(3), 0, 0, 0);
        playerBar.addView(button, lp);
        return button;
    }

    private void addSortButton(LinearLayout parent, SortOption option) {
        Button button = new Button(this);
        button.setText(option.label);
        button.setTextSize(11);
        button.setAllCaps(false);
        button.setPadding(0, 0, 0, 0);
        button.setOnClickListener(v -> {
            if (currentSort == option.order) return;
            currentSort = option.order;
            refreshSortButtons();
            runSearch();
        });
        sortButtons.add(button);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(38), 1);
        lp.setMargins(dp(3), 0, dp(3), 0);
        parent.addView(button, lp);
    }

    private void refreshSortButtons() {
        for (int i = 0; i < sortButtons.size(); i++) {
            boolean selected = SORTS[i].order == currentSort;
            Button button = sortButtons.get(i);
            button.setTextColor(selected ? Color.WHITE : Color.rgb(71, 85, 105));
            button.setBackgroundColor(selected ? Color.rgb(229, 57, 53) : Color.rgb(226, 232, 240));
        }
    }

    // ---- Downloads screen --------------------------------------------------

    private LinearLayout buildDownloadScreen() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setVisibility(View.GONE);
        screen.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));

        TextView title = new TextView(this);
        title.setText("다운로드");
        title.setTextColor(Color.rgb(15, 23, 42));
        title.setTextSize(20);
        title.setPadding(dp(16), dp(14), dp(16), dp(10));
        screen.addView(title);

        LinearLayout downloadSearchRow = new LinearLayout(this);
        downloadSearchRow.setOrientation(LinearLayout.HORIZONTAL);
        downloadSearchRow.setPadding(dp(12), 0, dp(12), dp(8));

        downloadSearchInput = new EditText(this);
        downloadSearchInput.setSingleLine(true);
        downloadSearchInput.setHint("다운로드 검색");
        downloadSearchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        downloadSearchInput.setTextColor(Color.rgb(15, 23, 42));
        downloadSearchInput.setHintTextColor(Color.rgb(100, 116, 139));
        downloadSearchInput.setBackgroundColor(Color.WHITE);
        downloadSearchInput.setPadding(dp(12), 0, dp(12), 0);
        downloadSearchInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                refreshDownloadList();
                return true;
            }
            return false;
        });
        downloadSearchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                refreshDownloadList();
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
        downloadSearchRow.addView(downloadSearchInput, new LinearLayout.LayoutParams(0, dp(46), 1));

        Button downloadSearchButton = new Button(this);
        downloadSearchButton.setText("검색");
        downloadSearchButton.setOnClickListener(v -> refreshDownloadList());
        downloadSearchRow.addView(downloadSearchButton, new LinearLayout.LayoutParams(dp(78), dp(46)));
        screen.addView(downloadSearchRow);

        downloadEmpty = new TextView(this);
        downloadEmpty.setText("다운로드한 영상이 없습니다.");
        downloadEmpty.setTextColor(Color.rgb(100, 116, 139));
        downloadEmpty.setPadding(dp(16), dp(8), dp(16), dp(8));
        screen.addView(downloadEmpty);

        ScrollView scroll = new ScrollView(this);
        downloadList = new LinearLayout(this);
        downloadList.setOrientation(LinearLayout.VERTICAL);
        downloadList.setPadding(dp(10), 0, dp(10), dp(10));
        scroll.addView(downloadList);
        screen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        return screen;
    }

    private void refreshDownloadList() {
        downloadList.removeAllViews();
        List<DownloadItem> items = DownloadStore.list(this);
        String query = downloadSearchInput == null ? "" : downloadSearchInput.getText().toString().trim();
        int shown = 0;
        for (DownloadItem item : items) {
            if (!matchesDownloadQuery(item, query)) continue;
            downloadList.addView(createDownloadRow(item));
            shown++;
        }
        downloadEmpty.setText(items.isEmpty() ? "다운로드한 영상이 없습니다." : "검색 결과가 없습니다.");
        downloadEmpty.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
    }

    private boolean matchesDownloadQuery(DownloadItem item, String query) {
        if (query == null || query.isEmpty()) return true;
        String needle = query.toLowerCase();
        String haystack = (item.title + "\n" + item.uploader + "\n"
                + item.quality + "\n" + item.searchText).toLowerCase();
        return haystack.contains(needle);
    }

    private LinearLayout createDownloadRow(DownloadItem item) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(8), dp(8), dp(8));
        row.setBackgroundColor(Color.WHITE);

        ImageView thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackgroundColor(Color.rgb(226, 232, 240));
        String thumbUrl = item.thumbnailUrl;
        if (thumbUrl == null || thumbUrl.isEmpty()) {
            // Older downloads / web queue without URL — recover from video id.
            thumbUrl = YoutubeWebPane.thumbnailUrlFor("https://www.youtube.com/watch?v=" + item.id);
            if (thumbUrl.isEmpty() && item.id != null && item.id.length() == 11) {
                thumbUrl = "https://i.ytimg.com/vi/" + item.id + "/hqdefault.jpg";
            }
        }
        imageLoader.load(thumbUrl, thumb);
        row.addView(thumb, new LinearLayout.LayoutParams(dp(120), dp(68)));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setPadding(dp(12), 0, dp(4), 0);

        TextView title = new TextView(this);
        title.setText(item.title);
        title.setTextColor(Color.rgb(15, 23, 42));
        title.setTextSize(15);
        title.setMaxLines(2);
        textCol.addView(title);

        TextView sub = new TextView(this);
        String subText = item.uploader;
        if (!item.quality.isEmpty()) subText = subText.isEmpty() ? item.quality : item.uploader + " · " + item.quality;
        sub.setText(subText + " · 오프라인");
        sub.setTextColor(Color.rgb(100, 116, 139));
        sub.setTextSize(12);
        sub.setMaxLines(1);
        textCol.addView(sub);

        row.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1));

        Button delete = new Button(this);
        delete.setText("삭제");
        delete.setAllCaps(false);
        delete.setTextSize(12);
        delete.setOnClickListener(v -> confirmDelete(item));
        row.addView(delete, new LinearLayout.LayoutParams(-2, dp(40)));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 0, 0, dp(8));
        row.setLayoutParams(params);
        row.setOnClickListener(v -> playOffline(item));
        return row;
    }

    private void confirmDelete(DownloadItem item) {
        new AlertDialog.Builder(this)
                .setTitle("삭제")
                .setMessage("이 영상을 삭제할까요? 저장된 파일도 함께 삭제됩니다.")
                .setPositiveButton("삭제", (d, w) -> {
                    if (offlinePlaying && currentOfflineItemId.equals(item.id)) {
                        closePlayer();
                    }
                    DownloadItem removed = DownloadStore.remove(this, item.id);
                    if (removed != null) MediaDownloader.deleteFile(this, removed.uri);
                    refreshDownloadList();
                    Toast.makeText(this, "삭제되었습니다.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void playOffline(DownloadItem item) {
        persistOfflinePlaybackPosition();
        mainHandler.removeCallbacks(offlinePositionSaver);
        playerReturnScreen = currentScreen;
        currentItem = null;
        currentPlaybackData = null;
        autoplayPlayedKeys.clear();
        preparingAutoplay = false;
        offlinePlaying = true;
        currentOfflineItemId = item.id == null ? "" : item.id;
        playing = true;
        showScreen(SCREEN_SEARCH);
        showPlayingLayout(true);
        if (playerDownloadButton != null) playerDownloadButton.setVisibility(View.GONE);
        if (playerTitleView != null) {
            playerTitleView.setText(item.title + (item.uploader.isEmpty() ? "" : " · " + item.uploader)
                    + "\n오프라인 재생");
        }
        player.setMediaItem(buildMediaItem(item.uri));
        long resumePosition = DownloadStore.getPlaybackPosition(this, currentOfflineItemId);
        if (resumePosition > 0L) player.seekTo(resumePosition);
        resetDefaultQualityForNewMedia();
        player.prepare();
        player.play();
        scheduleOfflinePositionSave();
        updateKeepScreenOnForPlayer();
    }

    private void scheduleOfflinePositionSave() {
        mainHandler.removeCallbacks(offlinePositionSaver);
        if (offlinePlaying && player != null && player.isPlaying()) {
            mainHandler.postDelayed(offlinePositionSaver, 5_000L);
        }
    }

    private void persistOfflinePlaybackPosition() {
        if (!offlinePlaying || currentOfflineItemId.isEmpty() || player == null) return;
        DownloadStore.setPlaybackPosition(this, currentOfflineItemId,
                player.getCurrentPosition(), player.getDuration());
    }

    // ---- Settings screen ---------------------------------------------------

    private LinearLayout buildSettingsScreen() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setVisibility(View.GONE);
        screen.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));

        TextView title = new TextView(this);
        title.setText("설정");
        title.setTextColor(Color.rgb(15, 23, 42));
        title.setTextSize(20);
        title.setPadding(dp(16), dp(14), dp(16), dp(10));
        screen.addView(title);

        TextView version = new TextView(this);
        version.setText("버전 My Tube " + BuildConfig.VERSION_NAME);
        version.setTextColor(Color.rgb(71, 85, 105));
        version.setTextSize(14);
        version.setPadding(dp(16), 0, dp(16), dp(10));
        screen.addView(version);

        // YouTube tab view mode
        TextView viewModeLabel = new TextView(this);
        viewModeLabel.setText("유튜브 보기 방식");
        viewModeLabel.setTextColor(Color.rgb(15, 23, 42));
        viewModeLabel.setTextSize(15);
        viewModeLabel.setPadding(dp(16), dp(6), dp(16), dp(4));
        screen.addView(viewModeLabel);

        youtubeViewModeText = new TextView(this);
        youtubeViewModeText.setTextColor(Color.rgb(71, 85, 105));
        youtubeViewModeText.setTextSize(13);
        youtubeViewModeText.setPadding(dp(16), 0, dp(16), dp(8));
        screen.addView(youtubeViewModeText);

        LinearLayout viewModeRow = new LinearLayout(this);
        viewModeRow.setOrientation(LinearLayout.HORIZONTAL);
        viewModeRow.setPadding(dp(12), 0, dp(12), dp(12));

        viewWebButton = new Button(this);
        viewWebButton.setText("사이트 화면");
        viewWebButton.setAllCaps(false);
        viewWebButton.setOnClickListener(v -> setYoutubeViewMode(DownloadStore.VIEW_WEB));
        viewModeRow.addView(viewWebButton, new LinearLayout.LayoutParams(0, dp(46), 1));

        viewListButton = new Button(this);
        viewListButton.setText("목록·검색");
        viewListButton.setAllCaps(false);
        viewListButton.setOnClickListener(v -> setYoutubeViewMode(DownloadStore.VIEW_LIST));
        LinearLayout.LayoutParams listBtnLp = new LinearLayout.LayoutParams(0, dp(46), 1);
        listBtnLp.setMargins(dp(8), 0, 0, 0);
        viewModeRow.addView(viewListButton, listBtnLp);
        screen.addView(viewModeRow);

        // Temp cleanup
        TextView tempLabel = new TextView(this);
        tempLabel.setText("저장 공간");
        tempLabel.setTextColor(Color.rgb(15, 23, 42));
        tempLabel.setTextSize(15);
        tempLabel.setPadding(dp(16), dp(6), dp(16), dp(4));
        screen.addView(tempLabel);

        Button clearTemp = new Button(this);
        clearTemp.setText("임시 파일 제거");
        clearTemp.setAllCaps(false);
        clearTemp.setOnClickListener(v -> {
            int n = MediaDownloader.clearTempFiles(this);
            Toast.makeText(this,
                    n > 0 ? "임시 파일 " + n + "개 삭제했습니다." : "삭제할 임시 파일이 없습니다.",
                    Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams tempLp = new LinearLayout.LayoutParams(-2, dp(46));
        tempLp.setMargins(dp(16), 0, dp(16), dp(12));
        screen.addView(clearTemp, tempLp);

        // YouTube session cookies — the main reason PC survives large downloads.
        TextView cookieLabel = new TextView(this);
        cookieLabel.setText("YouTube 로그인 (차단 완화)");
        cookieLabel.setTextColor(Color.rgb(15, 23, 42));
        cookieLabel.setTextSize(15);
        cookieLabel.setPadding(dp(16), dp(6), dp(16), dp(4));
        screen.addView(cookieLabel);

        cookieStatusText = new TextView(this);
        cookieStatusText.setTextColor(Color.rgb(71, 85, 105));
        cookieStatusText.setTextSize(13);
        cookieStatusText.setPadding(dp(16), 0, dp(16), dp(8));
        screen.addView(cookieStatusText);

        TextView cookieHelp = new TextView(this);
        cookieHelp.setText("로그인: 차단 완화 + 사이트 화면 모드에서 내 계정 표시. "
                + "다운로드는 한 번에 하나씩 대기열로 순차 진행됩니다.");
        cookieHelp.setTextColor(Color.rgb(100, 116, 139));
        cookieHelp.setTextSize(12);
        cookieHelp.setPadding(dp(16), 0, dp(16), dp(8));
        screen.addView(cookieHelp);

        LinearLayout cookieRow = new LinearLayout(this);
        cookieRow.setOrientation(LinearLayout.HORIZONTAL);
        cookieRow.setPadding(dp(12), 0, dp(12), dp(6));

        Button loginBtn = new Button(this);
        loginBtn.setText("로그인");
        loginBtn.setAllCaps(false);
        loginBtn.setOnClickListener(v ->
                startActivityForResult(new Intent(this, YoutubeLoginActivity.class), REQUEST_LOGIN));
        cookieRow.addView(loginBtn, new LinearLayout.LayoutParams(0, dp(46), 1));

        Button importBtn = new Button(this);
        importBtn.setText("cookies.txt");
        importBtn.setAllCaps(false);
        importBtn.setOnClickListener(v -> pickCookiesFile());
        LinearLayout.LayoutParams importLp = new LinearLayout.LayoutParams(0, dp(46), 1);
        importLp.setMargins(dp(8), 0, 0, 0);
        cookieRow.addView(importBtn, importLp);
        screen.addView(cookieRow);

        Button clearCookie = new Button(this);
        clearCookie.setText("로그인 쿠키 삭제");
        clearCookie.setAllCaps(false);
        clearCookie.setOnClickListener(v -> {
            CookieStore.clear();
            refreshSettings();
            if (youtubeWeb != null) youtubeWeb.goHome();
            Toast.makeText(this, "쿠키 삭제 · 유튜브를 손님 모드로 다시 엽니다.", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(-2, dp(46));
        clearLp.setMargins(dp(16), 0, dp(16), dp(14));
        screen.addView(clearCookie, clearLp);

        TextView playOrderLabel = new TextView(this);
        playOrderLabel.setText("다음 영상 재생 순서");
        playOrderLabel.setTextColor(Color.rgb(15, 23, 42));
        playOrderLabel.setTextSize(15);
        playOrderLabel.setPadding(dp(16), dp(6), dp(16), dp(4));
        screen.addView(playOrderLabel);

        nextPlayOrderText = new TextView(this);
        nextPlayOrderText.setTextColor(Color.rgb(71, 85, 105));
        nextPlayOrderText.setTextSize(13);
        nextPlayOrderText.setPadding(dp(16), 0, dp(16), dp(8));
        screen.addView(nextPlayOrderText);

        LinearLayout playOrderRow = new LinearLayout(this);
        playOrderRow.setOrientation(LinearLayout.HORIZONTAL);
        playOrderRow.setPadding(dp(12), 0, dp(12), dp(12));

        sequentialOrderButton = new Button(this);
        sequentialOrderButton.setText("순차재생");
        sequentialOrderButton.setAllCaps(false);
        sequentialOrderButton.setOnClickListener(v -> setNextPlayOrder(DownloadStore.ORDER_SEQUENTIAL));
        playOrderRow.addView(sequentialOrderButton, new LinearLayout.LayoutParams(0, dp(46), 1));

        randomOrderButton = new Button(this);
        randomOrderButton.setText("랜덤재생");
        randomOrderButton.setAllCaps(false);
        randomOrderButton.setOnClickListener(v -> setNextPlayOrder(DownloadStore.ORDER_RANDOM));
        LinearLayout.LayoutParams randomLp = new LinearLayout.LayoutParams(0, dp(46), 1);
        randomLp.setMargins(dp(8), 0, 0, 0);
        playOrderRow.addView(randomOrderButton, randomLp);
        screen.addView(playOrderRow);

        TextView qualityLabel = new TextView(this);
        qualityLabel.setText("기본 화질");
        qualityLabel.setTextColor(Color.rgb(15, 23, 42));
        qualityLabel.setTextSize(15);
        qualityLabel.setPadding(dp(16), dp(6), dp(16), dp(4));
        screen.addView(qualityLabel);

        defaultQualityText = new TextView(this);
        defaultQualityText.setTextColor(Color.rgb(71, 85, 105));
        defaultQualityText.setTextSize(13);
        defaultQualityText.setPadding(dp(16), 0, dp(16), dp(8));
        screen.addView(defaultQualityText);

        LinearLayout qualityRowTop = new LinearLayout(this);
        qualityRowTop.setOrientation(LinearLayout.HORIZONTAL);
        qualityRowTop.setPadding(dp(12), 0, dp(12), dp(6));
        addDefaultQualityButton(qualityRowTop, QUALITY_OPTIONS[0]);
        addDefaultQualityButton(qualityRowTop, QUALITY_OPTIONS[1]);
        addDefaultQualityButton(qualityRowTop, QUALITY_OPTIONS[2]);
        screen.addView(qualityRowTop);

        LinearLayout qualityRowBottom = new LinearLayout(this);
        qualityRowBottom.setOrientation(LinearLayout.HORIZONTAL);
        qualityRowBottom.setPadding(dp(12), 0, dp(12), dp(12));
        addDefaultQualityButton(qualityRowBottom, QUALITY_OPTIONS[3]);
        addDefaultQualityButton(qualityRowBottom, QUALITY_OPTIONS[4]);
        addDefaultQualityButton(qualityRowBottom, QUALITY_OPTIONS[5]);
        screen.addView(qualityRowBottom);

        TextView label = new TextView(this);
        label.setText("다운로드 저장 폴더");
        label.setTextColor(Color.rgb(15, 23, 42));
        label.setTextSize(15);
        label.setPadding(dp(16), dp(8), dp(16), dp(4));
        screen.addView(label);

        folderText = new TextView(this);
        folderText.setTextColor(Color.rgb(71, 85, 105));
        folderText.setTextSize(13);
        folderText.setPadding(dp(16), 0, dp(16), dp(8));
        screen.addView(folderText);

        Button change = new Button(this);
        change.setText("폴더 변경");
        change.setAllCaps(false);
        change.setOnClickListener(v -> pickFolder());
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, dp(46));
        cp.setMargins(dp(16), dp(4), dp(16), dp(8));
        screen.addView(change, cp);

        Button reset = new Button(this);
        reset.setText("기본 폴더로 되돌리기");
        reset.setAllCaps(false);
        reset.setOnClickListener(v -> {
            DownloadStore.setFolderUri(this, "");
            refreshSettings();
            Toast.makeText(this, "기본 폴더로 설정했습니다.", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, dp(46));
        rp.setMargins(dp(16), 0, dp(16), dp(8));
        screen.addView(reset, rp);

        // Scroll the long settings page.
        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        scroll.setFillViewport(true);
        // Re-parent: screen was built as the root; wrap it.
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setVisibility(View.GONE);
        wrapper.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        // Move children already added to screen into a content column inside scroll.
        // Simpler: rebuild return as scroll containing screen content.
        // screen is already the content; just put it in scroll and return scroll container.
        screen.setVisibility(View.VISIBLE);
        screen.setLayoutParams(new ScrollView.LayoutParams(-1, -2));
        // Detach screen from any parent first if needed — none yet.
        scroll.addView(screen);
        wrapper.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        // Keep settingsScreen reference as the visibility-togglable wrapper.
        return wrapper;
    }

    private void setYoutubeViewMode(String mode) {
        DownloadStore.setYoutubeViewMode(this, mode);
        applyYoutubeViewMode(true);
        Toast.makeText(this,
                DownloadStore.VIEW_WEB.equals(mode)
                        ? "유튜브 탭: 사이트 화면 모드"
                        : "유튜브 탭: 목록·검색 모드",
                Toast.LENGTH_SHORT).show();
    }

    private void refreshYoutubeViewModeUi() {
        if (youtubeViewModeText == null) return;
        boolean web = DownloadStore.VIEW_WEB.equals(DownloadStore.getYoutubeViewMode(this));
        youtubeViewModeText.setText(web
                ? "사이트 화면: YouTube 페이지 + 하단 «광고없이 재생 / 대기열 등록»"
                : "목록·검색: 앱 안 검색 리스트에서 재생·대기열 등록");
        if (viewWebButton != null) styleChoiceButton(viewWebButton, web);
        if (viewListButton != null) styleChoiceButton(viewListButton, !web);
    }

    private void refreshSettings() {
        refreshYoutubeViewModeUi();
        if (cookieStatusText != null) {
            cookieStatusText.setText(CookieStore.statusText());
            cookieStatusText.setTextColor(CookieStore.hasAuthCookies()
                    ? Color.rgb(22, 163, 74) : Color.rgb(185, 28, 28));
        }
        if (folderText == null) return;
        String folder = DownloadStore.getFolderUri(this);
        if (folder == null || folder.isEmpty()) {
            File dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
            folderText.setText("앱 기본 폴더\n" + (dir == null ? "" : dir.getAbsolutePath()));
        } else {
            folderText.setText(Uri.decode(folder));
        }
        refreshNextPlayOrderUi();
        refreshDefaultQualityUi();
    }

    private void pickCookiesFile() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/plain", "text/*", "*/*"});
            startActivityForResult(intent, REQUEST_COOKIES_FILE);
        } catch (Exception e) {
            Toast.makeText(this, "파일 선택을 열 수 없습니다.", Toast.LENGTH_SHORT).show();
        }
    }

    private void importCookiesFromUri(Uri uri) {
        executor.execute(() -> {
            try {
                StringBuilder sb = new StringBuilder();
                try (java.io.InputStream in = getContentResolver().openInputStream(uri);
                     java.io.BufferedReader reader = new java.io.BufferedReader(
                             new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
                    if (in == null) throw new java.io.IOException("파일을 열 수 없습니다.");
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append('\n');
                        if (sb.length() > 2 * 1024 * 1024) {
                            throw new java.io.IOException("파일이 너무 큽니다.");
                        }
                    }
                }
                int count = CookieStore.importNetscape(sb.toString());
                mainHandler.post(() -> {
                    refreshSettings();
                    if (count <= 0 || !CookieStore.hasAuthCookies()) {
                        Toast.makeText(this,
                                "유효한 YouTube 로그인 쿠키를 찾지 못했습니다.\nPC data/cookies.txt 형식을 사용하세요.",
                                Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "cookies.txt에서 " + count + "개 쿠키를 가져왔습니다.",
                                Toast.LENGTH_SHORT).show();
                        CookieStore.pushToWebView();
                        if (youtubeWeb != null) youtubeWeb.reloadWithCookies();
                    }
                });
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                mainHandler.post(() ->
                        Toast.makeText(this, "가져오기 실패: " + msg, Toast.LENGTH_LONG).show());
            }
        });
    }

    private void addDefaultQualityButton(LinearLayout row, QualityOption option) {
        Button button = new Button(this);
        button.setText(option.label);
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setOnClickListener(v -> setDefaultQuality(option.value));
        defaultQualityButtons.add(button);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), 1);
        lp.setMargins(dp(3), 0, dp(3), 0);
        row.addView(button, lp);
    }

    private void setDefaultQuality(String quality) {
        DownloadStore.setDefaultQuality(this, quality);
        refreshDefaultQualityUi();
        Toast.makeText(this, "기본 화질을 " + defaultQualityLabel(quality) + "로 설정했습니다.", Toast.LENGTH_SHORT).show();
    }

    private void refreshDefaultQualityUi() {
        if (defaultQualityText == null || defaultQualityButtons.isEmpty()) return;
        String quality = DownloadStore.getDefaultQuality(this);
        defaultQualityText.setText("영상 재생 시 " + defaultQualityLabel(quality)
                + "에 가장 가까운 화질로 시작합니다.");
        for (int i = 0; i < defaultQualityButtons.size() && i < QUALITY_OPTIONS.length; i++) {
            styleChoiceButton(defaultQualityButtons.get(i), QUALITY_OPTIONS[i].value.equals(quality));
        }
    }

    private void setNextPlayOrder(String order) {
        DownloadStore.setNextPlayOrder(this, order);
        refreshNextPlayOrderUi();
        Toast.makeText(this,
                DownloadStore.ORDER_RANDOM.equals(order) ? "랜덤재생으로 설정했습니다." : "순차재생으로 설정했습니다.",
                Toast.LENGTH_SHORT).show();
    }

    private void refreshNextPlayOrderUi() {
        if (nextPlayOrderText == null || sequentialOrderButton == null || randomOrderButton == null) return;
        String order = DownloadStore.getNextPlayOrder(this);
        boolean randomOrder = DownloadStore.ORDER_RANDOM.equals(order);
        nextPlayOrderText.setText(randomOrder
                ? "검색 결과 안에서 이미 재생한 영상을 제외하고 랜덤으로 재생합니다."
                : "현재 영상 다음 결과부터 2번, 3번 순서로 계속 재생합니다.");
        styleChoiceButton(sequentialOrderButton, !randomOrder);
        styleChoiceButton(randomOrderButton, randomOrder);
    }

    private void styleChoiceButton(Button button, boolean selected) {
        button.setTextColor(selected ? Color.WHITE : Color.rgb(71, 85, 105));
        button.setBackgroundColor(selected ? Color.rgb(229, 57, 53) : Color.rgb(226, 232, 240));
    }

    private String defaultQualityLabel(String quality) {
        for (QualityOption option : QUALITY_OPTIONS) {
            if (option.value.equals(quality)) return option.label;
        }
        return "최고";
    }

    private void pickFolder() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(intent, REQUEST_FOLDER);
        } catch (Exception e) {
            Toast.makeText(this, "폴더 선택을 열 수 없습니다.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_FOLDER && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {
                // some providers don't support persistable permissions
            }
            DownloadStore.setFolderUri(this, uri.toString());
            refreshSettings();
            Toast.makeText(this, "저장 폴더가 설정되었습니다.", Toast.LENGTH_SHORT).show();
        } else if (requestCode == REQUEST_LOGIN) {
            refreshSettings();
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, "로그인 적용 · 유튜브 홈을 엽니다.", Toast.LENGTH_SHORT).show();
                // CookieManager already holds the live session from login WebView.
                if (youtubeWeb != null) youtubeWeb.reloadWithCookies();
            }
        } else if (requestCode == REQUEST_COOKIES_FILE && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            importCookiesFromUri(data.getData());
        }
    }

    // ---- Bottom navigation -------------------------------------------------

    private void addNavButton(String label, int screen) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setOnClickListener(v -> onNavClicked(screen));
        navButtons.add(button);
        bottomNav.addView(button, new LinearLayout.LayoutParams(0, -1, 1));
    }

    private void onNavClicked(int screen) {
        if (screen == SCREEN_SEARCH && currentScreen == SCREEN_SEARCH) {
            // Re-tapping 유튜브 closes player / refreshes current mode.
            closePlayer();
            if (DownloadStore.VIEW_WEB.equals(DownloadStore.getYoutubeViewMode(this))) {
                if (youtubeWeb != null) youtubeWeb.goHome();
            } else {
                runSearch();
            }
        }
        if (screen != SCREEN_SEARCH && playing) {
            persistOfflinePlaybackPosition();
            mainHandler.removeCallbacks(offlinePositionSaver);
            player.pause();
            updateKeepScreenOnForPlayer();
        }
        showScreen(screen);
    }

    private void showScreen(int screen) {
        currentScreen = screen;
        searchScreen.setVisibility(screen == SCREEN_SEARCH ? View.VISIBLE : View.GONE);
        downloadScreen.setVisibility(screen == SCREEN_DOWNLOADS ? View.VISIBLE : View.GONE);
        settingsScreen.setVisibility(screen == SCREEN_SETTINGS ? View.VISIBLE : View.GONE);
        for (int i = 0; i < navButtons.size(); i++) {
            boolean selected = i == screen;
            Button button = navButtons.get(i);
            button.setTextColor(selected ? Color.WHITE : Color.rgb(203, 213, 225));
            button.setBackgroundColor(selected ? Color.rgb(229, 57, 53) : Color.rgb(11, 15, 20));
        }
        if (screen == SCREEN_DOWNLOADS) refreshDownloadList();
        if (screen == SCREEN_SETTINGS) refreshSettings();
    }

    // ---- Search ------------------------------------------------------------

    private void runSearch() {
        final int token = ++searchToken;
        final String query = searchInput.getText().toString();
        results.clear();
        autoplayPlayedKeys.clear();
        preparingAutoplay = false;
        resultsList.removeAllViews();
        final boolean wantHome = query.trim().isEmpty()
                && currentSort == ExtractorBridge.SortOrder.RELEVANCE;
        if (wantHome && !CookieStore.hasAuthCookies()) {
            statusView.setText("맞춤 추천은 로그인이 필요합니다 · 설정에서 YouTube 로그인 후 당겨보세요");
        } else if (wantHome) {
            statusView.setText("맞춤 추천 불러오는 중…");
        } else {
            statusView.setText("불러오는 중...");
        }
        loadingMore = true;
        final ExtractorBridge.SortOrder requestedSort = currentSort;
        session = ExtractorBridge.newSearch(query, Tab.YOUTUBE, requestedSort);
        final ExtractorBridge.SearchSession active = session;
        final boolean homeFeed = active.isHomeFeed();
        executor.execute(() -> {
            try {
                List<TubeItem> batch = new ArrayList<>(active.loadMore());
                // Keep first-page fetch modest. Aggressive multi-page pulls (was 80
                // for DATE) were a major IP-burn source vs PC.
                int target = homeFeed ? 20 : 25;
                while (batch.size() < target && active.hasMore()) {
                    List<TubeItem> more = active.loadMore();
                    if (more.isEmpty()) break;
                    batch.addAll(more);
                }
                mainHandler.post(() -> {
                    if (token != searchToken) return;
                    onBatchLoaded(batch);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (token != searchToken) return;
                    loadingMore = false;
                    statusView.setText((homeFeed ? "추천 실패: " : "검색 실패: ") + e.getMessage());
                    Toast.makeText(this,
                            homeFeed ? "맞춤 추천을 불러오지 못했습니다. 로그인 상태를 확인해 주세요."
                                    : "검색에 실패했습니다.",
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void maybeLoadMore() {
        if (loadingMore || session == null || !session.hasMore()) return;
        loadingMore = true;
        final int token = searchToken;
        final ExtractorBridge.SearchSession active = session;
        executor.execute(() -> {
            try {
                List<TubeItem> batch = new ArrayList<>(active.loadMore());
                mainHandler.post(() -> {
                    if (token != searchToken) return;
                    onBatchLoaded(batch);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (token == searchToken) loadingMore = false;
                });
            }
        });
    }

    private void onBatchLoaded(List<TubeItem> batch) {
        results.addAll(batch);
        sortCurrentResults();
        renderResults();
        boolean home = session != null && session.isHomeFeed();
        if (results.isEmpty()) {
            if (home) {
                statusView.setText("맞춤 추천이 비었습니다. 로그인·네트워크를 확인하거나 검색어를 입력하세요.");
            } else if (searchInput.getText().toString().trim().isEmpty() && !CookieStore.hasAuthCookies()) {
                statusView.setText("결과가 없습니다 · 설정에서 로그인하면 맞춤 추천이 표시됩니다.");
            } else {
                statusView.setText("결과가 없습니다.");
            }
        } else {
            boolean more = session != null && session.hasMore();
            String label = home
                    ? results.size() + "개 맞춤 추천"
                    : results.size() + "개 결과";
            statusView.setText(label + (more ? " · 아래로 스크롤하면 더 보기" : ""));
        }
        loadingMore = false;
    }

    private void sortCurrentResults() {
        // Home feed order is personalized — don't re-sort by date.
        if (session != null && session.isHomeFeed()) return;
        if (currentSort == ExtractorBridge.SortOrder.DATE) {
            results.sort((a, b) -> Long.compare(a.publishedAgeSeconds, b.publishedAgeSeconds));
        }
    }

    private void renderResults() {
        resultsList.removeAllViews();
        for (TubeItem item : results) resultsList.addView(createResultRow(item));
    }

    private LinearLayout createResultRow(TubeItem item) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(8), dp(8), dp(8));
        row.setBackgroundColor(Color.WHITE);

        ImageView thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackgroundColor(Color.rgb(226, 232, 240));
        imageLoader.load(item.thumbnailUrl, thumb);
        row.addView(thumb, new LinearLayout.LayoutParams(dp(128), dp(item.shortForm ? 128 : 72)));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setPadding(dp(12), 0, dp(4), 0);

        TextView title = new TextView(this);
        title.setText(item.title);
        title.setTextColor(Color.rgb(15, 23, 42));
        title.setTextSize(15);
        title.setMaxLines(2);
        textCol.addView(title);

        TextView sub = new TextView(this);
        sub.setText(item.subtitle);
        sub.setTextColor(Color.rgb(100, 116, 139));
        sub.setTextSize(12);
        sub.setMaxLines(2);
        textCol.addView(sub);

        row.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 0, 0, dp(8));
        row.setLayoutParams(params);
        row.setOnClickListener(v -> {
            if (!item.playable) {
                Toast.makeText(this, "채널은 재생할 수 없습니다.", Toast.LENGTH_SHORT).show();
                return;
            }
            play(item, true);
        });
        return row;
    }

    // ---- Playback ----------------------------------------------------------

    private void play(TubeItem item) {
        play(item, true);
    }

    private void play(TubeItem item, boolean resetAutoplayHistory) {
        if (resetAutoplayHistory) webAutoplayActive = false;
        playFullOverlay(item, resetAutoplayHistory);
    }

    private void playFullOverlay(TubeItem item, boolean resetAutoplayHistory) {
        persistOfflinePlaybackPosition();
        mainHandler.removeCallbacks(offlinePositionSaver);
        playerReturnScreen = SCREEN_SEARCH;
        currentOfflineItemId = "";
        currentItem = item;
        currentPlaybackData = null;
        offlinePlaying = false;
        playing = true;
        if (resetAutoplayHistory) preparingAutoplay = false;
        if (resetAutoplayHistory) autoplayPlayedKeys.clear();
        markAutoplayPlayed(item);
        if (playerView != null) playerView.setPlayer(player);
        showPlayingLayout(true);
        if (playerDownloadButton != null) playerDownloadButton.setVisibility(View.VISIBLE);
        if (playerTitleView != null) playerTitleView.setText("재생 준비 중: " + item.title);
        executor.execute(() -> {
            try {
                PlaybackData data = ExtractorBridge.resolve(item.url);
                mainHandler.post(() -> startPlayer(data));
            } catch (Exception e) {
                mainHandler.post(() -> {
                    preparingAutoplay = false;
                    updateKeepScreenOnForPlayer();
                    if (playerTitleView != null) {
                        playerTitleView.setText("재생 실패: " + e.getMessage());
                    }
                    Toast.makeText(this, "재생 가능한 스트림을 찾지 못했습니다.", Toast.LENGTH_LONG).show();
                    if (!resetAutoplayHistory) maybeAutoPlayNext();
                });
            }
        });
    }

    private void startPlayer(PlaybackData data) {
        playing = true;
        preparingAutoplay = false;
        currentPlaybackData = data;
        if (playerTitleView != null) {
            playerTitleView.setText(data.title == null ? "" : data.title);
        }
        if (metaView != null && metaView != playerTitleView) {
            metaView.setText(buildMeta(data));
        }
        if (metaScroll != null) metaScroll.scrollTo(0, 0);
        if (playerView != null) playerView.setPlayer(player);
        player.setMediaItem(buildMediaItem(data.mediaUrl));
        resetDefaultQualityForNewMedia();
        player.prepare();
        player.play();
        updateKeepScreenOnForPlayer();
    }

    private void updateKeepScreenOnForPlayer() {
        int state = player.getPlaybackState();
        boolean keepOn = preparingAutoplay
                || (playing
                && player.getPlayWhenReady()
                && state != Player.STATE_ENDED
                && state != Player.STATE_IDLE);
        setKeepScreenOn(keepOn);
    }

    private void setKeepScreenOn(boolean keepOn) {
        if (keepOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void resetDefaultQualityForNewMedia() {
        try {
            player.setTrackSelectionParameters(player.getTrackSelectionParameters()
                    .buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                    .build());
        } catch (Exception ignored) {
            // Some stream types expose no selectable video tracks.
        }
        defaultQualityApplied = false;
    }

    private void applyDefaultQualityIfNeeded(Tracks tracks) {
        if (defaultQualityApplied || tracks == null) return;
        String quality = DownloadStore.getDefaultQuality(this);
        try {
            TrackSelectionOverride override = preferredVideoOverride(tracks, quality);
            if (override == null) return;
            defaultQualityApplied = true;
            player.setTrackSelectionParameters(player.getTrackSelectionParameters()
                    .buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                    .setOverrideForType(override)
                    .build());
        } catch (Exception ignored) {
            defaultQualityApplied = true;
        }
    }

    private TrackSelectionOverride preferredVideoOverride(Tracks tracks, String quality) {
        Tracks.Group bestGroup = null;
        int bestTrack = -1;
        int bestDiff = Integer.MAX_VALUE;
        int bestHeight = -1;
        int targetHeight = 0;
        boolean lowest = DownloadStore.QUALITY_LOWEST.equals(quality);
        boolean highest = DownloadStore.QUALITY_HIGHEST.equals(quality);
        if (!lowest && !highest) targetHeight = Integer.parseInt(quality);

        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_VIDEO) continue;
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSupported(i)) continue;
                Format format = group.getTrackFormat(i);
                int height = format.height;
                if (height <= 0) continue;
                if (lowest) {
                    if (bestHeight < 0 || height < bestHeight) {
                        bestGroup = group;
                        bestTrack = i;
                        bestHeight = height;
                    }
                    continue;
                }
                if (highest) {
                    if (height > bestHeight) {
                        bestGroup = group;
                        bestTrack = i;
                        bestHeight = height;
                    }
                    continue;
                }
                int diff = Math.abs(height - targetHeight);
                if (diff < bestDiff || (diff == bestDiff && height > bestHeight)) {
                    bestGroup = group;
                    bestTrack = i;
                    bestDiff = diff;
                    bestHeight = height;
                }
            }
        }

        if (bestGroup == null || bestTrack < 0) return null;
        return new TrackSelectionOverride(bestGroup.getMediaTrackGroup(), bestTrack);
    }

    private void maybeAutoPlayNext() {
        List<TubeItem> autoplayItems = currentAutoplayItems();
        if (!playing || offlinePlaying || preparingAutoplay || autoplayItems.isEmpty()) return;
        TubeItem next = nextAutoplayItem();
        if (next == null) {
            Toast.makeText(this, "재생할 다음 영상이 없습니다.", Toast.LENGTH_SHORT).show();
            updateKeepScreenOnForPlayer();
            return;
        }
        preparingAutoplay = true;
        play(next, false);
    }

    private TubeItem nextAutoplayItem() {
        String order = DownloadStore.getNextPlayOrder(this);
        if (DownloadStore.ORDER_RANDOM.equals(order)) return randomAutoplayItem();
        return sequentialAutoplayItem();
    }

    private TubeItem sequentialAutoplayItem() {
        List<TubeItem> autoplayItems = currentAutoplayItems();
        int start = currentAutoplayIndex(autoplayItems);
        if (start < 0) start = -1;
        for (int i = start + 1; i < autoplayItems.size(); i++) {
            TubeItem candidate = autoplayItems.get(i);
            if (isAutoplayCandidate(candidate)) return candidate;
        }
        return null;
    }

    private TubeItem randomAutoplayItem() {
        List<TubeItem> candidates = new ArrayList<>();
        for (TubeItem item : currentAutoplayItems()) {
            if (isAutoplayCandidate(item)) candidates.add(item);
        }
        if (candidates.isEmpty()) return null;
        return candidates.get(random.nextInt(candidates.size()));
    }

    private boolean isAutoplayCandidate(TubeItem item) {
        return item != null && item.playable && !autoplayPlayedKeys.contains(autoplayKey(item));
    }

    private List<TubeItem> currentAutoplayItems() {
        return webAutoplayActive ? webRelatedResults : results;
    }

    private int currentAutoplayIndex(List<TubeItem> autoplayItems) {
        if (currentItem == null) return -1;
        String currentKey = autoplayKey(currentItem);
        for (int i = 0; i < autoplayItems.size(); i++) {
            if (currentKey.equals(autoplayKey(autoplayItems.get(i)))) return i;
        }
        return -1;
    }

    private void markAutoplayPlayed(TubeItem item) {
        if (item != null) autoplayPlayedKeys.add(autoplayKey(item));
    }

    private String autoplayKey(TubeItem item) {
        String id = ExtractorBridge.videoIdOf(item.url);
        return id.isEmpty() ? item.url : id;
    }

    // Ad-free player overlay over the YouTube site WebView.
    private void showPlayingLayout(boolean show) {
        View layer = playerLayerView();
        if (layer != null) layer.setVisibility(show ? View.VISIBLE : View.GONE);
        if (playerView != null) playerView.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show && playerView != null) {
            playerControlsVisible = true;
            playerView.showController();
        }
        updatePlayerChromeVisibility();
        if (show && playerTitleView != null && currentItem != null) {
            playerTitleView.setText(currentItem.title);
        }
        if (youtubeWeb != null
                && DownloadStore.VIEW_WEB.equals(DownloadStore.getYoutubeViewMode(this))) {
            if (show) youtubeWeb.onPause();
            else youtubeWeb.onResume();
        }
    }

    private CharSequence buildMeta(PlaybackData data) {
        StringBuilder sb = new StringBuilder();
        sb.append(data.title == null ? "" : data.title).append("\n\n");
        if (data.uploader != null && !data.uploader.isEmpty()) sb.append(data.uploader).append("\n");
        List<String> line = new ArrayList<>();
        if (data.viewCount >= 0) line.add("조회수 " + ExtractorBridge.formatCount(data.viewCount) + "회");
        if (!data.uploadDate.isEmpty()) line.add(data.uploadDate);
        if (!line.isEmpty()) sb.append(String.join(" · ", line)).append("\n");
        if (!data.likeCount.isEmpty()) sb.append("좋아요 ").append(data.likeCount).append("\n");
        if (!data.description.isEmpty()) sb.append("\n").append(data.description).append("\n");
        if (data.tags != null && !data.tags.isEmpty()) {
            StringBuilder tagLine = new StringBuilder("\n");
            for (String tag : data.tags) tagLine.append('#').append(tag).append(' ');
            sb.append(tagLine.toString().trim());
        }
        return sb.toString().trim();
    }

    private void closePlayer() {
        int returnScreen = playerReturnScreen;
        persistOfflinePlaybackPosition();
        mainHandler.removeCallbacks(offlinePositionSaver);
        playing = false;
        offlinePlaying = false;
        currentOfflineItemId = "";
        playerReturnScreen = SCREEN_SEARCH;
        currentItem = null;
        currentPlaybackData = null;
        autoplayPlayedKeys.clear();
        preparingAutoplay = false;
        player.pause();
        player.clearMediaItems();
        setKeepScreenOn(false);
        if (qualityButton != null) qualityButton.setText("화질");
        if (playerDownloadButton != null) playerDownloadButton.setVisibility(View.VISIBLE);
        setFullscreen(false);
        if (playerView != null) playerView.setPlayer(player);
        showPlayingLayout(false);
        if (returnScreen != SCREEN_SEARCH) showScreen(returnScreen);
    }

    private void showQualityDialog() {
        if (!playing) return;
        try {
            new TrackSelectionDialogBuilder(this, "화질 선택", player, C.TRACK_TYPE_VIDEO)
                    .setAllowAdaptiveSelections(true)
                    .build()
                    .show();
        } catch (Exception e) {
            Toast.makeText(this, "화질 정보를 불러올 수 없습니다.", Toast.LENGTH_SHORT).show();
        }
    }

    // ---- Download ----------------------------------------------------------

    private void onDownloadClicked() {
        if (currentItem == null) {
            Toast.makeText(this, "다운로드할 영상이 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        final TubeItem item = currentItem;
        Toast.makeText(this, "대기열 등록 준비 중…", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            try {
                List<ExtractorBridge.DownloadOption> options = ExtractorBridge.downloadOptions(item.url);
                mainHandler.post(() -> showDownloadDialog(item, options));
            } catch (Exception e) {
                mainHandler.post(() ->
                        Toast.makeText(this, "화질 정보를 불러오지 못했습니다.", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void showDownloadDialog(TubeItem item, List<ExtractorBridge.DownloadOption> options) {
        if (options.isEmpty()) {
            Toast.makeText(this, "다운로드 가능한 화질이 없습니다.", Toast.LENGTH_LONG).show();
            return;
        }
        String[] labels = new String[options.size()];
        for (int i = 0; i < options.size(); i++) {
            ExtractorBridge.DownloadOption o = options.get(i);
            labels[i] = o.label + (o.muxed ? "  (고화질·합치기)" : "") + " · 대기열";
        }
        new AlertDialog.Builder(this)
                .setTitle("대기열 등록 · 화질 선택")
                .setItems(labels, (d, which) -> enqueueDownload(item, options.get(which)))
                .show();
    }

    private void enqueueDownload(TubeItem item, ExtractorBridge.DownloadOption option) {
        final PlaybackData downloadMetadata = currentPlaybackData;
        if (downloadQueue == null) {
            Toast.makeText(this, "대기열을 초기화하지 못했습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        String thumb = item.thumbnailUrl;
        if (thumb == null || thumb.isEmpty()) {
            thumb = YoutubeWebPane.thumbnailUrlFor(item.url);
        }
        DownloadQueue.Job job = new DownloadQueue.Job(
                item.url,
                item.title,
                item.subtitle,
                thumb,
                buildDownloadSearchText(item, downloadMetadata),
                option);
        int pos = downloadQueue.enqueue(job);
        if (pos == -2) {
            Toast.makeText(this, "이미 받은 영상입니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (pos <= 0) {
            Toast.makeText(this, "대기열에 넣지 못했습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (pos == 1 && downloadQueue.isActive()) {
            showDownloadStatus("다운로드 시작 · " + item.title);
            Toast.makeText(this, "다운로드를 시작합니다.", Toast.LENGTH_SHORT).show();
        } else {
            showDownloadStatus("대기열 " + pos + "번 · " + item.title);
            Toast.makeText(this, "대기열 " + pos + "번에 등록했습니다. (한 개씩 순차 다운로드)", Toast.LENGTH_LONG).show();
        }
    }

    private String buildDownloadSearchText(TubeItem item, PlaybackData data) {
        StringBuilder sb = new StringBuilder();
        sb.append(item.title).append('\n').append(item.subtitle);
        if (data != null) {
            if (data.uploader != null) sb.append('\n').append(data.uploader);
            if (data.description != null) sb.append('\n').append(data.description);
            if (data.tags != null && !data.tags.isEmpty()) {
                for (String tag : data.tags) sb.append('\n').append(tag);
            }
        }
        return sb.toString();
    }

    private void showDownloadStatus(String text) {
        downloadStatus.setText(text);
        if (webFullscreen) {
            restoreDownloadStatusAfterWebFullscreen = true;
            downloadStatus.setVisibility(View.GONE);
        } else {
            downloadStatus.setVisibility(View.VISIBLE);
        }
    }

    private void hideDownloadStatus() {
        restoreDownloadStatusAfterWebFullscreen = false;
        downloadStatus.setVisibility(View.GONE);
    }

    // ---- Fullscreen / popup ------------------------------------------------

    private void enterPopup() {
        if (!playing || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            enterPictureInPictureMode(new PictureInPictureParams.Builder()
                    .setAspectRatio(new Rational(16, 9))
                    .build());
        } catch (Exception e) {
            Toast.makeText(this, "팝업 재생을 시작할 수 없습니다.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onUserLeaveHint() {
        if (playing && !fullscreen && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPopup();
        }
        super.onUserLeaveHint();
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        applyPipLayout(isInPictureInPictureMode);
    }

    private void applyPipLayout(boolean pip) {
        bottomNav.setVisibility(pip ? View.GONE : View.VISIBLE);
        if (playerView != null) playerView.setUseController(!pip);
        updatePlayerChromeVisibility();
    }

    private void setFullscreen(boolean enter) {
        fullscreen = enter;
        setRequestedOrientation(enter
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_USER);
        bottomNav.setVisibility(enter ? View.GONE : View.VISIBLE);
        updatePlayerChromeVisibility();
        if (playerView != null) {
            // Preserve the complete frame and its aspect ratio in fullscreen.
            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        }
        applyImmersive(enter);
    }

    private void updatePlayerChromeVisibility() {
        if (playerChrome == null) return;
        boolean pip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && isInPictureInPictureMode();
        boolean show = playing && playerControlsVisible && !fullscreen && !pip;
        playerChrome.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void setWebFullscreen(boolean enter) {
        if (webFullscreen == enter) return;
        webFullscreen = enter;
        if (enter) {
            restoreDownloadStatusAfterWebFullscreen = downloadStatus.getVisibility() == View.VISIBLE;
            downloadStatus.setVisibility(View.GONE);
        } else if (restoreDownloadStatusAfterWebFullscreen) {
            downloadStatus.setVisibility(View.VISIBLE);
        }
        bottomNav.setVisibility(enter ? View.GONE : View.VISIBLE);
        setRequestedOrientation(enter
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_USER);
        applyImmersive(enter);
        if (appRoot != null) appRoot.requestApplyInsets();
    }

    private void applyImmersive(boolean on) {
        on = on || fullscreen || webFullscreen;
        View decor = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                if (on) {
                    controller.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    controller.hide(WindowInsets.Type.systemBars());
                } else {
                    controller.show(WindowInsets.Type.systemBars());
                }
            }
        } else {
            decor.setSystemUiVisibility(on
                    ? View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    : View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && (fullscreen || webFullscreen)) applyImmersive(true);
    }

    @Override
    public void onBackPressed() {
        if (webFullscreen && youtubeWeb != null && youtubeWeb.exitFullscreen()) {
            return;
        }
        if (fullscreen) {
            setFullscreen(false);
            return;
        }
        if (playing) {
            closePlayer();
            return;
        }
        if (currentScreen != SCREEN_SEARCH) {
            showScreen(SCREEN_SEARCH);
            return;
        }
        if (youtubeWeb != null && youtubeWeb.canGoBack()) {
            youtubeWeb.goBack();
            return;
        }
        super.onBackPressed();
    }

    private MediaItem buildMediaItem(String mediaUrl) {
        MediaItem.Builder builder = new MediaItem.Builder().setUri(mediaUrl);
        String lower = mediaUrl.toLowerCase();
        if (lower.contains(".m3u8") || lower.contains("/hls")) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8);
        } else if (lower.contains(".mpd") || lower.contains("/dash") || lower.contains("manifest")) {
            builder.setMimeType(MimeTypes.APPLICATION_MPD);
        }
        return builder.build();
    }

    @Override
    protected void onStop() {
        persistOfflinePlaybackPosition();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        persistOfflinePlaybackPosition();
        mainHandler.removeCallbacks(offlinePositionSaver);
        setKeepScreenOn(false);
        if (downloadQueue != null) downloadQueue.shutdown();
        if (youtubeWeb != null) youtubeWeb.destroy();
        player.release();
        executor.shutdownNow();
        super.onDestroy();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
