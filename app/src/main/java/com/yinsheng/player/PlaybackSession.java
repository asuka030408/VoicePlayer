package com.yinsheng.player;

import android.content.*;
import android.media.*;
import android.net.Uri;
import android.os.*;
import java.io.File;
import java.util.*;

/** Application-scoped state hosted by PlaybackService; no Activity is retained. Main thread only. */
public final class PlaybackSession {
    private static PlaybackSession instance;
    public static synchronized PlaybackSession get(Context c) {
        if (instance == null) instance = new PlaybackSession(c.getApplicationContext());
        return instance;
    }
    public interface Ui { void onPlaybackChanged(); void onPlaybackError(String message); }
    final Context ctx;
    public final AppSettings settings;
    public final SubtitleManager cache;
    public final SubtitlePipeline pipeline;
    public final BulkGenerator bulk;
    public MainActivity.Song song;
    public String audioKey;
    public String status = "";
    public boolean preparing;
    private MediaPlayer media;
    private boolean ready, playRequested, hasFocus, resumeOnFocus, waitingForSubtitle;
    private long generation;
    private Ui ui;
    private SubtitlePipeline.Listener subtitleUi;
    private final List<MainActivity.Song> queue = new ArrayList<>();
    private int mode;
    private String subtitleConfig = "";
    private final Map<Integer, List<SubtitleManager.SubtitleLine>> segments = new TreeMap<>();
    private final AudioManager audio;
    private AudioFocusRequest focusRequest;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (change == AudioManager.AUDIOFOCUS_GAIN) {
            hasFocus = true;
            if (resumeOnFocus) { resumeOnFocus = false; start(); }
        } else {
            boolean wasPlaying = isPlaying();
            pause();
            hasFocus = false;
            resumeOnFocus = change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT && wasPlaying;
        }
    };
    private PlaybackSession(Context c) {
        ctx = c; settings = new AppSettings(c); cache = new SubtitleManager(c);
        pipeline = new SubtitlePipeline(c, cache, settings);
        bulk = new BulkGenerator(c, new SubtitleManager(c), settings);
        bulk.setObserver(() -> {
            configureSubtitles();
            if (audioKey != null) for (int seg : cache.getProcessedSegments(audioKey)) segments.put(seg, cache.loadSegment(audioKey, seg));
            changed();
        });
        audio = (AudioManager)c.getSystemService(Context.AUDIO_SERVICE);
        pipeline.setListener(new SubtitlePipeline.Listener() {
            public void onSegmentStarted(int i) { status = "正在生成第 " + (i+1) + " 段字幕"; if (subtitleUi != null) subtitleUi.onSegmentStarted(i); }
            public void onProgress(int i, int n) { if (subtitleUi != null) subtitleUi.onProgress(i,n); }
            public void onSegmentDone(int i, boolean ns) {
                if (audioKey == null) return;
                segments.put(i, cache.loadSegment(audioKey, i));
                status = "";
                if (subtitleUi != null) subtitleUi.onSegmentDone(i,ns);
                if (waitingForSubtitle && playRequested && isRequestedSegmentReady()) {
                    waitingForSubtitle = false;
                    start();
                }
            }
            public void onAllDone() { status = "字幕已生成"; if (subtitleUi != null) subtitleUi.onAllDone(); }
            public void onModelPreparing(String s) { status = "正在准备 " + s; if (subtitleUi != null) subtitleUi.onModelPreparing(s); }
            public void onModelMissing() { status = "离线模型不可用"; if (subtitleUi != null) subtitleUi.onModelMissing(); }
            public void onEngineError(String s) { status = s; if (subtitleUi != null) subtitleUi.onEngineError(s); }
            public void onInfo(String s) { status = s; if (subtitleUi != null) subtitleUi.onInfo(s); }
        });
    }
    public void attach(Ui u, SubtitlePipeline.Listener l) { ui = u; subtitleUi = l; }
    public void detach(Ui u) { if (ui == u) { ui = null; subtitleUi = null; } }
    private void changed() { if (ui != null) ui.onPlaybackChanged(); }
    public void setQueue(List<MainActivity.Song> songs, int playMode) { queue.clear(); queue.addAll(songs); mode = playMode; }
    public void setMode(int value) { mode = value; }
    public void play(MainActivity.Song target) { play(target, 0, true); }
    public void play(MainActivity.Song target, int position, boolean autoPlay) {
        final long token = ++generation;
        pipeline.stop(); subtitleConfig = ""; segments.clear(); audioKey = null;
        releaseMedia(); song = target; playRequested = autoPlay; preparing = true; status = "正在加载音频…";
        Intent service = new Intent(ctx, PlaybackService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(service); else ctx.startService(service);
        } catch (RuntimeException e) { preparing = false; playRequested = false; error("无法启动后台播放，请返回应用重试"); return; }
        changed();
        // setDataSource itself can block on a SAF provider. Keep it off the UI thread too.
        new Thread(() -> {
            MediaPlayer candidate = new MediaPlayer();
            try {
                candidate.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
                Uri uri = target.uri != null ? target.uri : Uri.fromFile(new File(target.path));
                candidate.setDataSource(ctx, uri);
                main.post(() -> {
                    if (token != generation) { candidate.release(); return; }
                    media = candidate;
                    candidate.setWakeMode(ctx, PowerManager.PARTIAL_WAKE_LOCK);
                    candidate.setOnErrorListener((mp, what, extra) -> {
                        if (token == generation) { preparing = false; ready = false; playRequested = false; releaseMedia(); error("无法播放此音频，请检查文件权限或重新导入"); }
                        return true;
                    });
                    candidate.setOnCompletionListener(mp -> { if (token == generation) completed(); });
                    candidate.setOnPreparedListener(mp -> {
                        if (token != generation) return;
                        ready = true; preparing = false; status = "";
                        if (position > 0) mp.seekTo(Math.min(position, mp.getDuration()));
                        configureSubtitles();
                        if (playRequested) start();
                        changed();
                    });
                    try { candidate.prepareAsync(); }
                    catch (RuntimeException e) { releaseMedia(); preparing = false; error("音频加载失败"); }
                });
            } catch (Exception e) {
                candidate.release();
                main.post(() -> { if (token == generation) { preparing = false; playRequested = false; error("无法读取音频，请重新导入并授予读取权限"); } });
            }
        }, "audio-open").start();
    }
    private void error(String message) { status = message; if (ui != null) ui.onPlaybackError(message); changed(); }
    public boolean isPlaying() { try { return ready && media != null && media.isPlaying(); } catch (IllegalStateException e) { return false; } }
    public int getDuration() { try { return ready && media != null ? media.getDuration() : 0; } catch (IllegalStateException e) { return 0; } }
    public int getCurrentPosition() { try { return ready && media != null ? media.getCurrentPosition() : 0; } catch (IllegalStateException e) { return 0; } }
    public void start() {
        playRequested = true; resumeOnFocus = false;
        if (media == null && !preparing && song != null) { play(song); return; }
        if (!ready || media == null) return;
        if (requiresSubtitleWait()) {
            waitForSubtitle();
            return;
        }
        try {
            if (!hasFocus) {
                int result;
                if (Build.VERSION.SDK_INT >= 26) {
                    focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                            .setOnAudioFocusChangeListener(focusListener, main).build();
                    result = audio.requestAudioFocus(focusRequest);
                } else result = audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
                hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            }
            if (!hasFocus) { playRequested = false; error("暂时无法获得音频焦点，请稍后播放"); return; }
            media.start(); pipeline.resume(); save(); changed();
            bulk.suspendForForeground();
        } catch (RuntimeException e) { error("播放失败，请重新选择音频"); }
    }
    public void pause() {
        playRequested = false; resumeOnFocus = false;
        waitingForSubtitle = false;
        if (isPlaying()) media.pause();
        pipeline.pause(); save(); changed();
        bulk.resumeAfterForeground();
    }
    public void seekTo(int position) {
        if (!ready || media == null) return;
        media.seekTo(Math.max(0, Math.min(position, getDuration())));
        int segment = Math.max(0, position) / (int)cache.getSegmentMs();
        pipeline.redirect(segment);
        // 只有字幕功能实际开启时才需要等待生成；关闭字幕/悬浮字幕后，拖动进度条应立即生效。
        if (playRequested && requiresSubtitleWait() && !isRequestedSegmentReady()) waitForSubtitle();
        save(); changed();
    }
    private boolean isRequestedSegmentReady() {
        if (audioKey == null) return true;
        int seg = Math.max(0, getCurrentPosition()) / (int)cache.getSegmentMs();
        if (!cache.getProcessedSegments(audioKey).contains(seg)) return false;
        if (!settings.isTranslationEnabled()) return true;
        List<SubtitleManager.SubtitleLine> lines = cache.loadSegment(audioKey, seg);
        for (SubtitleManager.SubtitleLine l : lines) {
            if (!l.noSpeech && !l.text.isEmpty() && !SubtitlePipeline.isChineseText(l.text)
                    && (l.translated == null || l.translated.trim().isEmpty())) return false;
        }
        return true;
    }

    /** 播放追上字幕/翻译生成进度时暂停音频，但保留播放请求，生成完成后自动恢复。 */
    private void waitForSubtitle() {
        if (isPlaying()) media.pause();
        waitingForSubtitle = true;
        pipeline.resume();
        int seg = audioKey == null ? 0 : Math.max(0, getCurrentPosition()) / (int) cache.getSegmentMs();
        pipeline.requestTranslationBackfill(seg);
        changed();
    }
    private boolean requiresSubtitleWait() {
        if (song == null || !(settings.isSubtitleEnabled() || settings.isFloatingSubtitlesEnabled()) || audioKey == null) return false;
        return !isRequestedSegmentReady();
    }
    public void stop() {
        generation++; pause(); releaseMedia(); pipeline.releaseIdle(); preparing = false;
        bulk.resumeAfterForeground();
        segments.clear(); subtitleConfig = ""; status = "已停止"; changed();
    }
    private void releaseMedia() { ready = false; if (media != null) { media.release(); media = null; } }
    public void abandonFocus() {
        if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) audio.abandonAudioFocusRequest(focusRequest);
        else audio.abandonAudioFocus(focusListener);
        hasFocus = false;
    }
    public void next(int direction) {
        if (queue.isEmpty()) return;
        int idx = indexOfSong();
        play(queue.get(Math.floorMod(idx + direction, queue.size())));
    }
    private int indexOfSong() {
        for (int i = 0; i < queue.size(); i++) if (MainActivity.songKey(queue.get(i)).equals(MainActivity.songKey(song))) return i;
        return -1;
    }
    private void completed() {
        if (mode == 1) { seekTo(0); start(); return; }
        if (mode == 2 && !queue.isEmpty()) { play(queue.get(new Random().nextInt(queue.size()))); return; }
        int idx = indexOfSong();
        if (idx >= 0 && idx + 1 < queue.size()) play(queue.get(idx + 1));
        else { pause(); seekTo(0); abandonFocus(); }
    }
    public void configureSubtitles() {
        if (!ready || song == null) return;
        boolean enabled = settings.isSubtitleEnabled() || settings.isFloatingSubtitlesEnabled();
        boolean queued = bulk.isQueued(MainActivity.songKey(song));
        String config = MainActivity.songKey(song) + "|" + settings.getChunkSeconds() + "|" + settings.getAsrEngine()
                + "|" + settings.isCloudAsrEnabled() + "|" + settings.isOfflineAsrEnabled()
                + "|" + settings.getCloudAsrEngine() + "|" + enabled + "|" + queued;
        if (config.equals(subtitleConfig)) return;
        subtitleConfig = config; pipeline.stop(); segments.clear();
        cache.setSegmentMs(settings.getChunkSeconds() * 1000L);
        String id = song.path != null ? song.path : String.valueOf(song.uri);
        audioKey = SubtitleManager.audioKey(id, getDuration());
        for (int seg : cache.getProcessedSegments(audioKey)) segments.put(seg, cache.loadSegment(audioKey, seg));
        if (!enabled || queued) return;
        SubtitlePipeline.AudioInfo info = new SubtitlePipeline.AudioInfo();
        info.audioKey = audioKey; info.uri = song.uri != null ? song.uri : Uri.fromFile(new File(song.path));
        info.durationMs = getDuration(); info.totalSegs = cache.segmentCount(info.durationMs);
        pipeline.start(info, getCurrentPosition() / (int)cache.getSegmentMs());
        if (!playRequested) pipeline.pause();
    }
    public void tick() {
        if (!ready) return;
        if (isPlaying()) {
            if (requiresSubtitleWait()) {
                waitForSubtitle();
                return;
            }
            pipeline.updateTarget(getCurrentPosition() / (int)cache.getSegmentMs());
            SubtitleManager.SubtitleLine line = currentLine();
            if (line != null && line.translated.isEmpty() && settings.isTranslationEnabled()) pipeline.requestTranslationBackfill(getCurrentPosition() / (int)cache.getSegmentMs());
        }
    }
    public SubtitleManager.SubtitleLine currentLine() {
        long position = getCurrentPosition() - settings.getSubOffsetMs();
        if (position < 0) return null;
        for (List<SubtitleManager.SubtitleLine> list : segments.values()) for (SubtitleManager.SubtitleLine line : list)
            if (!line.noSpeech && position >= line.startMs && position < line.endMs) return line;
        return null;
    }
    public void save() {
        if (song == null || !ready) return;
        ctx.getSharedPreferences("music_player", Context.MODE_PRIVATE).edit()
                .putString("last_song_uri", song.uri == null ? null : song.uri.toString())
                .putString("last_song_path", song.path).putLong("last_song_pos", getCurrentPosition())
                .putBoolean("last_song_playing", isPlaying()).apply();
    }
}
