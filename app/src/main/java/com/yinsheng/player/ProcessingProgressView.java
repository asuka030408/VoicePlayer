package com.yinsheng.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.Set;

/**
 * 字幕预处理进度条（自定义 View）：
 *  - 整条 = 音频总时长；
 *  - 浅色段 = 已完成字幕处理的时间段（可回看带字幕）；
 *      紫色 = 离线 ASR 生成，蓝色 = 联网 ASR 生成（方便分辨精度来源）；
 *  - 亮色段 = 正在处理的分片（宽度随分片内进度增长）；
 *  - 深灰段 = 已处理但无语音（ASMR 静音段）；
 *  - 其余深色 = 尚未处理（拖动到这些区间无 AI 字幕）。
 */
public class ProcessingProgressView extends View {

    private final Paint bgPaint = new Paint();
    private final Paint donePaint = new Paint();
    private final Paint cloudPaint = new Paint();
    private final Paint noSpeechPaint = new Paint();
    private final Paint currentPaint = new Paint();

    private long durationMs = 0;
    private int totalSegs = 0;
    private Set<Integer> processed = new java.util.HashSet<>();
    private Set<Integer> noSpeechSegs = new java.util.HashSet<>();
    private Set<Integer> cloudSegs = new java.util.HashSet<>();
    private int currentSeg = -1;
    private int currentProgress = 0;

    public ProcessingProgressView(Context c, AttributeSet a) {
        super(c, a);
        bgPaint.setColor(0xFF241F38);
        donePaint.setColor(0xFF8B5CF6);   // 离线：紫
        cloudPaint.setColor(0xFF3B82F6);  // 联网：蓝
        noSpeechPaint.setColor(0xFF4A4468);
        currentPaint.setColor(0xFFB79CFF);
    }

    public void setData(long durationMs, int totalSegs, Set<Integer> processed,
                        Set<Integer> noSpeechSegs, Set<Integer> cloudSegs, int currentSeg, int currentProgress) {
        this.durationMs = durationMs;
        this.totalSegs = totalSegs;
        this.processed = processed == null ? new java.util.HashSet<>() : processed;
        this.noSpeechSegs = noSpeechSegs == null ? new java.util.HashSet<>() : noSpeechSegs;
        this.cloudSegs = cloudSegs == null ? new java.util.HashSet<>() : cloudSegs;
        this.currentSeg = currentSeg;
        this.currentProgress = currentProgress;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0 || durationMs <= 0 || totalSegs <= 0) return;

        RectF bg = new RectF(0, h * 0.2f, w, h * 0.8f);
        canvas.drawRoundRect(bg, h * 0.3f, h * 0.3f, bgPaint);

        long segMs = durationMs / totalSegs;
        for (int i = 0; i < totalSegs; i++) {
            float x0 = w * (i * segMs) / durationMs;
            float x1 = w * ((i + 1) * segMs) / durationMs;
            if (processed.contains(i)) {
                RectF r = new RectF(x0 + 1, bg.top, x1 - 1, bg.bottom);
                Paint p = noSpeechSegs.contains(i) ? noSpeechPaint
                        : (cloudSegs.contains(i) ? cloudPaint : donePaint);
                canvas.drawRoundRect(r, h * 0.3f, h * 0.3f, p);
            }
        }
        if (currentSeg >= 0 && currentSeg < totalSegs && currentProgress > 0) {
            float x0 = w * (currentSeg * segMs) / durationMs;
            float x1 = x0 + (w * segMs / durationMs) * Math.min(100, currentProgress) / 100f;
            RectF r = new RectF(x0 + 1, bg.top, x1 - 1, bg.bottom);
            canvas.drawRoundRect(r, h * 0.3f, h * 0.3f, currentPaint);
        }
    }
}
