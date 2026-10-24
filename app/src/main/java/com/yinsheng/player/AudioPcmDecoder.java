package com.yinsheng.player;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * 音频解码器：把任意本地音频（mp3/flac/m4a/wav…）解码为 16kHz 单声道 float 采样，
 * 供 sherpa-onnx ASR 使用。使用系统 MediaExtractor + MediaCodec，无需 FFmpeg。
 *
 * 分片解码：通过 extractor.seekTo() 跳到分片起点，只保留 [startMs, endMs) 区间内的 PCM，
 * 避免一次解码整段长音频占用内存。
 */
public class AudioPcmDecoder {

    /**
     * 解码 [startMs, endMs) 区间的音频为 16k mono float（-1.0 ~ 1.0）。
     * 返回 null 表示失败。
     */
    public static float[] decodeRange(Context ctx, Uri uri, long startMs, long endMs) {
        // content:// 优先走 FileDescriptor（对文件管理器/SAF 的 provider 兼容性更好）
        try (ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd != null) {
                float[] r = decodeFd(pfd, startMs, endMs);
                if (r != null) return r;
            }
        } catch (Exception ignored) {}
        return decodeUri(ctx, uri, startMs, endMs);
    }

    private static float[] decodeFd(ParcelFileDescriptor pfd, long startMs, long endMs) {
        MediaExtractor extractor = null;
        MediaCodec codec = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(pfd.getFileDescriptor());
            return runDecode(extractor, startMs, endMs);
        } catch (Exception e) {
            return null;
        } finally {
            if (codec != null) { try { codec.stop(); } catch (Exception ignored) {} codec.release(); }
            if (extractor != null) extractor.release();
        }
    }

    private static float[] decodeUri(Context ctx, Uri uri, long startMs, long endMs) {
        MediaExtractor extractor = null;
        MediaCodec codec = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(ctx, uri, null);
            return runDecode(extractor, startMs, endMs);
        } catch (Exception e) {
            return null;
        } finally {
            if (codec != null) { try { codec.stop(); } catch (Exception ignored) {} codec.release(); }
            if (extractor != null) extractor.release();
        }
    }

    private static float[] runDecode(MediaExtractor extractor, long startMs, long endMs) throws Exception {
        MediaCodec codec = null;
        try {
            int trackIdx = -1;
            MediaFormat trackFmt = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    trackIdx = i;
                    trackFmt = f;
                    break;
                }
            }
            if (trackIdx < 0) return null;
            extractor.selectTrack(trackIdx);

            int srcSampleRate = trackFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? trackFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int srcChannels = trackFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? trackFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;

            String mime = trackFmt.getString(MediaFormat.KEY_MIME);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(trackFmt, null, null, 0);
            codec.start();

            // seek 到分片起点前一点（seek 到上一个关键帧，由后续时间戳裁剪）
            long seekUs = Math.max(0, startMs * 1000 - 500 * 1000);
            extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

            ByteBuffer[] inBufs = codec.getInputBuffers();
            ByteBuffer[] outBufs = codec.getOutputBuffers();
            ByteArrayOutputStream pcm = new ByteArrayOutputStream();
            boolean inputDone = false, outputDone = false;
            long firstKeepUs = startMs * 1000;
            long endUs = endMs * 1000;
            int timeout = 10000;

            // ---- 分片解码三重保护（防止"整文件解码"卡死/内存暴涨）----
            // 部分文件（尤其 MP3）seek 后 extractor 返回的 sampleTime 会重置/乱序，
            // 输入侧按 sampleTime>=endUs 截断会失效 → 分片解码退化成整文件解码：
            // 长音频每片都从开头解到 EOF，codec 长期占用不释放、PCM 持续累积、
            // worker 全部堵在解码上（真机曾出现一个解码器每 5 秒只消费 2 帧、
            // 73 分钟音频要解 12 小时的卡死，Native 内存涨到 500MB、主线程 ANR）。
            // ① 帧数硬上限：分片应有的输入帧数（按常见 AAC 帧 1024 样本估，余量充足），
            //    达到即喂 EOF——输出侧仍按 presentationTimeUs 过滤，不影响正确性；
            // ② 输出空闲退出：输入已喂完但连续多次取不到输出 → 判定解码器卡住，强制结束；
            // ③ 总时长超时：整个解码超过 3 倍分片时长+30s 强制结束，任何情况下都能退出。
            final int maxFeed = (int) (((endMs - startMs) + 1500) * srcSampleRate / 1000 / 1024) + 32;
            int fed = 0;
            long lastProgress = android.os.SystemClock.elapsedRealtime();
            int pcmEncoding = android.media.AudioFormat.ENCODING_PCM_16BIT;
            final long deadline = android.os.SystemClock.elapsedRealtime() + (endMs - startMs) * 3 + 30000;

            while (!outputDone) {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("解码已取消");
                // 喂输入
                if (!inputDone) {
                    int inIdx = codec.dequeueInputBuffer(timeout);
                    if (inIdx >= 0) {
                        ByteBuffer bb = inBufs[inIdx];
                        long sampleTime = extractor.getSampleTime();
                        int sz = extractor.readSampleData(bb, 0);
                        if (sz < 0 || sampleTime >= endUs) {
                            // 文件读完 / 已到本分片结束时间 / 已达帧数上限：喂 EOF 停止解码。
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sz, sampleTime, 0);
                            extractor.advance();
                            fed++;
                            lastProgress = android.os.SystemClock.elapsedRealtime();
                        }
                    }
                }
                // 取输出
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                int outIdx = codec.dequeueOutputBuffer(info, timeout);
                if (outIdx >= 0) {
                    if (info.size > 0 && info.presentationTimeUs >= firstKeepUs && info.presentationTimeUs < endUs) {
                        ByteBuffer ob = outBufs[outIdx];
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        byte[] chunk = new byte[info.size];
                        ob.get(chunk);
                        pcm.write(chunk);
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    lastProgress = android.os.SystemClock.elapsedRealtime();
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat output = codec.getOutputFormat();
                    srcSampleRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    srcChannels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmEncoding = output.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    if (pcmEncoding != android.media.AudioFormat.ENCODING_PCM_16BIT
                            && pcmEncoding != android.media.AudioFormat.ENCODING_PCM_FLOAT) throw new java.io.IOException("不支持的 PCM 格式");
                    lastProgress = android.os.SystemClock.elapsedRealtime();
                    outBufs = codec.getOutputBuffers();
                } else if (outIdx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    outBufs = codec.getOutputBuffers();
                } else {
                    if (android.os.SystemClock.elapsedRealtime() - lastProgress > 15000)
                        throw new java.io.IOException("音频解码长时间无进展，请重试");
                }
                if (!outputDone && android.os.SystemClock.elapsedRealtime() > deadline)
                    throw new java.io.IOException("音频解码超时，请重试");
            }
            codec.stop();
            codec.release();
            codec = null;

            byte[] raw = pcm.toByteArray();
            if (raw.length < 4) {
                android.util.Log.w("AudioPcmDecoder", "解码结果为空: startMs=" + startMs + " endMs=" + endMs + " 文件时长可能小于分片起点");
                return null;
            }
            android.util.Log.d("AudioPcmDecoder", "解码完成 startMs=" + startMs + " endMs=" + endMs
                    + " 原始字节=" + raw.length + " (目标时长 " + (endMs - startMs) + "ms)");
            // 转 short（16bit LE）
            short[] shorts = new short[raw.length / (pcmEncoding == android.media.AudioFormat.ENCODING_PCM_FLOAT ? 4 : 2)];
            ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            if (pcmEncoding == android.media.AudioFormat.ENCODING_PCM_FLOAT) {
                for (int i = 0; i < shorts.length; i++) shorts[i] = (short)(Math.max(-1f, Math.min(1f, bb.getFloat())) * 32767);
            } else bb.asShortBuffer().get(shorts);
            // 重采样到 16k mono
            return resampleMono16k(shorts, srcSampleRate, srcChannels);
        } finally {
            if (codec != null) { try { codec.stop(); } catch (Exception ignored) {} codec.release(); }
        }
    }

    /** 多声道 → 单声道平均；采样率 → 16k（线性插值） */
    private static float[] resampleMono16k(short[] samples, int srcRate, int channels) {
        if (channels < 1) channels = 1;
        // 先降为单声道
        int frames = samples.length / channels;
        float[] mono = new float[frames];
        for (int i = 0; i < frames; i++) {
            long sum = 0;
            for (int c = 0; c < channels; c++) sum += samples[i * channels + c];
            mono[i] = sum / (float) channels / 32768f;
        }
        if (srcRate == 16000) return mono;
        // 线性插值到 16k
        int outLen = (int) (mono.length * 16000L / srcRate);
        float[] out = new float[outLen];
        for (int i = 0; i < outLen; i++) {
            float pos = i * (float) srcRate / 16000f;
            int i0 = (int) pos;
            int i1 = Math.min(i0 + 1, mono.length - 1);
            float frac = pos - i0;
            out[i] = mono[i0] * (1 - frac) + mono[i1] * frac;
        }
        return out;
    }
}
