package com.yinsheng.player;

import android.content.Context;
import android.util.Log;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig;
import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.SpeechSegment;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * ASR 语音识别引擎：sherpa-onnx 本地推理（离线，不上传任何音频）。
 *
 * 支持两种引擎（设置页可切换，独立版本对比用）：
 *  - "sensevoice"：SenseVoice-small-int8（默认，速度快，中文好）；
 *  - "whisper"：Whisper-small（抗噪/快节奏大伴奏场景更稳，速度较慢）。
 *
 * 特性：
 *  - 内置 silero VAD 静音检测：先切出有效语音段，只对语音段做识别，
 *    遇到 ASMR 无语音片段自动跳过，显著减少算力消耗；
 *  - 全流程纯本地，音频绝不上传网络；
 *  - 所有方法为阻塞调用，必须放到独立后台线程执行（见 SubtitlePipeline）。
 */
public class SpeechEngine {

    private static final String TAG = "SpeechEngine";
    private static final int SAMPLE_RATE = 16000;

    /** 识别出的一段语音 */
    public static class SpeechSeg {
        public long startMs;      // 相对整个音频的时间轴
        public long endMs;
        public String text;
    }

    /** 一次识别的完整结果 */
    public static class AsrResult {
        public final List<SpeechSeg> segments = new ArrayList<>();
        public boolean hasSpeech;
    }

    private final OfflineRecognizer recognizer;
    private final Vad vad;

    /**
     * 初始化引擎。模型缺失时抛异常，调用方需先确认 ModelManager.isModelReady()/isWhisperReady()。
     *
     * @param engineType "sensevoice" | "whisper"
     * @param modelFiles sensevoice: [modelPath, tokensPath]；whisper: [encoderPath, decoderPath, tokensPath]
     */
    public SpeechEngine(Context ctx, String engineType, String... modelFiles) throws IOException {
        // 防御：模型从文件系统加载，必须先确认文件真实存在，
        // 否则 sherpa-onnx native 层会因读文件失败直接 abort 整个进程（闪退）
        if (modelFiles == null || modelFiles.length < 2) throw new IOException("模型参数缺失");
        boolean whisper = "whisper".equalsIgnoreCase(engineType);
        File enc = new File(modelFiles[0]);
        File tok = new File(modelFiles[whisper ? 2 : 1]);
        if (!enc.exists() || enc.length() < (whisper ? 100 * 1024 * 1024 : 150 * 1024 * 1024)) {
            throw new IOException((whisper ? "Whisper" : "SenseVoice") + " 模型缺失或不完整: " + modelFiles[0]);
        }
        if (whisper) {
            File dec = new File(modelFiles[1]);
            if (!dec.exists() || dec.length() < 240 * 1024 * 1024) {
                throw new IOException("Whisper decoder 缺失: " + modelFiles[1]);
            }
        }
        if (!tok.exists() || tok.length() < 100) {
            throw new IOException("tokens 缺失: " + tok.getAbsolutePath());
        }

        FeatureConfig featureConfig = new FeatureConfig();
        featureConfig.setSampleRate(SAMPLE_RATE);
        featureConfig.setFeatureDim(80);

        // VAD（silero，2.2MB）：打包在 APK assets 中，用 AssetManager 从 assets 相对路径加载。
        // 注意：绝不能用 AssetManager + 绝对路径，否则 native 读文件失败会 fatal abort。
        SileroVadModelConfig silero = new SileroVadModelConfig();
        silero.setModel("models/silero_vad.onnx");   // assets 相对路径
        silero.setThreshold(0.5f);
        silero.setMinSilenceDuration(0.25f);
        silero.setMinSpeechDuration(0.25f);
        silero.setWindowSize(512);
        silero.setMaxSpeechDuration(20f);

        VadModelConfig vadConfig = new VadModelConfig();
        vadConfig.setSileroVadModelConfig(silero);
        vadConfig.setSampleRate(SAMPLE_RATE);
        vadConfig.setNumThreads(1);
        vadConfig.setDebug(false);
        vad = new Vad(ctx.getAssets(), vadConfig);

        OfflineModelConfig modelConfig = new OfflineModelConfig();
        if (whisper) {
            OfflineWhisperModelConfig w = new OfflineWhisperModelConfig();
            w.setEncoder(modelFiles[0]);   // 文件系统绝对路径
            w.setDecoder(modelFiles[1]);
            w.setLanguage("");   // 空 = 自动语言检测（99 种语言）
            w.setTask("transcribe");
            w.setTailPaddings(-1);
            modelConfig.setWhisper(w);
            modelConfig.setTokens(modelFiles[2]);
            modelConfig.setModelType("whisper");
        } else {
            OfflineSenseVoiceModelConfig senseVoice = new OfflineSenseVoiceModelConfig();
            senseVoice.setModel(modelFiles[0]);   // 文件系统绝对路径
            senseVoice.setLanguage("");   // 空 = 自动语言检测（中/英/日/韩/粤）
            senseVoice.setUseInverseTextNormalization(true);
            modelConfig.setSenseVoice(senseVoice);
            modelConfig.setTokens(modelFiles[1]);
            modelConfig.setModelType("sense_voice");
        }
        modelConfig.setNumThreads(1);   // 单线程推理：降低内存占用，防止低内存设备 OOM 闪退
        modelConfig.setDebug(false);

        OfflineRecognizerConfig recognizerConfig = new OfflineRecognizerConfig();
        recognizerConfig.setFeatConfig(featureConfig);
        recognizerConfig.setModelConfig(modelConfig);
        recognizerConfig.setDecodingMethod("greedy_search");

        // 关键：模型从文件系统加载，AssetManager 必须传 null
        // （sherpa 官方：load model from SD card 时 assetManager 要置 null，否则 fatal）
        recognizer = new OfflineRecognizer(null, recognizerConfig);
    }

