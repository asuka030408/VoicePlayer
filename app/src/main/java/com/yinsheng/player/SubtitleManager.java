package com.yinsheng.player;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 字幕缓存系统（分片增量 + 断点续传 + 完整 SRT 合并）。
 *
 * 目录结构（应用私有目录 filesDir/subtitles/<audioKey>/）：
 *   seg_<i>.json   —— 第 i 个分片的字幕（行级数据：时间轴 + 原文 + 译文 + 无语音标记）
 *   progress.json  —— 断点信息：已处理分片数 / 分片总数
 *   full.srt       —— 全部分片完成后合并出的完整双语 SRT
 *
 * 查询策略：播放时按需加载"已处理分片"的字幕行，与播放位置实时匹配；
 * 全部处理完后再合并 full.srt，下次打开直接加载，无需重新计算。
 */
public class SubtitleManager {

    private static final String TAG = "SubtitleManager";
    private static final String PROGRESS_FILE = "progress.json";
    private static final String FULL_SRT_FILE = "full.srt";

    /** 默认分片时长（毫秒）：1 分钟一段。≤1 分钟的音频整段为 1 个分片，天然"完整预加载再播放" */
    public static final long DEFAULT_SEGMENT_MS = 60 * 1000L;

    /** 当前切片时长（毫秒），可在设置页调整（30 秒步进）；默认 60 秒 */
    private volatile long segMs = DEFAULT_SEGMENT_MS;

    public void setSegmentMs(long ms) { this.segMs = Math.max(30000, ms); }
    public long getSegmentMs() { return segMs; }

    /** 一条字幕行 */
    public static class SubtitleLine {
        public long startMs, endMs;
        public String text = "";        // 原文
        public String translated = "";  // 译文
        public boolean noSpeech;        // 该区间未检测到人声

        SubtitleLine(long s, long e, String t, String tr, boolean ns) {
            startMs = s; endMs = e; text = t; translated = tr; noSpeech = ns;
        }
    }

    /** 分片状态 */
    public static class SegmentStatus {
        public final int index;
        public final long startMs, endMs;
        public boolean done;
        public boolean noSpeech;
        SegmentStatus(int index, long startMs, long endMs) {
            this.index = index; this.startMs = startMs; this.endMs = endMs;
        }
    }

    private final Context ctx;

    public SubtitleManager(Context c) { this.ctx = c.getApplicationContext(); }

    /** 字幕缓存格式版本：仅在字幕时间轴/切片/格式不兼容改动时提升（提升后下次启动清空全部缓存）。
     *  普通功能升级（UI、翻译逻辑等）不动它，避免无谓清空已生成的字幕缓存。
     *  当前 39：即 v9.11 修复 VAD 跨分片时间戳错位之后产生的缓存格式（旧缓存全部不可信，
     *  39 及以上格式一致；本次 9.22 仅 UI/翻译逻辑变更，不改动它，保留用户已生成的字幕） */
    private static final int SUBTITLE_CACHE_VER = 39;

