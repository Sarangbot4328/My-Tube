package com.mytube.apk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps site playback alive after the Activity/WebView is backgrounded.
 * Android suspends WebView media, so this service resolves the same video to an
 * audio stream, continues at the captured position, and hands the position back
 * to the WebView when the app returns.
 */
public final class BackgroundPlaybackService extends Service {
    static final class ResumeState {
        final String videoUrl;
        final long positionMs;
        final boolean shouldPlay;

        ResumeState(String videoUrl, long positionMs, boolean shouldPlay) {
            this.videoUrl = videoUrl == null ? "" : videoUrl;
            this.positionMs = Math.max(0L, positionMs);
            this.shouldPlay = shouldPlay;
        }
    }

    private static final String CHANNEL_ID = "mytube_background_playback";
    private static final int NOTIFICATION_ID = 1801;
    private static final String ACTION_START =
            "com.mytube.apk.action.START_BACKGROUND_PLAYBACK";
    private static final String ACTION_STOP =
            "com.mytube.apk.action.STOP_BACKGROUND_PLAYBACK";
    private static final String EXTRA_VIDEO_URL = "video_url";
    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_POSITION = "position";
    private static final Object STATE_LOCK = new Object();

    private static BackgroundPlaybackService instance;
    private static ResumeState pendingState;

    private final ExecutorService resolver = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private String videoUrl = "";
    private String title = "";
    private long requestedPositionMs;
    private int resolveGeneration;
    private boolean stoppedByUser;

    static boolean start(
            Context context, String videoUrl, String title, long positionMs) {
        if (context == null || videoUrl == null || videoUrl.isEmpty()) return false;
        synchronized (STATE_LOCK) {
            pendingState = new ResumeState(videoUrl, positionMs, true);
        }
        Intent intent = new Intent(context, BackgroundPlaybackService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_VIDEO_URL, videoUrl)
                .putExtra(EXTRA_TITLE, title == null ? "" : title)
                .putExtra(EXTRA_POSITION, Math.max(0L, positionMs));
        try {
            context.startForegroundService(intent);
            return true;
        } catch (RuntimeException e) {
            synchronized (STATE_LOCK) {
                pendingState = null;
            }
            return false;
        }
    }

    static boolean hasPendingPlayback() {
        synchronized (STATE_LOCK) {
            return pendingState != null || instance != null;
        }
    }

    static ResumeState takeResumeState(Context context) {
        BackgroundPlaybackService live;
        ResumeState state;
        synchronized (STATE_LOCK) {
            live = instance;
            state = pendingState;
            pendingState = null;
        }
        if (live != null) state = live.snapshotForReturn(state);
        try {
            context.stopService(new Intent(context, BackgroundPlaybackService.class));
        } catch (RuntimeException ignored) {
            // Service may already have stopped after playback completed.
        }
        return state;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        CookieStore.init(this);
        synchronized (STATE_LOCK) {
            instance = this;
        }
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stoppedByUser = true;
            synchronized (STATE_LOCK) {
                pendingState = new ResumeState(videoUrl, currentPosition(), false);
            }
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) return START_NOT_STICKY;

        videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL);
        if (videoUrl == null) videoUrl = "";
        title = intent.getStringExtra(EXTRA_TITLE);
        if (title == null || title.isEmpty()) title = "YouTube 영상";
        requestedPositionMs = Math.max(0L, intent.getLongExtra(EXTRA_POSITION, 0L));
        stoppedByUser = false;
        final int generation = ++resolveGeneration;
        startForeground(NOTIFICATION_ID, buildNotification("백그라운드 재생 준비 중"));

        resolver.execute(() -> {
            try {
                PlaybackData data = ExtractorBridge.resolveBackgroundAudio(videoUrl);
                mainHandler.post(() -> {
                    if (generation != resolveGeneration || stoppedByUser) return;
                    startResolvedPlayback(data);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (generation != resolveGeneration || stoppedByUser) return;
                    updateNotification("백그라운드 재생을 시작하지 못했습니다");
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
                });
            }
        });
        return START_NOT_STICKY;
    }

    private void startResolvedPlayback(PlaybackData data) {
        releasePlayer();
        player = new ExoPlayer.Builder(this).build();
        player.setAudioAttributes(
                new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                true);
        player.setWakeMode(C.WAKE_MODE_LOCAL);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_ENDED) {
                    synchronized (STATE_LOCK) {
                        pendingState = new ResumeState(
                                videoUrl, currentPosition(), false);
                    }
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
                }
            }
        });
        player.setMediaItem(buildMediaItem(data.mediaUrl));
        player.seekTo(requestedPositionMs);
        player.prepare();
        player.play();
        updateNotification("백그라운드 재생 중");
    }

    private ResumeState snapshotForReturn(ResumeState fallback) {
        String url = videoUrl.isEmpty() && fallback != null
                ? fallback.videoUrl : videoUrl;
        long position = player == null && fallback != null
                ? fallback.positionMs : currentPosition();
        boolean shouldPlay = !stoppedByUser
                && (player == null
                || (player.getPlayWhenReady()
                && player.getPlaybackState() != Player.STATE_ENDED));
        ++resolveGeneration;
        return new ResumeState(url, position, shouldPlay);
    }

    private long currentPosition() {
        if (player == null) return requestedPositionMs;
        try {
            return Math.max(0L, player.getCurrentPosition());
        } catch (RuntimeException ignored) {
            return requestedPositionMs;
        }
    }

    private MediaItem buildMediaItem(String mediaUrl) {
        MediaItem.Builder builder = new MediaItem.Builder().setUri(mediaUrl);
        String lower = mediaUrl == null ? "" : mediaUrl.toLowerCase();
        if (lower.contains(".m3u8") || lower.contains("/hls")) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8);
        } else if (lower.contains(".mpd") || lower.contains("/dash")
                || lower.contains("manifest")) {
            builder.setMimeType(MimeTypes.APPLICATION_MPD);
        }
        return builder.build();
    }

    private Notification buildNotification(String status) {
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPending = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, BackgroundPlaybackService.class)
                .setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
                this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title.isEmpty() ? "My Tube" : title)
                .setContentText(status)
                .setContentIntent(openPending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                        "정지", stopPending)
                .build();
    }

    private void updateNotification(String status) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(status));
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "백그라운드 재생",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("유튜브 사이트 영상의 백그라운드 재생");
        manager.createNotificationChannel(channel);
    }

    private void releasePlayer() {
        if (player == null) return;
        player.release();
        player = null;
    }

    @Override
    public void onDestroy() {
        ++resolveGeneration;
        releasePlayer();
        resolver.shutdownNow();
        synchronized (STATE_LOCK) {
            if (instance == this) instance = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