    /**
     * 识别一段 16k mono PCM（阻塞，后台线程调用）。
     *
     * @param pcm16k   16kHz 单声道 float 采样（-1.0~1.0）
     * @param baseMs   该 PCM 对应音频的时间起点（用于拼接分片内时间轴）
     */
    public AsrResult recognize(float[] pcm16k, long baseMs) {
        AsrResult result = new AsrResult();
        if (pcm16k == null || pcm16k.length == 0) throw new IllegalStateException("音频解码失败"); // // <0.5s 视为无语音

        try {
            // 关键：VAD 是长生命周期对象，内部会累计所有喂入过的波形。
            // 若不重置，第 N 个分片识别时 SpeechSegment.getStart() 返回的是
            // "自 VAD 创建以来"的全局样本偏移，再叠加 baseMs 会让时间戳整体
            // 多出 N×切片时长 —— 表现为：分片 1 起字幕时间轴整体后移 60s、
            // 分片 2 后移 120s……（真机已复现：seg_1 起点变成 120s、seg_2 变成 240s）。
            // 每次识别前必须 reset()，让 getStart() 相对"本次 PCM"计算。
            vad.reset();

            // VAD：把长音频切成有效语音段，无语音区直接跳过（ASMR 优化点）
            // 注意：与官方示例一致，按窗口（512 样本）逐段喂入，最后 flush 处理尾部
            final int win = 512;
            int i = 0;
            for (; i + win <= pcm16k.length; i += win) {
                vad.acceptWaveform(java.util.Arrays.copyOfRange(pcm16k, i, i + win));
            }
            if (i < pcm16k.length) {
                vad.acceptWaveform(java.util.Arrays.copyOfRange(pcm16k, i, pcm16k.length));
            }
            vad.flush();
            while (!vad.empty()) {
                SpeechSegment seg = vad.front();
                vad.pop();
                float[] segSamples = seg.getSamples();
                if (segSamples == null || segSamples.length == 0) continue;

                // 关键：sherpa-onnx 的 SpeechSegment.getStart() 返回的是"样本数"（samples），
                // 不是毫秒！必须除以采样率换算成秒，再乘 1000 得毫秒。
                // （源码 voice-activity-detector.cc: start_ = buffer_.Tail() - 2*windowSize - minSpeechSamples，单位样本）
                // 之前误当毫秒用，导致长音频第 2+ 段字幕时间戳被放大 16 倍、永远不显示。
                long segStartMs = baseMs + seg.getStart() * 1000L / SAMPLE_RATE;
                long segEndMs = baseMs + (seg.getStart() + segSamples.length) * 1000L / SAMPLE_RATE;

                OfflineStream stream = recognizer.createStream();
                try {
                stream.acceptWaveform(segSamples, SAMPLE_RATE);
                recognizer.decode(stream);
                OfflineRecognizerResult r = recognizer.getResult(stream);
                String text = r == null || r.getText() == null ? "" : r.getText().trim();

                if (!text.isEmpty()) {
                    result.hasSpeech = true;
                    SpeechSeg s = new SpeechSeg();
                    s.startMs = segStartMs;
                    s.endMs = segEndMs;
                    s.text = text;
                    result.segments.add(s);
                }
                } finally { stream.release(); }
            }
        } catch (Throwable t) {
            throw new IllegalStateException("语音识别失败，请重试", t);
        }
        return result;
    }

    public void release() {
        try { if (recognizer != null) recognizer.release(); } catch (Exception ignored) {}
        try { if (vad != null) vad.release(); } catch (Exception ignored) {}
    }
}