    /** 字幕缓存版本清理：比较 SUBTITLE_CACHE_VER，不兼容时清空全部字幕缓存强制重新生成 */
    public void clearCacheIfStale(int currentVersionCode) {
        try {
            android.content.SharedPreferences sp = ctx.getSharedPreferences("sub_cache_ver", Context.MODE_PRIVATE);
            int cached = sp.getInt("version", 0);
            if (cached == SUBTITLE_CACHE_VER) return;
            File dir = new File(ctx.getFilesDir(), "subtitles");
            if (dir.exists()) {
                deleteRecursive(dir);
                android.util.Log.i(TAG, "字幕缓存格式版本不兼容(" + cached + "->" + SUBTITLE_CACHE_VER + ")，已清空，重新生成");
            }
            sp.edit().putInt("version", SUBTITLE_CACHE_VER).apply();
        } catch (Exception e) {
            android.util.Log.w(TAG, "clearCacheIfStale", e);
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        f.delete();
    }

    /** 删除某首歌的全部字幕缓存（完整缓存 + 全部分片，含不同切片长度的变体目录）。
     *  已生成栏删除歌曲时调用：只清缓存与记录，音乐文件本身不动。 */
    public void clearCache(String audioKey) {
        try {
            // 默认切片长度目录（60s，无后缀，兼容历史缓存）
            deleteRecursive(new File(ctx.getFilesDir(), "subtitles/" + audioKey));
            // 自定义切片长度变体目录（_c<秒>）
            File base = new File(ctx.getFilesDir(), "subtitles");
            File[] children = base.listFiles();
            if (children != null) {
                for (File c : children) {
                    if (c.getName().startsWith(audioKey + "_c")) deleteRecursive(c);
                }
            }
        } catch (Exception e) {
            android.util.Log.w(TAG, "clearCache", e);
        }
    }

    /** 生成稳定的音频标识（基于路径/URI + 时长） */
    public static String audioKey(String id, long durationMs) {
        int h = (id + "|" + durationMs).hashCode();
        String hex = Integer.toHexString(h);
        return (id.length() > 32 ? id.substring(0, 32) : id).replaceAll("[^A-Za-z0-9_-]", "_") + "_" + hex;
    }

    private File segDir(String audioKey) {
        // 非默认切片长度使用独立缓存目录（_c<秒>），避免与默认 60s 缓存混用；默认 60s 沿用旧目录（兼容已有缓存）
        String suffix = (segMs == DEFAULT_SEGMENT_MS) ? "" : "_c" + (segMs / 1000);
        File d = new File(ctx.getFilesDir(), "subtitles/" + audioKey + suffix);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private File segFile(String audioKey, int idx) {
        return new File(segDir(audioKey), "seg_" + idx + ".json");
    }

    // ---------- 分片数量 ----------

    public int segmentCount(long durationMs) {
        return Math.max(1, (int) Math.ceil(durationMs / (double) segMs));
    }

    public long segmentStartMs(int idx) { return idx * segMs; }
    public long segmentEndMs(int idx, long totalMs) {
        return Math.min((idx + 1) * segMs, totalMs);
    }

    // ---------- 完整缓存 ----------

    /** 是否存在完整合并的双语 SRT 缓存 */
    public boolean hasFullCache(String audioKey) {
        File f = new File(segDir(audioKey), FULL_SRT_FILE);
        if (!f.exists()) return false;
        try {
            JSONObject meta = new JSONObject(readAll(new File(segDir(audioKey), "complete.json")));
            return isAllDone(audioKey, meta.getInt("total"));
        } catch (Exception e) { return false; }
    }

    /** 加载完整双语字幕（解析 full.srt） */
    public List<SubtitleLine> loadFullCache(String audioKey) {
        List<Integer> committed = getProcessedSegments(audioKey);
        if (!committed.isEmpty()) {
            List<SubtitleLine> all = new ArrayList<>();
            for (int idx : committed) all.addAll(loadSegment(audioKey, idx));
            return all;
        }
        File f = new File(segDir(audioKey), FULL_SRT_FILE);
        if (!f.exists()) return new ArrayList<>();
        List<SubtitleLine> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.indexOf("-->") < 0) continue;
                String[] t = line.trim().split("-->");
                long s = parseSrtTime(t[0].trim());
                long e = parseSrtTime(t[1].trim());
                // 时间行之后：原文一行（无语音时为 [未检测到人声]），再之后是译文一行
                String text = br.readLine();
                if (text == null) break;
                String trans = br.readLine();
                boolean noSpeech = text.contains("未检测到人声");
                if (noSpeech) text = "";
                lines.add(new SubtitleLine(s, e, text, trans == null ? "" : trans, noSpeech));
            }
        } catch (Exception e) {
            Log.w(TAG, "loadFullCache", e);
        }
        return lines;
    }

    private static long parseSrtTime(String s) {
        try {
            String[] p = s.split(":");
            long h = Long.parseLong(p[0]);
            long m = Long.parseLong(p[1]);
            String[] sec = p[2].split(",");
            long ss = Long.parseLong(sec[0]);
            long ms = Long.parseLong(sec[1]);
            return ((h * 60 + m) * 60 + ss) * 1000 + ms;
        } catch (Exception e) { return 0; }
    }

    private static String fmtSrtTime(long ms) {
        long h = ms / 3600000, m = (ms % 3600000) / 60000, s = (ms % 60000) / 1000, mm = ms % 1000;
        return String.format(java.util.Locale.US, "%02d:%02d:%02d,%03d", h, m, s, mm);
    }

    // ---------- 断点 ----------

    /** 已处理完成的分片索引集合（从断点文件读取） */
    public List<Integer> getProcessedSegments(String audioKey) {
        synchronized (SubtitleManager.class) {
            List<Integer> done = new ArrayList<>();
            File[] files = segDir(audioKey).listFiles();
            if (files != null) for (File f : files) {
                if (!f.getName().matches("seg_[0-9]+\\.json")) continue;
                try {
                    JSONObject o = new JSONObject(readAll(f));
                    int idx = o.getInt("idx");
                    if (idx >= 0 && o.optJSONArray("lines") != null
                            && f.getName().equals("seg_" + idx + ".json")) done.add(idx);
                } catch (Exception e) { Log.w(TAG, "Damaged segment; will retry: " + f.getName()); }
            }
            Collections.sort(done);
            return done;
        }
    }

    /** 保存断点（某分片完成） */
    public void saveSegmentDone(String audioKey, int idx) {
        List<Integer> done = getProcessedSegments(audioKey);
        if (!done.contains(idx)) done.add(idx);
        Collections.sort(done);
        try {
            JSONArray arr = new JSONArray();
            for (int i : done) arr.put(i);
            JSONObject o = new JSONObject();
            o.put("done", arr);
            writeAll(new File(segDir(audioKey), PROGRESS_FILE), o.toString());
        } catch (Exception e) { throw new IllegalStateException("字幕进度保存失败", e); }
    }

    // ---------- 分片字幕 ----------

    /** 保存单个分片字幕（已翻译）。engine: "cloud"=联网 ASR 生成（进度条蓝色） / "local"=离线 ASR 生成（紫色） */
    public void saveSegment(String audioKey, int idx, List<SubtitleLine> lines, boolean noSpeech, String engine) {
        synchronized (SubtitleManager.class) {
        try {
            JSONArray arr = new JSONArray();
            for (SubtitleLine l : lines) {
                JSONObject o = new JSONObject();
                o.put("s", l.startMs); o.put("e", l.endMs);
                o.put("t", l.text); o.put("tr", l.translated); o.put("ns", l.noSpeech);
                arr.put(o);
            }
            JSONObject wrap = new JSONObject();
            wrap.put("idx", idx);
            wrap.put("noSpeech", noSpeech);
            wrap.put("verified", true);
            wrap.put("engine", engine == null ? "local" : engine);
            wrap.put("lines", arr);
            writeAll(segFile(audioKey, idx), wrap.toString());
            saveSegmentDone(audioKey, idx);
            File full = new File(segDir(audioKey), FULL_SRT_FILE);
            if (full.exists() && !full.delete()) throw new IllegalStateException("无法刷新完整字幕缓存");
        } catch (Exception e) { throw new IllegalStateException("字幕保存失败，请检查存储空间", e); }
        }
    }

    /** 该分片是否由联网 ASR 生成（无 engine 字段的旧缓存按离线处理） */
    public boolean isCloudSegment(String audioKey, int idx) {
        try {
            File f = segFile(audioKey, idx);
            if (!f.exists()) return false;
            JSONObject o = new JSONObject(readAll(f));
            return "cloud".equals(o.optString("engine"));
        } catch (Exception e) { return false; }
    }

    /** 已处理分片中由联网 ASR 生成的分片集合（进度条蓝色标记用） */
    public Set<Integer> getCloudSegments(String audioKey) {
        Set<Integer> set = new java.util.HashSet<>();
        for (int idx : getProcessedSegments(audioKey)) {
            if (isCloudSegment(audioKey, idx)) set.add(idx);
        }
        return set;
    }

    /** 加载单个分片字幕 */
    public List<SubtitleLine> loadSegment(String audioKey, int idx) {
        List<SubtitleLine> lines = new ArrayList<>();
        File f = segFile(audioKey, idx);
        if (!f.exists()) return lines;
        try {
            JSONObject o = new JSONObject(readAll(f));
            JSONArray arr = o.optJSONArray("lines");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject j = arr.getJSONObject(i);
                    lines.add(new SubtitleLine(j.optLong("s"), j.optLong("e"),
                            j.optString("t"), j.optString("tr"), j.optBoolean("ns")));
                }
            }
        } catch (Exception e) { Log.w(TAG, "loadSegment", e); }
        return lines;
    }

    // ---------- 合并完整 SRT ----------

    /** 所有分片完成后，合并生成完整双语 SRT */
    public void mergeFullSrt(String audioKey, int totalSegs) {
        if (!isAllDone(audioKey, totalSegs)) throw new IllegalStateException("字幕尚未完整生成");
        File dir = segDir(audioKey);
        StringBuilder sb = new StringBuilder();
        int n = 1;
        for (int i = 0; i < totalSegs; i++) {
            for (SubtitleLine l : loadSegment(audioKey, i)) {
                if (l.text.isEmpty() && !l.noSpeech) continue;
                sb.append(n++).append('\n');
                sb.append(fmtSrtTime(l.startMs)).append(" --> ").append(fmtSrtTime(l.endMs)).append('\n');
                if (l.noSpeech) sb.append("[未检测到人声]");
                else {
                    sb.append(l.text).append('\n');
                    if (!l.translated.isEmpty()) sb.append(l.translated);
                }
                sb.append("\n\n");
            }
        }
        writeAll(new File(dir, FULL_SRT_FILE), sb.toString());
        writeAll(new File(dir, "complete.json"), "{\"total\":" + totalSegs + "}");
    }

    /** 该音频是否全部处理完成 */
    public boolean isAllDone(String audioKey, int totalSegs) {
        if (totalSegs <= 0) return false;
        List<Integer> done = getProcessedSegments(audioKey);
        for (int i = 0; i < totalSegs; i++) if (!done.contains(i)) return false;
        return true;
    }

    /** 已处理分片中被标记为"整片无语音"的分片集合（ASMR 静音段，进度条深灰标记） */
    public Set<Integer> getNoSpeechSegments(String audioKey) {
        Set<Integer> set = new HashSet<>();
        List<Integer> done = getProcessedSegments(audioKey);
        for (int idx : done) {
            try {
                JSONObject o = new JSONObject(readAll(segFile(audioKey, idx)));
                if (o.optBoolean("noSpeech")) set.add(idx);
            } catch (Exception ignored) {}
        }
        return set;
    }

    // ---------- 工具 ----------

    private static String readAll(File f) throws Exception {
        synchronized (SubtitleManager.class) {
            android.util.AtomicFile atomic = new android.util.AtomicFile(f);
            return new String(atomic.readFully(), StandardCharsets.UTF_8);
        }
    }

    private static void writeAll(File f, String s) {
        synchronized (SubtitleManager.class) {
            android.util.AtomicFile atomic = new android.util.AtomicFile(f);
            FileOutputStream out = null;
            try {
                out = atomic.startWrite();
                out.write(s.getBytes(StandardCharsets.UTF_8));
                atomic.finishWrite(out);
            } catch (Exception e) {
                atomic.failWrite(out);
                throw new IllegalStateException("无法保存字幕缓存", e);
            }
        }
    }
}
