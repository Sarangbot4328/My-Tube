package com.mytube.apk;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Rational;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.TrackSelectionDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    public enum Tab { YOUTUBE, HOME, VIDEOS, SHORTS, CHANNELS }

    private static final class SortOption {
        final String label;
        final ExtractorBridge.SortOrder order;
        SortOption(String label, ExtractorBridge.SortOrder order) {
            this.label = label;
            this.order = order;
        }
    }

    private static final SortOption[] SORTS = {
            new SortOption("관련성", ExtractorBridge.SortOrder.RELEVANCE),
            new SortOption("최신", ExtractorBridge.SortOrder.DATE),
            new SortOption("조회수", ExtractorBridge.SortOrder.VIEWS),
            new SortOption("평점", ExtractorBridge.SortOrder.RATING),
    };

    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<Button> tabButtons = new ArrayList<>();
    private final List<Button> sortButtons = new ArrayList<>();
    private final List<TubeItem> results = new ArrayList<>();

    private ImageLoader imageLoader;
    private EditText searchInput;
    private TextView statusView;
    private TextView metaView;
    private LinearLayout resultsList;
    private PlayerView playerView;
    private ExoPlayer player;
    private Tab currentTab = Tab.YOUTUBE;
    private ExtractorBridge.SortOrder currentSort = ExtractorBridge.SortOrder.RELEVANCE;

    // Views toggled during fullscreen / popup playback.
    private LinearLayout searchRow;
    private LinearLayout sortRow;
    private ScrollView scrollView;
    private ScrollView metaScroll;
    private LinearLayout tabsBar;
    private LinearLayout playerBar;

    private boolean fullscreen;
    private boolean playing;
    private boolean loadingMore;
    private int searchToken;

    private ExtractorBridge.SearchSession session;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        imageLoader = new ImageLoader(executor, mainHandler);
        player = new ExoPlayer.Builder(this).build();
        setContentView(buildUi());
        selectTab(Tab.YOUTUBE);
    }

    private LinearLayout buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(248, 249, 251));

        searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setPadding(dp(12), dp(10), dp(12), dp(8));

        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setHint("검색어를 입력하세요");
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
        root.addView(searchRow);

        // Sort filter row.
        sortRow = new LinearLayout(this);
        sortRow.setOrientation(LinearLayout.HORIZONTAL);
        sortRow.setPadding(dp(10), 0, dp(10), dp(6));
        for (SortOption option : SORTS) {
            addSortButton(sortRow, option);
        }
        root.addView(sortRow);

        playerView = new PlayerView(this);
        playerView.setPlayer(player);
        playerView.setVisibility(View.GONE);
        // Enables the controller's fullscreen button and routes taps to us.
        playerView.setFullscreenButtonClickListener(this::setFullscreen);
        root.addView(playerView, new LinearLayout.LayoutParams(-1, dp(220)));

        playerBar = new LinearLayout(this);
        playerBar.setOrientation(LinearLayout.HORIZONTAL);
        playerBar.setGravity(Gravity.END);
        playerBar.setPadding(dp(12), dp(4), dp(12), 0);
        playerBar.setVisibility(View.GONE);
        Button popupButton = new Button(this);
        popupButton.setText("팝업");
        popupButton.setAllCaps(false);
        popupButton.setOnClickListener(v -> enterPopup());
        playerBar.addView(popupButton, new LinearLayout.LayoutParams(-2, dp(40)));
        Button qualityButton = new Button(this);
        qualityButton.setText("화질");
        qualityButton.setAllCaps(false);
        qualityButton.setOnClickListener(v -> showQualityDialog());
        playerBar.addView(qualityButton, new LinearLayout.LayoutParams(-2, dp(40)));
        root.addView(playerBar);

        // Scrollable metadata panel (views, likes, date, description, tags).
        metaScroll = new ScrollView(this);
        metaScroll.setVisibility(View.GONE);
        metaScroll.setBackgroundColor(Color.WHITE);
        metaView = new TextView(this);
        metaView.setTextColor(Color.rgb(30, 41, 59));
        metaView.setTextSize(13);
        metaView.setPadding(dp(16), dp(10), dp(16), dp(10));
        metaView.setTextIsSelectable(true);
        metaScroll.addView(metaView);
        root.addView(metaScroll, new LinearLayout.LayoutParams(-1, dp(150)));

        statusView = new TextView(this);
        statusView.setTextColor(Color.rgb(71, 85, 105));
        statusView.setTextSize(14);
        statusView.setPadding(dp(16), dp(8), dp(16), dp(8));
        root.addView(statusView, new LinearLayout.LayoutParams(-1, dp(42)));

        scrollView = new ScrollView(this);
        resultsList = new LinearLayout(this);
        resultsList.setOrientation(LinearLayout.VERTICAL);
        resultsList.setPadding(dp(10), 0, dp(10), dp(10));
        scrollView.addView(resultsList);
        // Load more results as the user nears the bottom (infinite scroll).
        scrollView.setOnScrollChangeListener((v, sx, sy, osx, osy) -> {
            View child = scrollView.getChildAt(0);
            if (child == null) return;
            int remaining = child.getHeight() - (scrollView.getHeight() + sy);
            if (remaining < dp(600)) maybeLoadMore();
        });
        root.addView(scrollView, new LinearLayout.LayoutParams(-1, 0, 1));

        tabsBar = new LinearLayout(this);
        tabsBar.setOrientation(LinearLayout.HORIZONTAL);
        tabsBar.setBackgroundColor(Color.rgb(11, 15, 20));
        addTabButton(tabsBar, "유튜브", Tab.YOUTUBE);
        addTabButton(tabsBar, "홈", Tab.HOME);
        addTabButton(tabsBar, "동영상", Tab.VIDEOS);
        addTabButton(tabsBar, "쇼츠", Tab.SHORTS);
        addTabButton(tabsBar, "채널", Tab.CHANNELS);
        root.addView(tabsBar, new LinearLayout.LayoutParams(-1, dp(64)));

        return root;
    }

    private void addTabButton(LinearLayout parent, String label, Tab tab) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setOnClickListener(v -> selectTab(tab));
        tabButtons.add(button);
        parent.addView(button, new LinearLayout.LayoutParams(0, -1, 1));
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

    private void selectTab(Tab tab) {
        currentTab = tab;
        for (int i = 0; i < tabButtons.size(); i++) {
            Button button = tabButtons.get(i);
            boolean selected = Tab.values()[i] == tab;
            button.setTextColor(selected ? Color.WHITE : Color.rgb(203, 213, 225));
            button.setBackgroundColor(selected ? Color.rgb(229, 57, 53) : Color.rgb(11, 15, 20));
        }
        refreshSortButtons();
        runSearch();
    }

    private void runSearch() {
        final int token = ++searchToken;
        final String query = searchInput.getText().toString();
        results.clear();
        resultsList.removeAllViews();
        statusView.setText("불러오는 중...");
        loadingMore = true;
        session = ExtractorBridge.newSearch(query, currentTab, currentSort);
        final ExtractorBridge.SearchSession active = session;
        executor.execute(() -> {
            try {
                // Pull a couple of pages up front for a fuller first screen.
                List<TubeItem> batch = new ArrayList<>(active.loadMore());
                if (batch.size() < 25 && active.hasMore()) batch.addAll(active.loadMore());
                mainHandler.post(() -> {
                    if (token != searchToken) return;
                    onBatchLoaded(batch, true);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (token != searchToken) return;
                    loadingMore = false;
                    statusView.setText("검색 실패: " + e.getMessage());
                    Toast.makeText(this, "검색에 실패했습니다.", Toast.LENGTH_SHORT).show();
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
                    onBatchLoaded(batch, false);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (token == searchToken) loadingMore = false;
                });
            }
        });
    }

    private void onBatchLoaded(List<TubeItem> batch, boolean initial) {
        results.addAll(batch);
        for (TubeItem item : batch) {
            resultsList.addView(createResultRow(item));
        }
        if (results.isEmpty()) {
            statusView.setText("결과가 없습니다.");
        } else {
            boolean more = session != null && session.hasMore();
            statusView.setText(results.size() + "개 결과" + (more ? " · 아래로 스크롤하면 더 보기" : ""));
        }
        loadingMore = false;
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
                Toast.makeText(this, "채널 상세 화면은 다음 단계에서 붙일 예정입니다.", Toast.LENGTH_SHORT).show();
                return;
            }
            play(item);
        });
        return row;
    }

    private void play(TubeItem item) {
        playing = true;
        playerView.setVisibility(View.VISIBLE);
        playerBar.setVisibility(View.VISIBLE);
        metaScroll.setVisibility(View.VISIBLE);
        metaView.setText("재생 준비 중: " + item.title);
        executor.execute(() -> {
            try {
                PlaybackData data = ExtractorBridge.resolve(item.url);
                mainHandler.post(() -> startPlayer(data));
            } catch (Exception e) {
                mainHandler.post(() -> {
                    metaView.setText("재생 실패: " + e.getMessage());
                    Toast.makeText(this, "재생 가능한 스트림을 찾지 못했습니다.", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void startPlayer(PlaybackData data) {
        playing = true;
        playerView.setVisibility(View.VISIBLE);
        playerBar.setVisibility(View.VISIBLE);
        metaScroll.setVisibility(View.VISIBLE);
        metaView.setText(buildMeta(data));
        metaScroll.scrollTo(0, 0);
        player.setMediaItem(buildMediaItem(data.mediaUrl));
        player.prepare();
        player.play();
    }

    private CharSequence buildMeta(PlaybackData data) {
        StringBuilder sb = new StringBuilder();
        sb.append(data.title == null ? "" : data.title).append("\n\n");
        if (data.uploader != null && !data.uploader.isEmpty()) {
            sb.append(data.uploader).append("\n");
        }
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
        player.pause();
        player.clearMediaItems();
        playerView.setVisibility(View.GONE);
        playerBar.setVisibility(View.GONE);
        metaScroll.setVisibility(View.GONE);
    }

    // Opens a resolution picker for the current stream. Adaptive HLS/DASH streams
    // expose several qualities here; "자동(Auto)" lets ExoPlayer choose.
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

    private void enterPopup() {
        if (!playing || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            PictureInPictureParams params = new PictureInPictureParams.Builder()
                    .setAspectRatio(new Rational(16, 9))
                    .build();
            enterPictureInPictureMode(params);
        } catch (Exception e) {
            Toast.makeText(this, "팝업 재생을 시작할 수 없습니다.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onUserLeaveHint() {
        // Auto-enter popup when leaving the app mid-playback.
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
        int chrome = pip ? View.GONE : View.VISIBLE;
        searchRow.setVisibility(chrome);
        sortRow.setVisibility(chrome);
        statusView.setVisibility(chrome);
        scrollView.setVisibility(chrome);
        tabsBar.setVisibility(chrome);
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

        int chrome = enter ? View.GONE : View.VISIBLE;
        searchRow.setVisibility(chrome);
        sortRow.setVisibility(chrome);
        statusView.setVisibility(chrome);
        scrollView.setVisibility(chrome);
        tabsBar.setVisibility(chrome);
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
        if (playing) {
            closePlayer();
            return;
        }
        super.onBackPressed();
    }

    // Manifest URLs from YouTube often lack a file extension, so ExoPlayer can't
    // infer HLS/DASH from the URI alone — set the MIME type explicitly.
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
        player.release();
        executor.shutdownNow();
        super.onDestroy();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
