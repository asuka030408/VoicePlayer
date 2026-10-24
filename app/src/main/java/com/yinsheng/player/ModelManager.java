package com.yinsheng.player;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 模型管理：SenseVoice-small-int8（ASR）模型文件。
 *
 * 学习 Demo 策略：
 *  - silero_vad.onnx（约 2.2MB）打包在 assets 中，首次使用时自动复制到应用目录；
 *  - SenseVoice int8（约 228MB）也直接内置在安装包 assets 中（APK 体积约 280MB，
 *    但装上即用、零下载、零网络依赖）；首次使用字幕时自动复制到应用目录：
 *      1) 应用目录已有完整模型（手动放置/之前复制过）→ 直接使用；
 *      2) 否则从安装包 assets 复制（无需网络）；
 *      3) 仅当 assets 异常缺失时才引导从 ModelScope 下载（兜底）。
 */
public class ModelManager {

    public static final String MODEL_FILE = "model.int8.onnx";
    public static final String TOKENS_FILE = "tokens.txt";
    public static final String VAD_FILE = "silero_vad.onnx";

    // ---------- Whisper 模型（sherpa-onnx 格式：encoder/decoder 分离 + tokens）----------
    public static final String WHISPER_ENCODER = "encoder.onnx";
    public static final String WHISPER_DECODER = "decoder.onnx";
    public static final String WHISPER_TOKENS = "tokens.txt";
    /** Whisper 模型最小体积（small fp32 约 400MB+；防御截断下载） */
    // Whisper-small int8：encoder.onnx 实际约 107MB、decoder.onnx 约 250MB。
    // 阈值必须低于实际体积（之前误设 200MB，导致 encoder 永远判"不完整"、
    // 每次播放都重新复制 375MB 并误报"模型复制失败"）
    private static final long WHISPER_MIN_BYTES = 100L * 1024 * 1024;
    private static final long WHISPER_DEC_MIN_BYTES = 240L * 1024 * 1024;

    // 兜底下载源：ModelScope 国内镜像（仅当安装包 assets 异常缺失时使用）
    public static final String MODEL_URL =
            "https://modelscope.cn/models/fengge2024/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/master/model.int8.onnx";
    public static final String TOKENS_URL =
            "https://modelscope.cn/models/fengge2024/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/master/tokens.txt";

    public static File sensevoiceDir(Context c) {
        return new File(c.getExternalFilesDir(null), "models/sensevoice");
    }

    public static File whisperDir(Context c) {
        return new File(c.getExternalFilesDir(null), "models/whisper");
    }

    public static String modelPath(Context c) {
        return new File(sensevoiceDir(c), MODEL_FILE).getAbsolutePath();
    }

    public static String tokensPath(Context c) {
        return new File(sensevoiceDir(c), TOKENS_FILE).getAbsolutePath();
    }

    public static String whisperEncoderPath(Context c) {
        return new File(whisperDir(c), WHISPER_ENCODER).getAbsolutePath();
    }

    public static String whisperDecoderPath(Context c) {
        return new File(whisperDir(c), WHISPER_DECODER).getAbsolutePath();
    }

    public static String whisperTokensPath(Context c) {
        return new File(whisperDir(c), WHISPER_TOKENS).getAbsolutePath();
    }

