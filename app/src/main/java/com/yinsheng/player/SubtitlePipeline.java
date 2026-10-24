package com.yinsheng.player;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.util.*;
import java.util.concurrent.*;

/** Each song has its own generation. Native resources belong only to the worker. */
public class SubtitlePipeline {
    public interface Listener {
        void onSegmentStarted(int idx); void onProgress(int idx, int percent);
        void onSegmentDone(int idx, boolean noSpeech); void onAllDone();
        void onModelPreparing(String name); void onModelMissing();
        void onEngineError(String message); void onInfo(String message);
    }
    public static class AudioInfo {
        public String audioKey; public Uri uri; public long durationMs; public int totalSegs;
    }
    private static final class Job {
        final AudioInfo info;
        final SubtitleManager cache;
        final Set<Integer> done = ConcurrentHashMap.newKeySet();
        final Map<Integer, Integer> failures = new ConcurrentHashMap<>();
        final Set<Integer> translationFailed = ConcurrentHashMap.newKeySet();
        volatile int target, from;
        volatile boolean paused, allRequested, scheduled, completeNotified;
        Job(AudioInfo i, SubtitleManager c, int from) {
            info = i; cache = c; this.from = from; target = Math.min(i.totalSegs - 1, from + 1);
            done.addAll(c.getProcessedSegments(i.audioKey));
        }
    }
    private final Context ctx;
    private final SubtitleManager sourceCache;
    private final AppSettings settings;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Job current;
    private volatile Listener listener;
    private volatile boolean closed;
    // Worker thread only.
    private SpeechEngine engine;
    private String engineType;
    private boolean backfillQueued;
    private long lastBackfill;
    private String translationFingerprint = "";

