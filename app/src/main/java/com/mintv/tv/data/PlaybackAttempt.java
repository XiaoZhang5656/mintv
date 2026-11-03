package com.mintv.tv.data;

/**
 * 一次播放尝试的状态机。
 *
 * <p><b>为什么要单独一个类</b>
 *
 * <p>这些状态以前是 MainActivity 里六个散落的字段：
 * {@code playing} / {@code watchdogFired} / {@code retryDone} /
 * {@code noVideoStage} / {@code tempDecoderOverride} /
 * {@code stallSince+stallLastPos}，由 armWatchdog、playbackWatchdog、
 * onNoVideo、checkStall、onFirstFrame、playChannelInternal 六处交叉读写。
 *
 * <p>没有任何一处能完整回答「这次播放进行到哪一步」。漏复位就会出现
 * 上一轮那种 bug：改一次解码方式之后永远播不了（released 不复位）、
 * 切回来没声音（tempDecoderOverride 没清）。同一个病在不同地方各犯一次。
 *
 * <p>这里把它收敛成一个对象：边界清楚、转换集中、不依赖 Android，
 * 可以直接跑单测。
 *
 * <p><b>只回答「下一步做什么」，不执行副作用</b>：换线路、重播、弹提示
 * 都由调用方做。这样这个类的行为可以完全不碰线程和播放器地验证。
 */
public final class PlaybackAttempt {

    /** 下一步动作。 */
    public enum Action {
        /** 什么都不用做。 */
        NONE,
        /** 本次尝试改用软件解码重播同一个地址。 */
        RETRY_SOFT_DECODE,
        /** 切到下一条线路。 */
        SWITCH_SOURCE,
        /** 没牌可打了，明确告知用户。 */
        GIVE_UP
    }

    private final int baseDecoder;
    private final long timeoutMs;
    private final int sourceCount;
    private final boolean autoSwitch;

    /** 已出首帧。 */
    private boolean firstFrame;
    /** 起播看门狗已经触发过 —— 只用来防重复，**不参与卡顿判断**。 */
    private boolean startupFailed;
    /** 解码错误已经处理过一次。 */
    private boolean errorRetried;
    /** 「无画面」的软解重试已经做过。 */
    private boolean noVideoTried;
    /** 「无画面」已经换过一次线路。 */
    private boolean sourceSwitched;
    /** 本次尝试临时覆盖的解码方式，-1 表示没覆盖。 */
    private int decodeOverride = -1;

    private long stallLastPos = -1;
    /** 最后一次「位置变化」的时刻。**-1 表示还没有有效样本**（不能用 0：
     *  uptimeMillis 在开机瞬间确实可能为 0，会和哨兵值撞上）。 */
    private long stallSince = -1;

    /**
     * @param baseDecoder 用户设置的解码方式：0 自动 / 1 内置硬解 / 2 软解
     * @param timeoutMs   超时换源阈值，0 表示用户关闭了
     * @param sourceCount 当前频道有几条线路
     * @param autoSwitch  用户是否开启了超时换源
     */
    public PlaybackAttempt(int baseDecoder, long timeoutMs, int sourceCount, boolean autoSwitch) {
        this.baseDecoder = baseDecoder;
        this.timeoutMs = timeoutMs;
        this.sourceCount = sourceCount;
        this.autoSwitch = autoSwitch;
    }

    // ================= 查询 =================

    /** 是否已经出过画面。 */
    public boolean hasFirstFrame() { return firstFrame; }

    /** 本次尝试真正生效的解码方式（临时覆盖优先于用户设置）。 */
    public int decoderMode() { return decodeOverride >= 0 ? decodeOverride : baseDecoder; }

    /** 是否有临时覆盖（调用方据此决定失败后要不要还原）。 */
    public boolean hasDecoderOverride() { return decodeOverride >= 0; }

    // ================= 转换 =================

    /** 出首帧。 */
    public void onFirstFrame() {
        firstFrame = true;
        // ⚠ 这里**不能**去动"看门狗已触发"那个标志。
        //
        // 原来的代码是 onFirstFrame() { playing = true; watchdogFired = true; }，
        // 而卡顿检测 checkStall 的第一个判断就是
        //     if (!autoSwitchSource() || watchdogFired || !playing) return;
        // —— 于是出帧的同时就把卡顿检测永久关掉了，那个功能从来没生效过。
        // "起播看门狗响过" 和 "播放中是否卡住" 是两件事，必须分开。
        stallLastPos = -1;
        stallSince = -1;
    }

    /**
     * 起播超时（看门狗）：到点还没出画面。
     *
     * <p>调用方只在用户开启超时换源时才 arm 看门狗，所以这里不再判断开关。
     */
    public Action onStartupTimeout() {
        if (firstFrame || startupFailed) return Action.NONE;
        startupFailed = true;
        return sourceCount > 1 ? Action.SWITCH_SOURCE : Action.GIVE_UP;
    }

    /**
     * 明确的「无画面」信号（内核报的，不是超时猜的）。
     *
     * <p>顺序：自动 → 软解；还有别的线路且允许换源 → 换线路；否则放弃。
     * 用户本来就手动选了软解的，不重复软解这一步。
     */
    public Action onNoVideo() {
        if (!noVideoTried && decoderMode() == 0) {
            noVideoTried = true;
            decodeOverride = 2;
            return Action.RETRY_SOFT_DECODE;
        }
        noVideoTried = true;
        if (autoSwitch && sourceCount > 1 && !sourceSwitched) {
            sourceSwitched = true;
            return Action.SWITCH_SOURCE;
        }
        return Action.GIVE_UP;
    }

    /**
     * 播放位置回调。返回 {@link Action#SWITCH_SOURCE} 表示判定为卡住。
     *
     * @param pos 当前播放位置（毫秒）
     * @param now 单调时钟（uptimeMillis）
     */
    public Action onPosition(long pos, long now) {
        if (!autoSwitch || !firstFrame || timeoutMs <= 0) return Action.NONE;

        if (pos != stallLastPos) {
            stallLastPos = pos;
            stallSince = now;
            return Action.NONE;
        }
        if (stallSince < 0) {
            stallSince = now;
            return Action.NONE;
        }
        if (now - stallSince < timeoutMs) return Action.NONE;

        stallSince = now;   // 重置，避免连续触发
        return sourceCount > 1 ? Action.SWITCH_SOURCE : Action.GIVE_UP;
    }

    /**
     * 重开一次播放（换线路 / 自愈重播）。
     *
     * <p>只清「卡顿采样」和「是否出过帧」，**不清降级进度** ——
     * 否则换线路之后又会从"软解"重来一遍，来回横跳。
     */
    public void onRestart() {
        firstFrame = false;
        stallLastPos = -1;
        stallSince = -1;
    }

    /** 解码能力错误。 */
    public Action onErrorRetryable() {
        if (errorRetried) return Action.GIVE_UP;
        errorRetried = true;
        // 单内核下没有"换内核"可走，交回给调用方当普通错误处理
        return Action.NONE;
    }
}
