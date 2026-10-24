package com.yinsheng.player;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 后台批量生成字幕（长按歌曲 → 后台生成）。
 *
 * 与播放时的增量管线（SubtitlePipeline）独立：
 *  - 支持离线引擎（SenseVoice/Whisper，本机推理）与联网引擎（腾讯/阿里/讯飞，云端识别）；
 *  - 逐个任务顺序处理（单线程，避免同时跑多个大模型/多路上传）；
 *  - 复用 SubtitleManager 的分片缓存：逐分片解码→识别→翻译→保存缓存，全部完成合并 full.srt；
 *  - 断点续传：已处理的分片自动跳过；完成记录长期保留（「生成完毕」tab 展示）。
 */
public class BulkGenerator {

    private static final String TAG = "BulkGenerator";

    public interface Listener {
        void onChanged();   // 队列或进度变化（主线程回调，UI 刷新）
    }

    public static class Task {
        public String id;            // songKey（去重标识）
        public MainActivity.Song song;
        public String engineKey;     // sensevoice / whisper / cloud
        public String engineLabel;   // 展示用（如 "SenseVoice" / "腾讯云"）
        public String status;        // pending / running / done / failed
        public int progress;         // 0~100
        public String detail;        // "第 3/10 分片"、"识别失败：xxx"
        public long doneTime;        // 完成时间戳（done 时写入）

        Task(MainActivity.Song s, String key, String label) {
            id = MainActivity.songKey(s);
            song = s;
            engineKey = key;
            engineLabel = label;
            status = "pending";
            progress = 0;
        }
    }

    private final Context ctx;
    private final SubtitleManager subMgr;
    private final AppSettings settings;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<Task> tasks = new ArrayList<>();
    private Listener listener;
    private Listener observer;
    private boolean running;
    private volatile boolean suspended;

    public BulkGenerator(Context c, SubtitleManager subMgr, AppSettings settings) {
        this.ctx = c.getApplicationContext();
        this.subMgr = subMgr;
        this.settings = settings;
    }

    public void setListener(Listener l) { this.listener = l; }
    public void setObserver(Listener l) { this.observer = l; }

    public synchronized List<Task> getTasks() { return new ArrayList<>(tasks); }

    /** 是否还有未完成的任务 */
    public synchronized boolean hasPending() {
        for (Task t : tasks) if (!"done".equals(t.status) && !"failed".equals(t.status)) return true;
        return false;
    }

    /** 任务是否已在队列中 */
    public synchronized boolean isQueued(String songKey) {
        for (Task t : tasks) if (t.id.equals(songKey) && !"done".equals(t.status) && !"failed".equals(t.status)) return true;
        return false;
    }

    /** 加入后台生成任务（已排队/进行中的同一首歌去重） */
    public void enqueue(MainActivity.Song s, String engineKey) {
        String key = MainActivity.songKey(s);
        synchronized (this) {
            if (isQueued(key)) return;
            String label;
            if ("cloud".equals(engineKey)) {
                label = cloudLabel();
            } else if ("whisper".equals(engineKey)) {
                label = "Whisper";
            } else {
                label = "SenseVoice";
            }
            tasks.add(new Task(s, engineKey, label));
        }
        if (!running) {
            running = true;
            executor.submit(this::runLoop);
        }
        postChanged();
    }

    /** 移除已完成/失败的任务记录（保留完成记录在 AppSettings 中，用于「生成完毕」tab） */
    public synchronized void removeTask(Task t) {
        tasks.remove(t);
    }
    public synchronized void cancelTask(Task t) {
        if (t == null || "done".equals(t.status) || "failed".equals(t.status)) return;
        t.status = "canceled";
        t.detail = "已暂停，已生成分段保留";
        tasks.remove(t);
        postChanged();
    }

    public void shutdown() {
        suspended = true;
        executor.shutdownNow();
    }

    /** Foreground playback owns CPU and cloud requests. Existing segment cache is retained. */
    public void suspendForForeground() { suspended = true; postChanged(); }
    public void resumeAfterForeground() { suspended = false; if (!running && hasPending()) { running = true; executor.submit(this::runLoop); } postChanged(); }