    public SubtitlePipeline(Context c, SubtitleManager cache, AppSettings settings) {
        ctx = c.getApplicationContext(); sourceCache = cache; this.settings = settings;
    }
    public void setListener(Listener l) { listener = l; }
    public boolean isProcessing() { Job j = current; return j != null && !j.paused && j.done.size() < j.info.totalSegs; }
    public synchronized void start(AudioInfo info, int from) {
        if (closed) return;
        SubtitleManager cache = new SubtitleManager(ctx);
        cache.setSegmentMs(sourceCache.getSegmentMs());
        current = new Job(info, cache, Math.max(0, Math.min(from, info.totalSegs - 1)));
        schedule(current, 0);
    }
    public synchronized void stop() { current = null; }
    public synchronized void pause() { if (current != null) current.paused = true; }
    public synchronized void resume() {
        if (current != null) { current.paused = false; schedule(current, 0); }
    }
    public synchronized void updateTarget(int seg) {
        Job j = current;
        if (j == null) return;
        j.target = Math.max(j.target, Math.min(j.info.totalSegs - 1, seg + 1));
        schedule(j, 0);
    }
    public synchronized void redirect(int seg) {
        Job j = current;
        if (j == null) return;
        j.from = Math.max(0, Math.min(seg, j.info.totalSegs - 1));
        j.target = Math.max(j.target, Math.min(j.info.totalSegs - 1, j.from + 1));
        schedule(j, 0);
    }
    public synchronized void processAll() {
        Job j = current;
        if (j == null) return;
        j.failures.clear(); j.completeNotified = false; j.paused = false; j.allRequested = true;
        j.target = j.info.totalSegs - 1; schedule(j, 0);
    }
    public synchronized void releaseIdle() {
        current = null;
        if (!closed) worker.execute(this::releaseEngine);
    }
    public synchronized void shutdown() {
        current = null; closed = true;
        // No shutdownNow + concurrent JNI release. Cleanup follows any running decode.
        worker.execute(this::releaseEngine);
        worker.shutdown();
    }
    private void releaseEngine() {
        if (engine != null) { engine.release(); engine = null; engineType = null; }
    }
    private boolean valid(Job j) { return !closed && current == j; }
    private void check(Job j) {
        if (!valid(j) || Thread.currentThread().isInterrupted()) throw new CancellationException();
    }
    private void post(Job j, java.util.function.Consumer<Listener> event) {
        main.post(() -> { Listener l = listener; if (valid(j) && l != null) event.accept(l); });
    }
    private synchronized void schedule(Job j, long delay) {
        if (!valid(j) || j.scheduled || j.paused) return;
        j.scheduled = true;
        worker.schedule(() -> step(j), delay, TimeUnit.MILLISECONDS);
    }
    private void step(Job j) {
        long retryDelay = 0;
        try {
            if (!valid(j) || j.paused) return;
            int next = -1;
            for (int n = 0; n < j.info.totalSegs; n++) {
                int i = (j.from + n) % j.info.totalSegs;
                if (i <= j.target && !j.done.contains(i) && j.failures.getOrDefault(i, 0) < 3) { next = i; break; }
            }
            if (next < 0) {
                if (j.done.size() == j.info.totalSegs && !j.completeNotified) {
                    check(j); j.cache.mergeFullSrt(j.info.audioKey, j.info.totalSegs);
                    j.completeNotified = true; post(j, Listener::onAllDone);
                }
                return;
            }
            final int seg = next;
            try {
                process(j, seg);
            } catch (CancellationException ignored) { return; }
            catch (Exception e) {
                if (!valid(j)) return;
                // 云端失败必须交给界面确认是否切换离线，不能静默重试三次或自动降级。
                if (e instanceof IllegalStateException && e.getMessage() != null
                        && e.getMessage().startsWith("联网 ASR 不可用")) throw e;
                int attempts = j.failures.merge(seg, 1, Integer::sum);
                retryDelay = 5000L * attempts;
                post(j, l -> l.onInfo("第 " + (seg + 1) + " 段生成失败" + (attempts < 3 ? "，稍后重试" : "，点击补全字幕可重试") + "：" + safeMessage(e)));
            }
        } catch (Exception e) {
            j.completeNotified = true;
            j.paused = true; // 等待用户决定是否启用离线，避免弹窗期间重复发起云端请求。
            post(j, l -> l.onEngineError(safeMessage(e)));
        }
        finally {
            synchronized (this) {
                j.scheduled = false;
                if (valid(j) && !j.paused) {
                    boolean more = false;
                    for (int i = 0; i <= j.target; i++) if (!j.done.contains(i) && j.failures.getOrDefault(i, 0) < 3) { more = true; break; }
                    if (more || (j.done.size() == j.info.totalSegs && !j.completeNotified)) schedule(j, retryDelay);
                }
            }
        }
    }
    private static String safeMessage(Exception e) {
        return e instanceof IllegalStateException && e.getMessage() != null ? e.getMessage() : "请检查音频、模型或网络";
    }
    private SpeechEngine offline(Job j) throws Exception {
        if (!settings.isOfflineAsrEnabled()) throw new IllegalStateException("离线 ASR 已关闭");
        String type = settings.getAsrEngine();
        final boolean whisper = "whisper".equals(type);
        post(j, l -> l.onModelPreparing("离线·" + (whisper ? "Whisper" : "SenseVoice")));
        if (engine != null && type.equals(engineType)) return engine;
        check(j); releaseEngine();
        boolean ready = whisper ? ModelManager.ensureWhisperModels(ctx) : ModelManager.ensureModels(ctx);
        check(j);
        if (!ready) throw new IllegalStateException("离线模型不可用");
        engine = whisper ? new SpeechEngine(ctx, type, ModelManager.whisperEncoderPath(ctx), ModelManager.whisperDecoderPath(ctx), ModelManager.whisperTokensPath(ctx))
                : new SpeechEngine(ctx, type, ModelManager.modelPath(ctx), ModelManager.tokensPath(ctx));
        engineType = type;
        return engine;
    }
    private void process(Job j, int seg) throws Exception {
        check(j); post(j, l -> l.onSegmentStarted(seg));
        if (!settings.isCloudAsrEnabled() && !settings.isOfflineAsrEnabled()) {
            throw new IllegalStateException("联网 ASR 不可用：联网 ASR 和离线 ASR 均已关闭，请在设置中至少开启一个");
        }
        long start = j.cache.segmentStartMs(seg);
        float[] pcm = AudioPcmDecoder.decodeRange(ctx, j.info.uri, start, j.cache.segmentEndMs(seg, j.info.durationMs));
        check(j);
        if (pcm == null || pcm.length == 0) throw new IllegalStateException("音频解码失败");
        post(j, l -> l.onProgress(seg, 30));
        List<SubtitleManager.SubtitleLine> lines = new ArrayList<>();
        boolean usedCloud = false;
        if (settings.isCloudAsrEnabled()) {
            try {
                CloudAsrEngine.Provider cloud = CloudAsrEngine.create(settings);
                post(j, l -> l.onModelPreparing("联网·" + cloud.name()));
                List<CloudAsrEngine.Sentence> result = cloud.recognize(BulkGenerator.pcmToWav16k(pcm));
                check(j); usedCloud = true;
                for (CloudAsrEngine.Sentence s : result) lines.add(new SubtitleManager.SubtitleLine(start + s.startMs, start + Math.max(s.startMs + 1, s.endMs), s.text, "", false));
            } catch (CancellationException e) { throw e; }
            catch (Exception e) { check(j); throw new IllegalStateException("联网 ASR 不可用：额度不足、密钥错误或接口失败"); }
        }
        if (!usedCloud) {
            SpeechEngine.AsrResult result = offline(j).recognize(pcm, start);
            check(j);
            for (SpeechEngine.SpeechSeg s : result.segments) lines.add(new SubtitleManager.SubtitleLine(s.startMs, s.endMs, s.text, "", false));
        }
        post(j, l -> l.onProgress(seg, 75));
        translate(j, lines);
        check(j);
        j.cache.saveSegment(j.info.audioKey, seg, lines, lines.isEmpty(), usedCloud ? "cloud" : "local");
        check(j); j.done.add(seg);
        post(j, l -> l.onSegmentDone(seg, lines.isEmpty()));
    }
    private boolean translate(Job j, List<SubtitleManager.SubtitleLine> lines) throws Exception {
        if (!settings.isTranslationEnabled()) return false;
        TranslationEngine translator = TranslationEngine.Factory.create(ctx, settings);
        if (translator == null || !translator.isAvailable()) return false;
        List<SubtitleManager.SubtitleLine> needed = new ArrayList<>();
        for (SubtitleManager.SubtitleLine l : lines) if (!l.noSpeech && !l.text.isEmpty() && l.translated.isEmpty() && !isChineseText(l.text)) needed.add(l);
        boolean changed = false;
        for (int start = 0; start < needed.size(); start += 3) {
            check(j);
            if (!settings.isTranslationEnabled()) break;
            List<String> texts = new ArrayList<>();
            for (int i = start; i < Math.min(start + 3, needed.size()); i++) texts.add(needed.get(i).text);
            try {
                List<String> result = translator.translateBatch(texts);
                check(j);
                if (result != null) for (int i = 0; i < texts.size() && i < result.size(); i++) {
                    String tr = result.get(i);
                    if (tr != null && !tr.trim().isEmpty()) { needed.get(start + i).translated = tr.trim(); changed = true; }
                }
            } catch (CancellationException e) { throw e; }
            catch (Exception e) { post(j, l -> l.onInfo("翻译暂时失败，原文已保留，可重新开启翻译重试")); }
        }
        return changed;
    }
    public static boolean isChineseText(String text) { return SubtitleLanguage.isChinese(text); }
    public synchronized void requestTranslationBackfill(int from) {
        Job j = current;
        if (!valid(j) || j == null || j.paused || !settings.isTranslationEnabled() || backfillQueued) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastBackfill < 2000) return;
        lastBackfill = now;
        String fp = settings.getTransEngine() + settings.getBaiduKey() + settings.getBaiduAppid() + settings.getDeepseekKey() + settings.getZhipuKey() + settings.getAlibabaKey();
        if (!fp.equals(translationFingerprint)) { translationFingerprint = fp; j.translationFailed.clear(); }
        backfillQueued = true;
        worker.execute(() -> {
            try {
                check(j);
                for (int n = 0; n < j.info.totalSegs; n++) {
                    int seg = (Math.max(0, from) + n) % j.info.totalSegs;
                    if (!j.done.contains(seg) || j.translationFailed.contains(seg)) continue;
                    check(j); if (j.paused) break;
                    List<SubtitleManager.SubtitleLine> lines = j.cache.loadSegment(j.info.audioKey, seg);
                    boolean changed = translate(j, lines); check(j);
                    if (changed) {
                        boolean noSpeech = j.cache.getNoSpeechSegments(j.info.audioKey).contains(seg);
                        String origin = j.cache.isCloudSegment(j.info.audioKey, seg) ? "cloud" : "local";
                        j.cache.saveSegment(j.info.audioKey, seg, lines, noSpeech, origin);
                        if (j.done.size() == j.info.totalSegs) j.cache.mergeFullSrt(j.info.audioKey, j.info.totalSegs);
                        post(j, l -> l.onSegmentDone(seg, noSpeech));
                    }
                    j.translationFailed.add(seg); // one pass; explicit toggle/settings change permits retry
                    break; // Yield to incremental recognition between translation segments.
                }
            } catch (CancellationException ignored) {}
            catch (Exception e) { post(j, l -> l.onInfo(safeMessage(e))); }
            finally { synchronized (this) { backfillQueued = false; } }
        });
    }
    public synchronized void retryTranslation() {
        if (current != null) current.translationFailed.clear();
        lastBackfill = 0;
    }
    public void watchdogStopBackfill() { pause(); }
}
