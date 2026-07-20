package com.mytube.apk;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
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
    private final List<Button> sortButtons = new ArrayList<>();
    private final List<Button> navButtons = new ArrayList<>();
    private final List<TubeItem> results = new ArrayList<>();

    private ImageLoader imageLoader;
    private ExoPlayer player;

    // Screens.
    private LinearLayout searchScreen;
    private LinearLayout downloadScreen;
    private LinearLayout settingsScreen;
    private LinearLayout bottomNav;
    private TextView downloadStatus;
    private int currentScreen = SCREEN_SEARCH;

    // Search screen views.
    private EditText searchInput;
    private LinearLayout searchRow;
    private LinearLayout sortRow;
    private TextView statusView;
    private ScrollView resultsScroll;
    private LinearLayout resultsList;
    private PlayerView playerView;
    private LinearLayout playerBar;
    private Button downloadButton;
    private Button qualityButton;
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
    private boolean playing;
    private boolean offlinePlaying;
    private boolean loadingMore;
    private boolean preparingAutoplay;
    private boolean defaultQualityApplied;
    private int searchToken;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CookieStore.init(this);
        imageLoader = new ImageLoader(executor, mainHandler);
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
                    maybeAutoPlayNext();
                }
                updateKeepScreenOnForPlayer();
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                updateKeepScreenOnForPlayer();
            }
        });
        showScreen(SCREEN_SEARCH);
        runSearch();
    }

    private void updateQualityLabel() {
        if (qualityButton == null) return;
        int height = player.getVideoSize().height;
        qualityButton.setText(height > 0 ? "화질 " + height + "p" : "화질");
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(248, 249, 251));
        // targetSdk 35 draws edge-to-edge, so pad by the system bar insets to keep
        // the search bar out from under the status bar and the nav out from under
        // the gesture bar.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
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

    // ---- Search screen -----------------------------------------------------

    private LinearLayout buildSearchScreen() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));

        searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setPadding(dp(12), dp(10), dp(12), dp(8));

        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setHint("비우면 맞춤 추천 · 검색어 입력 가능");
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

        playerView = new PlayerView(this);
        playerView.setPlayer(player);
        playerView.setBackgroundColor(Color.BLACK);
        playerView.setShutterBackgroundColor(Color.BLACK);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        playerView.setVisibility(View.GONE);
        playerView.setFullscreenButtonClickListener(this::setFullscreen);
        screen.addView(playerView, new LinearLayout.LayoutParams(-1, dp(220)));

        playerBar = new LinearLayout(this);
        playerBar.setOrientation(LinearLayout.HORIZONTAL);
        playerBar.setGravity(Gravity.END);
        playerBar.setPadding(dp(10), dp(4), dp(10), 0);
        playerBar.setVisibility(View.GONE);
        addPlayerBarButton("목록", v -> closePlayer());
        addPlayerBarButton("팝업", v -> enterPopup());
        qualityButton = addPlayerBarButton("화질", v -> showQualityDialog());
        downloadButton = addPlayerBarButton("다운로드", v -> onDownloadClicked());
        screen.addView(playerBar);

        metaScroll = new ScrollView(this);
        metaScroll.setVisibility(View.GONE);
        metaScroll.setBackgroundColor(Color.WHITE);
        metaView = new TextView(this);
        metaView.setTextColor(Color.rgb(30, 41, 59));
        metaView.setTextSize(13);
        metaView.setPadding(dp(16), dp(10), dp(16), dp(10));
        metaView.setTextIsSelectable(true);
        metaScroll.addView(metaView);
        screen.addView(metaScroll, new LinearLayout.LayoutParams(-1, 0, 1));

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
        imageLoader.load(item.thumbnailUrl, thumb);
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
                    DownloadItem removed = DownloadStore.remove(this, item.id);
                    if (removed != null) MediaDownloader.deleteFile(this, removed.uri);
                    refreshDownloadList();
                    Toast.makeText(this, "삭제되었습니다.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void playOffline(DownloadItem item) {
        currentItem = null;
        currentPlaybackData = null;
        autoplayPlayedKeys.clear();
        preparingAutoplay = false;
        offlinePlaying = true;
        playing = true;
        showScreen(SCREEN_SEARCH);
        showPlayingLayout(true);
        downloadButton.setVisibility(View.GONE);
        metaView.setText(item.title + (item.uploader.isEmpty() ? "" : "\n\n" + item.uploader) + "\n\n오프라인 재생");
        player.setMediaItem(buildMediaItem(item.uri));
        resetDefaultQualityForNewMedia();
        player.prepare();
        player.play();
        updateKeepScreenOnForPlayer();
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
        version.setText("버전 My Tube 1.4");
        version.setTextColor(Color.rgb(71, 85, 105));
        version.setTextSize(14);
        version.setPadding(dp(16), 0, dp(16), dp(10));
        screen.addView(version);

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
        cookieHelp.setText("로그인하면 (1) 차단 완화 쿠키 적용, (2) 검색창을 비웠을 때 계정 맞춤 추천 홈을 불러옵니다. "
                + "비공개 기기에서만 로그인하세요.");
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
            Toast.makeText(this, "로그인 쿠키를 삭제했습니다.", Toast.LENGTH_SHORT).show();
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

    private void refreshSettings() {
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
                        if (currentScreen == SCREEN_SEARCH
                                && searchInput != null
                                && searchInput.getText().toString().trim().isEmpty()) {
                            runSearch();
                        }
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
                Toast.makeText(this, "로그인 세션이 적용되었습니다. 맞춤 추천을 불러옵니다.", Toast.LENGTH_SHORT).show();
                if (currentScreen == SCREEN_SEARCH
                        && searchInput != null
                        && searchInput.getText().toString().trim().isEmpty()) {
                    runSearch();
                }
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
            // Re-tapping 유튜브 returns to the main list.
            closePlayer();
        }
        if (screen != SCREEN_SEARCH && playing) {
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
        currentItem = item;
        currentPlaybackData = null;
        offlinePlaying = false;
        playing = true;
        if (resetAutoplayHistory) preparingAutoplay = false;
        if (resetAutoplayHistory) autoplayPlayedKeys.clear();
        markAutoplayPlayed(item);
        showPlayingLayout(true);
        downloadButton.setVisibility(View.VISIBLE);
        metaView.setText("재생 준비 중: " + item.title);
        executor.execute(() -> {
            try {
                PlaybackData data = ExtractorBridge.resolve(item.url);
                mainHandler.post(() -> startPlayer(data));
            } catch (Exception e) {
                mainHandler.post(() -> {
                    preparingAutoplay = false;
                    updateKeepScreenOnForPlayer();
                    metaView.setText("재생 실패: " + e.getMessage());
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
        metaView.setText(buildMeta(data));
        metaScroll.scrollTo(0, 0);
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
        if (!playing || offlinePlaying || preparingAutoplay || results.isEmpty()) return;
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
        int start = currentResultIndex();
        if (start < 0) start = -1;
        for (int i = start + 1; i < results.size(); i++) {
            TubeItem candidate = results.get(i);
            if (isAutoplayCandidate(candidate)) return candidate;
        }
        return null;
    }

    private TubeItem randomAutoplayItem() {
        List<TubeItem> candidates = new ArrayList<>();
        for (TubeItem item : results) {
            if (isAutoplayCandidate(item)) candidates.add(item);
        }
        if (candidates.isEmpty()) return null;
        return candidates.get(random.nextInt(candidates.size()));
    }

    private boolean isAutoplayCandidate(TubeItem item) {
        return item != null && item.playable && !autoplayPlayedKeys.contains(autoplayKey(item));
    }

    private int currentResultIndex() {
        if (currentItem == null) return -1;
        String currentKey = autoplayKey(currentItem);
        for (int i = 0; i < results.size(); i++) {
            if (currentKey.equals(autoplayKey(results.get(i)))) return i;
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

    // Shows only the player + description (hides the search bar and result list).
    private void showPlayingLayout(boolean show) {
        searchRow.setVisibility(show ? View.GONE : View.VISIBLE);
        sortRow.setVisibility(show ? View.GONE : View.VISIBLE);
        statusView.setVisibility(show ? View.GONE : View.VISIBLE);
        resultsScroll.setVisibility(show ? View.GONE : View.VISIBLE);
        playerView.setVisibility(show ? View.VISIBLE : View.GONE);
        playerBar.setVisibility(show ? View.VISIBLE : View.GONE);
        metaScroll.setVisibility(show ? View.VISIBLE : View.GONE);
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
        playing = false;
        offlinePlaying = false;
        currentItem = null;
        currentPlaybackData = null;
        autoplayPlayedKeys.clear();
        preparingAutoplay = false;
        player.pause();
        player.clearMediaItems();
        setKeepScreenOn(false);
        qualityButton.setText("화질");
        showPlayingLayout(false);
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
        Toast.makeText(this, "화질 정보를 불러오는 중...", Toast.LENGTH_SHORT).show();
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
            labels[i] = o.label + (o.muxed ? "  (고화질·합치기)" : "");
        }
        new AlertDialog.Builder(this)
                .setTitle("다운로드 화질 선택")
                .setItems(labels, (d, which) -> startDownload(item, options.get(which)))
                .show();
    }

    private void startDownload(TubeItem item, ExtractorBridge.DownloadOption option) {
        final PlaybackData downloadMetadata = currentPlaybackData;
        if (!CookieStore.hasAuthCookies()) {
            Toast.makeText(this,
                    "로그인 쿠키가 없습니다. 설정 → YouTube 로그인 후 대량 다운로드를 권장합니다.",
                    Toast.LENGTH_LONG).show();
        }
        showDownloadStatus("다운로드 준비 중: " + item.title);
        executor.execute(() -> {
            try {
                MediaDownloader.ProgressListener listener = status ->
                        mainHandler.post(() -> showDownloadStatus(status + " · " + item.title));
                ExtractorBridge.DownloadOption active = option;
                String savedUri;
                try {
                    savedUri = MediaDownloader.save(this, active, item.title, listener);
                } catch (Exception first) {
                    // Stream URLs expire or get 403; re-resolve like yt-dlp retries.
                    String msg = first.getMessage() == null ? "" : first.getMessage();
                    if (!msg.contains("403") && !msg.contains("429") && !msg.toLowerCase().contains("http 5")) {
                        throw first;
                    }
                    mainHandler.post(() -> showDownloadStatus("스트림 재발급 후 재시도 중… · " + item.title));
                    ExtractorBridge.DownloadOption refreshed =
                            ExtractorBridge.refreshOption(item.url, active);
                    if (refreshed == null) throw first;
                    active = refreshed;
                    savedUri = MediaDownloader.save(this, active, item.title, listener);
                }
                String id = ExtractorBridge.videoIdOf(item.url);
                if (id.isEmpty()) id = MediaDownloader.safeFileName(item.title, active.ext);
                DownloadStore.add(this, new DownloadItem(
                        id, item.title, item.subtitle, savedUri, item.thumbnailUrl, active.label,
                        buildDownloadSearchText(item, downloadMetadata)));
                mainHandler.post(() -> {
                    showDownloadStatus("✓ 다운로드 완료: " + item.title);
                    if (currentScreen == SCREEN_DOWNLOADS) refreshDownloadList();
                    mainHandler.postDelayed(this::hideDownloadStatus, 5000);
                });
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.toString() : e.getMessage();
                if (message.contains("403") || message.contains("429")) {
                    message += CookieStore.hasAuthCookies()
                            ? " · 잠시 후 다시 시도하거나 네트워크를 바꿔 보세요."
                            : " · 설정에서 YouTube 로그인 후 다시 시도하세요.";
                }
                final String shown = message;
                mainHandler.post(() -> showDownloadStatus("✗ 다운로드 실패: " + shown));
            }
        });
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
        downloadStatus.setVisibility(View.VISIBLE);
    }

    private void hideDownloadStatus() {
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
        playerBar.setVisibility(pip ? View.GONE : (playing ? View.VISIBLE : View.GONE));
        metaScroll.setVisibility(pip ? View.GONE : (playing ? View.VISIBLE : View.GONE));
        playerView.setUseController(!pip);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) playerView.getLayoutParams();
        lp.height = pip ? LinearLayout.LayoutParams.MATCH_PARENT : dp(220);
        playerView.setLayoutParams(lp);
    }

    private void setFullscreen(boolean enter) {
        fullscreen = enter;
        setRequestedOrientation(enter
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_USER);
        bottomNav.setVisibility(enter ? View.GONE : View.VISIBLE);
        playerBar.setVisibility(enter ? View.GONE : (playing ? View.VISIBLE : View.GONE));
        metaScroll.setVisibility(enter ? View.GONE : (playing ? View.VISIBLE : View.GONE));
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) playerView.getLayoutParams();
        lp.height = enter ? LinearLayout.LayoutParams.MATCH_PARENT : dp(220);
        playerView.setLayoutParams(lp);
        applyImmersive(enter);
    }

    private void applyImmersive(boolean on) {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(on
                ? View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                : View.SYSTEM_UI_FLAG_VISIBLE);
    }

    @Override
    public void onBackPressed() {
        if (fullscreen) {
            setFullscreen(false);
            return;
        }
        if (currentScreen != SCREEN_SEARCH) {
            showScreen(SCREEN_SEARCH);
            return;
        }
        if (playing) {
            closePlayer();
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
    protected void onDestroy() {
        setKeepScreenOn(false);
        player.release();
        executor.shutdownNow();
        super.onDestroy();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