    // ---------------------------------------------------------------
    // 工作循环（单线程）
    // ---------------------------------------------------------------
    private void runLoop() {
        while (true) {
            Task t;
            synchronized (this) {
                t = null;
                for (Task x : tasks) {
                    if ("pending".equals(x.status)) { t = x; break; }
                }
                if (t == null) { running = false; return; }
                t.status = "running";
                t.progress = 1;
            }
            postChanged();
            try {
                while (suspended && !Thread.currentThread().isInterrupted()) {
                    try { Thread.sleep(250); } catch (InterruptedException e) { return; }
                }
                if (Thread.currentThread().isInterrupted()) return;
                processSong(t);
                t.status = "done";
                t.progress = 100;
                t.detail = "已完成";
                t.doneTime = System.currentTimeMillis();
                settings.putDoneRecord(t.id, t.doneTime);
            } catch (Throwable e) {
                Log.w(TAG, "生成失败 " + t.song.title, e);
                t.status = "failed";
                t.detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            postChanged();
            // 完成后任务卡保留 2 秒显示「已完成 100%」，让用户看到完整流程再消失
            final Task ft = t;
            new Thread(() -> {
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                synchronized (BulkGenerator.this) { tasks.remove(ft); }
                postChanged();
            }).start();
        }
    }

    private String cloudLabel() {
        switch (settings.getCloudAsrEngine()) {
            case "alibaba": return "阿里云(百炼)";
            case "iflytek": return "讯飞";
            default: return "腾讯云";
        }
    }

    // ---------------------------------------------------------------
    // 处理单个歌曲：逐分片 解码 → 识别 → 翻译 → 缓存 → 合并
    // ---------------------------------------------------------------
    private void processSong(Task t) throws Exception {
        SubtitleManager subMgr = new SubtitleManager(ctx);
        MainActivity.Song s = t.song;
        if (s.path == null && s.uri == null) throw new Exception("无数据源");
        Uri uri = s.uri != null ? s.uri : Uri.fromFile(new File(s.path));
        // 时长用 MediaPlayer 探测，与播放端 setupSubtitle 的 duration 完全一致，
        // 保证后台生成的缓存 key 播放时必定命中
        long durationMs = probeDuration(uri, s.duration);
        // 缓存 key 必须与播放端 setupSubtitle 完全一致（裸 path/uri 字符串，无 "path:" 前缀），
        // 否则后台生成的缓存播放时命中不了
        String id = s.path != null ? s.path : String.valueOf(s.uri);
        String audioKey = SubtitleManager.audioKey(id, durationMs);
        subMgr.setSegmentMs(settings.getChunkSeconds() * 1000L);
        int totalSegs = subMgr.segmentCount(durationMs);

        // 引擎准备
        SpeechEngine offEngine = null;
        CloudAsrEngine.Provider cloud = null;
        if ("cloud".equals(t.engineKey)) {
            if (!settings.isCloudAsrEnabled() || !settings.isCloudAsrConfigured()) {
                throw new Exception("联网 ASR 未开启或密钥未填全，请在设置中开启");
            }
            cloud = CloudAsrEngine.create(settings);
        } else {
            boolean whisper = "whisper".equals(t.engineKey);
            if (whisper) {
                if (!ModelManager.isWhisperReady(ctx) && !ModelManager.ensureWhisperModels(ctx))
                    throw new Exception("Whisper 模型缺失");
                offEngine = new SpeechEngine(ctx, "whisper",
                        ModelManager.whisperEncoderPath(ctx), ModelManager.whisperDecoderPath(ctx),
                        ModelManager.whisperTokensPath(ctx));
            } else {
                if (!ModelManager.isModelReady(ctx) && !ModelManager.ensureModels(ctx))
                    throw new Exception("SenseVoice 模型缺失");
                offEngine = new SpeechEngine(ctx, "sensevoice",
                        ModelManager.modelPath(ctx), ModelManager.tokensPath(ctx));
            }
        }
        TranslationEngine translator = null;
        try { translator = TranslationEngine.Factory.create(ctx, settings); } catch (Throwable ignored) {}

        try {
            // 断点续传：已有缓存的分片跳过
            java.util.List<Integer> doneSegs = subMgr.getProcessedSegments(audioKey);
            for (int seg = 0; seg < totalSegs; seg++) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("任务已取消");
                if (doneSegs.contains(seg)) continue;
                t.progress = 1 + seg * 92 / totalSegs;
                t.detail = "第 " + (seg + 1) + "/" + totalSegs + " 分片";
                postChanged();

                long startMs = subMgr.segmentStartMs(seg);
                long endMs = subMgr.segmentEndMs(seg, durationMs);
                float[] pcm = AudioPcmDecoder.decodeRange(ctx, uri, startMs, endMs);
                if (pcm == null || pcm.length == 0) throw new IllegalStateException("音频解码失败，请重试");

                List<SubtitleManager.SubtitleLine> lines = new ArrayList<>();
                boolean noSpeech;
                if (cloud != null) {
                    // 在线：分片 PCM → WAV → 云端识别（按音频时长计费）
                    byte[] wav = pcmToWav16k(pcm);
                    List<CloudAsrEngine.Sentence> sents = cloud.recognize(wav);
                    noSpeech = sents.isEmpty();
                    for (CloudAsrEngine.Sentence st : sents) {
                        long gs = startMs + st.startMs, ge = startMs + st.endMs;
                        if (ge <= gs) ge = gs + 1000;
                        lines.add(new SubtitleManager.SubtitleLine(gs, ge, st.text, "", false));
                    }
                } else {
                    SpeechEngine.AsrResult asr = offEngine.recognize(pcm, startMs);
                    noSpeech = !asr.hasSpeech;
                    for (SpeechEngine.SpeechSeg st : asr.segments) {
                        lines.add(new SubtitleManager.SubtitleLine(st.startMs, st.endMs, st.text, "", false));
                    }
                }

                // 翻译（与播放管线同规则：开关开启 + 非中文才翻译）
                if (!lines.isEmpty() && settings.isTranslationEnabled() && translator != null && translator.isAvailable()) {
                    List<Integer> trIdx = new ArrayList<>();
                    List<String> trTexts = new ArrayList<>();
                    for (int i = 0; i < lines.size(); i++) {
                        SubtitleManager.SubtitleLine l = lines.get(i);
                        if (!l.text.isEmpty() && !SubtitlePipeline.isChineseText(l.text)) {
                            trIdx.add(i); trTexts.add(l.text);
                        }
                    }
                    if (!trTexts.isEmpty()) {
                        try {
                            List<String> trs = translator.translateBatch(trTexts);
                            if (trs != null) {
                                for (int i = 0; i < trIdx.size() && i < trs.size(); i++) {
                                    if (trs.get(i) != null) lines.get(trIdx.get(i)).translated = trs.get(i).trim();
                                }
                            }
                        } catch (Throwable ex) { Log.w(TAG, "翻译失败(后台生成)", ex); }
                    }
                }
                subMgr.saveSegment(audioKey, seg, lines, noSpeech, cloud != null ? "cloud" : "local");
                doneSegs.add(seg);
            }
            subMgr.mergeFullSrt(audioKey, totalSegs);
        } finally {
            if (offEngine != null) offEngine.release();
        }
    }