    /** 安装包 assets 中是否内置了 SenseVoice 模型 */
    public static boolean hasAssetsModel(Context c) {
        try {
            c.getAssets().open("models/sensevoice/" + MODEL_FILE).close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 安装包 assets 中是否内置了 Whisper 模型 */
    public static boolean hasAssetsWhisper(Context c) {
        try {
            c.getAssets().open("models/whisper/" + WHISPER_ENCODER).close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 确保模型就绪（阻塞调用，请在后台线程执行）：
     *  1. 应用目录已有完整模型 → 直接可用；
     *  2. 否则从安装包 assets 内置模型复制（无需网络，别人装上即用）；
     *  3. assets 异常缺失时才尝试从 ModelScope 下载（兜底）。
     */
    public static boolean ensureModels(Context c) {
        if (isModelReady(c)) return true;
        if (hasAssetsModel(c)) {
            File dir = sensevoiceDir(c);
            if (!dir.exists()) dir.mkdirs();
            boolean ok1 = copyAsset(c, "models/sensevoice/" + MODEL_FILE, new File(dir, MODEL_FILE));
            boolean ok2 = copyAsset(c, "models/sensevoice/" + TOKENS_FILE, new File(dir, TOKENS_FILE));
            if (!ok1 || !ok2) {
                new File(dir, MODEL_FILE).delete();
                new File(dir, TOKENS_FILE).delete();
                return false;
            }
            return isModelReady(c);
        }
        return downloadModel(c);
    }

    /** 确保 Whisper 模型就绪（阻塞调用，后台线程）：assets 内置 → 复制；异常缺失 → 失败提示 */
    public static boolean ensureWhisperModels(Context c) {
        if (isWhisperReady(c)) return true;
        if (hasAssetsWhisper(c)) {
            File dir = whisperDir(c);
            if (!dir.exists()) dir.mkdirs();
            boolean ok1 = copyAsset(c, "models/whisper/" + WHISPER_ENCODER, new File(dir, WHISPER_ENCODER));
            boolean ok2 = copyAsset(c, "models/whisper/" + WHISPER_DECODER, new File(dir, WHISPER_DECODER));
            boolean ok3 = copyAsset(c, "models/whisper/" + WHISPER_TOKENS, new File(dir, WHISPER_TOKENS));
            if (!ok1 || !ok2 || !ok3) {
                new File(dir, WHISPER_ENCODER).delete();
                new File(dir, WHISPER_DECODER).delete();
                new File(dir, WHISPER_TOKENS).delete();
                return false;
            }
            return isWhisperReady(c);
        }
        return false;   // Whisper 无兜底下载源（体积大，内置优先）
    }

    /** Whisper 模型是否就绪 */
    public static boolean isWhisperReady(Context c) {
        File enc = new File(whisperEncoderPath(c));
        File dec = new File(whisperDecoderPath(c));
        File tok = new File(whisperTokensPath(c));
        if (!enc.exists() || enc.length() < WHISPER_MIN_BYTES) return false;
        if (!dec.exists() || dec.length() < WHISPER_DEC_MIN_BYTES) return false;
        if (!tok.exists() || tok.length() < 100) return false;
        return !isTextFile(enc) && !isTextFile(dec);
    }

    /** 从 assets 复制单个文件到目标路径 */
    private static boolean copyAsset(Context c, String assetPath, File target) {
        try (InputStream in = c.getAssets().open(assetPath);
             OutputStream out = new FileOutputStream(target)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** SenseVoice 模型是否就绪：大小达标（防止下载中断/损坏的截断文件）+ 非文本文件头 */
    public static boolean isModelReady(Context c) {
        File m = new File(modelPath(c));
        File t = new File(tokensPath(c));
        if (!m.exists() || m.length() < 150 * 1024 * 1024) return false;   // int8 模型约 228MB
        if (!t.exists() || t.length() < 100) return false;
        return !isTextFile(m);   // onnx 是 protobuf 二进制；若以文本开头（如 HTML 错误页）视为无效
    }

    private static boolean isTextFile(File f) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] head = new byte[8];
            int n = in.read(head);
            for (int i = 0; i < n; i++) {
                if (head[i] == '<' || head[i] == '{') return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** silero VAD 模型：从 assets 复制到应用私有目录（供 native 按路径加载） */
    public static File vadModelFile(Context c) {
        File dir = new File(c.getFilesDir(), "models");
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, VAD_FILE);
        if (!f.exists()) {
            try (InputStream in = c.getAssets().open("models/" + VAD_FILE);
                 OutputStream out = new FileOutputStream(f)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } catch (Exception ignored) {}
        }
        return f;
    }

    /** 下载单个文件到目标路径（阻塞调用，请在后台线程执行）。
     *  支持断点续传：.part 临时文件保留已下载部分，失败后下次从断点继续；
     *  支持重定向（递归跟随，保留 Range）。返回是否成功。 */
    public static boolean downloadFile(String urlStr, File target) {
        File part = new File(target.getParentFile(), target.getName() + ".part");
        HttpURLConnection conn = null;
        try {
            long existing = part.exists() ? part.length() : 0;
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            if (existing > 0) conn.setRequestProperty("Range", "bytes=" + existing + "-");
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc != null) return downloadFile(loc, target);   // 跟随重定向（继续带 Range）
                return false;
            }
            boolean append = (code == 206);   // 206 = 断点续传成功
            if (code != 200 && code != 206) return false;
            try (InputStream in = conn.getInputStream();
                 OutputStream out = new FileOutputStream(part, append)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return part.renameTo(target);
        } catch (Exception e) {
            return false;   // 保留 .part，下次重试从断点继续
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 下载 SenseVoice 模型（model + tokens），阻塞调用，后台线程执行。
     *  每个文件最多自动重试 3 次；中途失败保留 .part 断点，重试时续传，不浪费已下载部分。 */
    public static boolean downloadModel(Context c) {
        File dir = sensevoiceDir(c);
        if (!dir.exists()) dir.mkdirs();
        File mf = new File(dir, MODEL_FILE);
        File tf = new File(dir, TOKENS_FILE);
        boolean ok1 = false, ok2 = false;
        for (int i = 0; i < 3 && !ok1; i++) ok1 = downloadFile(MODEL_URL, mf);
        if (ok1) { for (int i = 0; i < 3 && !ok2; i++) ok2 = downloadFile(TOKENS_URL, tf); }
        if (!ok1 || !ok2) {
            // 下载未完成：删除半成品正式文件（避免加载坏模型），保留 .part 供下次续传
            mf.delete();
            tf.delete();
            return false;
        }
        return isModelReady(c);
    }
}
