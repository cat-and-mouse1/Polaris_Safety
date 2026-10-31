package com.polaris.app.util;

import android.animation.ValueAnimator;
import android.view.animation.LinearInterpolator;

import com.polaris.app.view.ConcentricRingsView;

/**
 * 动画测试模式的固定时长进度动画（MainActivity 拆分第四刀）。
 *
 * 开关来自「设置 → 应用信息 → 连点版本号 5 次」：开启后扫描进度环无视实际扫描速度，
 * 按固定 3200ms 完整播放一遍（三环接力各 1/3），finishCombinedScan 会等它播完再出结果。
 * 纯动画封装，无业务状态。
 */
public final class AnimTestProgress {

    public static final long DURATION_MS = 3200L;

    private ValueAnimator animator;
    private long startMs = 0;

    /** 启动一次完整播放；已在播放中则忽略（组合扫描的文件段/应用段不会重复触发）。 */
    public void start(ConcentricRingsView rings) {
        if (animator != null) return;
        startMs = android.os.SystemClock.uptimeMillis();
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(DURATION_MS);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> rings.setProgress((float) a.getAnimatedValue()));
        animator.start();
    }

    /** 距离播完还剩多少毫秒（已播完返回 0）。 */
    public long remainingMs() {
        long used = android.os.SystemClock.uptimeMillis() - startMs;
        return Math.max(0L, DURATION_MS - used);
    }

    public void cancel() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    public boolean running() {
        return animator != null;
    }
}
