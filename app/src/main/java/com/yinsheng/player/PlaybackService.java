package com.yinsheng.player;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.session.*;
import android.media.MediaMetadata;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

/** Foreground media owner and explicitly enabled bilingual overlay. */
public class PlaybackService extends Service {
    private static final String CHANNEL = "playback";
    private static final int NOTIFICATION = 160;
    private PlaybackSession session;
    private MediaSession mediaSession;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager window;
    private LinearLayout overlay;
    private TextView original, translated;
    private WindowManager.LayoutParams params;
    private long lastSave;
    private String notificationState = "";
    private SubtitleManager.SubtitleLine lastOverlayLine;
    private String overlaySongKey = "";
    private SharedPreferences preferences;
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsChanged = (sp, key) -> {
        if (session != null) {
            if (key != null && (key.contains("key") || key.contains("trans") || key.contains("appid"))) session.pipeline.retryTranslation();
            session.configureSubtitles(); updateOverlay();
        }
    };
    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) { session.pause(); updateNotification(); }
    };
    private final Runnable ticker = new Runnable() {
        public void run() {
            session.tick(); updateOverlay(); updateNotification();
            long now = SystemClock.elapsedRealtime();
            if (now - lastSave > 5000) { session.save(); lastSave = now; }
            handler.postDelayed(this, 250);
        }
    };
    @Override public void onCreate() {
        super.onCreate();
        session = PlaybackSession.get(this);
        window = (WindowManager)getSystemService(WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "音频播放", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
        mediaSession = new MediaSession(this, "YinShengPlayer");
        mediaSession.setCallback(new MediaSession.Callback() {
            public void onPlay() { session.start(); }
            public void onPause() { session.pause(); }
            public void onStop() { session.stop(); stopSelf(); }
            public void onSeekTo(long p) { session.seekTo((int)p); }
            public void onSkipToNext() { session.next(1); }
            public void onSkipToPrevious() { session.next(-1); }
        });
        mediaSession.setActive(true);
        Notification n = notification();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(NOTIFICATION, n);
        IntentFilter filter = new IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, filter, RECEIVER_NOT_EXPORTED); else registerReceiver(noisy, filter);
        preferences = session.settings.preferences();
        preferences.registerOnSharedPreferenceChangeListener(settingsChanged);
        handler.post(ticker);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if ("toggle".equals(action)) { if (session.isPlaying()) session.pause(); else session.start(); }
        if ("stop".equals(action)) { session.stop(); stopSelf(); }
        if ("next".equals(action)) session.next(1);
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent i) { return null; }
    private PendingIntent command(String action, int id) {
        return PendingIntent.getService(this, id, new Intent(this, PlaybackService.class).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private Notification notification() {
        boolean playing = session.isPlaying();
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(session.song == null ? "音声播放器" : session.song.title)
                .setContentText(session.preparing ? "正在加载音频" : playing ? "正在播放" : "已暂停")
                .setContentIntent(open).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOngoing(playing).setCategory(Notification.CATEGORY_TRANSPORT)
                .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, playing ? "暂停" : "播放", command("toggle", 1))
                .addAction(android.R.drawable.ic_media_next, "下一首", command("next", 2))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", command("stop", 3))
                .setStyle(new Notification.MediaStyle().setMediaSession(mediaSession.getSessionToken()).setShowActionsInCompactView(0,1,2)).build();
    }
    private void updateNotification() {
        boolean playing = session.isPlaying();
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(session.preparing ? PlaybackState.STATE_BUFFERING : playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, session.getCurrentPosition(), playing ? 1f : 0f).build());
        String state = (session.song == null ? "" : session.song.title) + playing + session.preparing + session.getDuration();
        if (!state.equals(notificationState)) {
            notificationState = state;
            mediaSession.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, session.song == null ? "音声播放器" : session.song.title).putLong(MediaMetadata.METADATA_KEY_DURATION, session.getDuration()).build());
            getSystemService(NotificationManager.class).notify(NOTIFICATION, notification());
        }
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private void updateOverlay() {
        // 悬浮字幕只服务于后台播放；应用任一页面在前台时隐藏，避免与界面重复堆叠。
        if (!session.settings.isFloatingSubtitlesEnabled() || !Settings.canDrawOverlays(this)
                || !session.isPlaying() || MainActivity.isAppForeground()) { removeOverlay(); return; }
        if (overlay == null) createOverlay();
        if (overlay == null) return;
        String key = session.song == null ? "" : MainActivity.songKey(session.song);
        if (!key.equals(overlaySongKey)) { overlaySongKey = key; lastOverlayLine = null; }
        SubtitleManager.SubtitleLine line = session.currentLine();
        if (line != null) lastOverlayLine = line;
        SubtitleManager.SubtitleLine shown = line != null ? line : lastOverlayLine;
        original.setText(shown == null ? session.status : shown.text);
        String tr = shown == null ? "" : shown.translated;
        translated.setText(tr);
        translated.setVisibility(tr.isEmpty() ? View.GONE : View.VISIBLE);
    }
    private void createOverlay() {
        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL); overlay.setPadding(dp(12), dp(6), dp(12), dp(12));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(0xf01d172b); bg.setCornerRadius(dp(14)); overlay.setBackground(bg);
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this); title.setText("音声字幕 · 拖动此处移动悬浮窗"); title.setTextColor(0xffd1c0e9); title.setTextSize(11);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(36), 1));
        TextView close = new TextView(this); close.setText("×"); close.setContentDescription("关闭悬浮字幕"); close.setTextColor(0xffffffff); close.setTextSize(24); close.setGravity(Gravity.CENTER);
        header.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));
        close.setOnClickListener(v -> { session.settings.setFloatingSubtitlesEnabled(false); removeOverlay(); });
        overlay.addView(header);
        original = new TextView(this); original.setTextColor(0xffffffff); original.setTextSize(17); original.setMaxLines(4); original.setEllipsize(android.text.TextUtils.TruncateAt.END); overlay.addView(original);
        translated = new TextView(this); translated.setTextColor(0xffd2b7ff); translated.setTextSize(16); translated.setMaxLines(4); translated.setEllipsize(android.text.TextUtils.TruncateAt.END); translated.setPadding(0,dp(5),0,0); overlay.addView(translated);
        int width = Math.min(dp(340), getResources().getDisplayMetrics().widthPixels - dp(20));
        params = new WindowManager.LayoutParams(width, WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = preferences.getInt("overlay_x", dp(10)); params.y = preferences.getInt("overlay_y", dp(140));
        params.alpha = 0.8f;
        clampPosition();
        title.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY; int x, y;
            public boolean onTouch(View v, android.view.MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) { downX = event.getRawX(); downY = event.getRawY(); x = params.x; y = params.y; return true; }
                if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    params.x = x + (int)(event.getRawX() - downX); params.y = y + (int)(event.getRawY() - downY); clampPosition();
                    try { if (overlay != null) window.updateViewLayout(overlay, params); } catch (RuntimeException e) { removeOverlay(); }
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_UP) { preferences.edit().putInt("overlay_x",params.x).putInt("overlay_y",params.y).apply(); v.performClick(); return true; }
                return false;
            }
        });
        try { window.addView(overlay, params); }
        catch (RuntimeException e) { overlay = null; session.settings.setFloatingSubtitlesEnabled(false); }
    }
    private void clampPosition() {
        android.util.DisplayMetrics m = getResources().getDisplayMetrics();
        params.x = Math.max(0, Math.min(params.x, Math.max(0, m.widthPixels - params.width)));
        params.y = Math.max(dp(24), Math.min(params.y, Math.max(dp(24), m.heightPixels - dp(200))));
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config); removeOverlay(); updateOverlay();
    }
    private void removeOverlay() {
        if (overlay != null) { try { window.removeView(overlay); } catch (RuntimeException ignored) {} overlay = null; }
    }
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null); removeOverlay();
        if (preferences != null) preferences.unregisterOnSharedPreferenceChangeListener(settingsChanged);
        unregisterReceiver(noisy);
        session.stop(); session.abandonFocus(); mediaSession.release();
        super.onDestroy();
    }
}
