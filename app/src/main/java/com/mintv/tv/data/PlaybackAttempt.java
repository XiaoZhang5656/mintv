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
        /**
         * 只有一条线路且超时：重连同一条。
         *
         * <p>以前单源超时直接 GIVE_UP，用户看到「该源无响应」之后
         * 永久黑屏，只能手动切台。而绝大多数"无响应"是临时的
         * （服务端重启、切片还没生成），重连几次往往就好了。
         */
        RECONNECT,
        /** 没牌可打了，明确告知用户。 */
        GIVE_UP
    }

    private final int baseDecoder;
    private long timeoutMs;
    private final int sourceCount;
    private final boolean autoSwitch;

    /** 已出首帧。 */
    private boolean firstFrame;
    /** 单源重连次数，用于退避间隔。 */
    private int reconnectCount;
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

    /** 用户按了暂停。暂停时位置本来就不动，不能算卡住。 */
    private boolean paused;

    /**
     * 实时倍率低于这个值就认为「跟不上」。
     *
     * <p>0.9 意味着每 10 分钟落后 1 分钟 —— 这是用户能察觉的边界。
     * 定 0.8 太宽松了：那时每 5 分钟就落后 1 分钟，看半小时能差 6 分钟，
     * 但程序还觉得"正常"。
     */
    private static final float MIN_SPEED = 0.90f;

    /** 低速开始的时间戳，-1 表示当前速度正常。 */
    private long lowSpeedSince = -1;
    /** 低速已经报过一次（单线路时防止每 8 秒刷一次提示）。 */
    private boolean lowSpeedReported;

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

    /**
     * 同步暂停状态。
     *
     * <p>⚠ 暂停时 {@code positionMs()} 是不动的 —— 如果还照常做卡顿检测，
     * 用户暂停十几秒就会看到「播放卡住，切换线路」。这不是卡住，是用户
     * 自己按的。所以暂停期间必须整段跳过检测。
     *
     * <p>恢复播放时清空采样点：否则「暂停前的最后时间戳」还留着，
     * 一恢复就立刻满足 {@code now - stallSince >= timeout}，直接误判。
     */
    public void setPaused(boolean p) {
        if (paused == p) return;
        paused = p;
        stallLastPos = -1;
        stallSince = -1;
    }

    public boolean isPaused() { return paused; }

    /** 出首帧。 */
    public void onFirstFrame() {
        // 出画面说明这次尝试真的成功了，重连计数归零。
        // 不归零的话：看了一小时源断了，第一次重连直接按第 10 次
        // 算，等 30 秒才动 —— 用户以为卡死了。
        reconnectCount = 0;
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
        return sourceCount > 1 ? Action.SWITCH_SOURCE : Action.RECONNECT;
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
        // 暂停中不判卡顿（位置本来就不动）；没出帧时不判（起播交给看门狗）
        if (paused || !autoSwitch || !firstFrame || timeoutMs <= 0) return Action.NONE;
        // 位置拿不到有效值（某些直播源 getTime() 恒为 0）：判不了，别乱判。
        // 没有这个保护，恒 0 的源会一直"卡住"，用户看到画面在动却被踢去换线路。
        if (pos <= 0) return Action.NONE;

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
        return sourceCount > 1 ? Action.SWITCH_SOURCE : Action.RECONNECT;
    }

    /**
     * 重开一次播放（换线路 / 自愈重播）。
     *
     * <p>只清「卡顿采样」和「是否出过帧」，**不清降级进度** ——
     * 否则换线路之后又会从"软解"重来一遍，来回横跳。
     */
    /**
     * 重连前清掉「已经尝试过」的标记。
     *
     * <p>不清的话：第一次超时 → startupFailed=true → RECONNECT →
     * 第二次超时被这个标记挡住，返回 NONE，永远不动作，卡死黑屏。
     * 降级进度（软解、换线路）不清 —— 那些是本次尝试的成果，重连后仍然有效。
     */
    public void resetForReconnect() {
        startupFailed = false;
        errorRetried = false;
        stallLastPos = -1;
        stallSince = -1;
        lowSpeedSince = -1;
    }

    /**
     * 重连间隔 = 用户在「超时换源」里设的秒数。
     *
     * <p>不做指数退避：用户设了 8 秒，就是每 8 秒重连一次。
     * 退避会让间隔一路涨到 30 秒 —— 用户设了个数，不该被程序
     * 偷偷改掉。想让它等更久，去改设置。
     */
    public long nextReconnectDelayMs() {
        reconnectCount++;
        return timeoutMs > 0 ? timeoutMs : 8000;
    }

    public int reconnectCount() { return reconnectCount; }

    public void onRestart() {
        firstFrame = false;
        paused = false;
        lowSpeedSince = -1;
        lowSpeedReported = false;
        stallLastPos = -1;
        stallSince = -1;
    }

    /**
     * 实时倍率（Δ播放时间 / Δ墙钟时间）。
     *
     * <p>位置判据只能抓"完全冻住"，抓不到"还在播但越来越慢"：
     * 播放位置一直在涨，只是涨得比墙钟慢，8 秒不动那条规则永远不触发。
     * 用户看到的是画面越来越落后于直播，而程序认为一切正常。
     *
     * @param speed 1.0 = 实时
     * @param now   单调时钟
     */
    public Action onSpeed(float speed, long now) {
        if (paused || !autoSwitch || !firstFrame) return Action.NONE;

        if (speed >= MIN_SPEED) {
            lowSpeedSince = -1;
            lowSpeedReported = false;
            return Action.NONE;
        }
        if (lowSpeedSince < 0) {
            lowSpeedSince = now;
            return Action.NONE;
        }
        // 用用户设置里的「超时换源」秒数，和位置判据保持一致。
        // 原来这里写死 8000，导致设置成 5 秒/12 秒/20 秒都不生效。
        if (now - lowSpeedSince < timeoutMs) return Action.NONE;

        // 持续低速：该换线路了
        lowSpeedSince = -1;
        if (sourceCount > 1) return Action.SWITCH_SOURCE;
        if (lowSpeedReported) return Action.NONE;
        lowSpeedReported = true;
        return Action.GIVE_UP;
    }

    /**
     * 用户在设置里改了「超时换源」秒数时同步过来。
     *
     * <p>不重建 attempt：那样会丢掉本次的降级进度（已经试过软解、
     * 已经换过几条线路），改个超时值不该让这些白费。
     */
    public void setTimeoutMs(long ms) { this.timeoutMs = ms; }

    /** 解码能力错误。 */
    public Action onErrorRetryable() {
        if (errorRetried) return Action.GIVE_UP;
        errorRetried = true;
        // 单内核下没有"换内核"可走，交回给调用方当普通错误处理
        return Action.NONE;
    }
}