    /** 用 MediaPlayer 探测音频真实时长（毫秒），失败回退传入值 */
    private long probeDuration(Uri uri, long fallback) {
        try {
            android.media.MediaPlayer mp = new android.media.MediaPlayer();
            try {
                mp.setDataSource(ctx, uri);
                mp.prepare();
                int d = mp.getDuration();
                return d > 0 ? d : fallback;
            } finally {
                try { mp.release(); } catch (Exception ignored) {}
            }
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** 16k mono float PCM → 16k mono 16bit WAV（字节数组，供云端上传） */
    static byte[] pcmToWav16k(float[] pcm) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(pcm.length * 2 + 44);
        int sampleRate = 16000, channels = 1, bits = 16;
        int dataLen = pcm.length * 2;
        writeStr(bos, "RIFF");
        writeIntLE(bos, 36 + dataLen);
        writeStr(bos, "WAVE");
        writeStr(bos, "fmt ");
        writeIntLE(bos, 16);
        writeShortLE(bos, 1);            // PCM
        writeShortLE(bos, channels);
        writeIntLE(bos, sampleRate);
        writeIntLE(bos, sampleRate * channels * bits / 8);
        writeShortLE(bos, channels * bits / 8);
        writeShortLE(bos, bits);
        writeStr(bos, "data");
        writeIntLE(bos, dataLen);
        for (float f : pcm) {
            short s = (short) Math.max(-32768, Math.min(32767, (int) (f * 32767)));
            writeShortLE(bos, s);
        }
        return bos.toByteArray();
    }

    private static void writeStr(ByteArrayOutputStream bos, String s) throws Exception {
        bos.write(s.getBytes("US-ASCII"));
    }
    private static void writeIntLE(ByteArrayOutputStream bos, int v) {
        bos.write(v & 0xFF); bos.write((v >> 8) & 0xFF); bos.write((v >> 16) & 0xFF); bos.write((v >> 24) & 0xFF);
    }
    private static void writeShortLE(ByteArrayOutputStream bos, int v) {
        bos.write(v & 0xFF); bos.write((v >> 8) & 0xFF);
    }

    private void postChanged() {
        if (listener != null || observer != null) {
            android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.post(() -> { if (observer != null) observer.onChanged(); if (listener != null) listener.onChanged(); });
        }
    }
}
