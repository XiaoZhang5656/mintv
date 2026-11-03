package com.mintv.tv.ui.key;

import android.view.KeyEvent;

/**
 * 纯播放层：面板收起，只剩画面。
 *
 * <p>职责：
 * <ul>
 *   <li>上下 → 唤回面板；</li>
 *   <li>左右 → 快进/快退（长按加速）；</li>
 *   <li>BACK → 先唤回面板，再按才退出。以前无条件 return true，
 *       导致纯播放态按 BACK 直接 finish()，看片时误按就退出；</li>
 *   <li>数字 / 上一频道 → 换台。</li>
 * </ul>
 */
public final class PlayerLayer implements KeyLayer {

    public interface Host {
        boolean isPanelVisible();
        void showPanel();
        void onSeekStep(int direction);
        boolean startSeek(boolean forward);
        boolean isSeeking();
        void endSeek();
        void appendDigit(int digit);
        void jumpToLastChannel();
        void channelUp();
        void channelDown();
        boolean canSeek();
        void seekStep(int direction);
        boolean isOsdVisible();
        void hideOsd();
        void reshowOsd();
    }

    private final Host host;

    /**
     * 换台节流。
     *
     * <p>按住上下键时，系统每 50ms 左右发一次带递增 repeatCount 的
     * ACTION_DOWN。之前没有任何判断，按下去等于连续切二三十个台 ——
     * 每切一次都要重新拉流，直接雪崩：画面疯狂闪、声音断续、看门狗
     * 连番触发。电视遥控的 CH+/- 应该大约 5 台/秒，不是每帧一个。
     */
    private static final long SWITCH_MIN_GAP_MS = 200;
    private long lastSwitchAt;

    public PlayerLayer(Host host) {
        this.host = host;
    }

    @Override public String name() { return "Player"; }

    @Override public boolean active() { return !host.isPanelVisible(); }

    @Override public boolean key(KeyEvent event) {
        int code = event.getKeyCode();

        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                // 纯播放态直接切台，不弹面板。
                //
                // 原来这里是 showPanel()：按上下只把左侧面板叫出来，
                // 画面一动不动，也不换台 —— 手感就是"按了没反应"，
                // 想看下一个台要按三次（弹面板→移到列表→再按）。
                //
                // 电视的通用交互是上下键=上一个/下一个频道。面板入口
                // 仍然有：OK 键 / MENU 键 / BACK 键都能唤回。
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastSwitchAt < SWITCH_MIN_GAP_MS) {
                    // 长按产生的重复事件：吃掉，但不切台
                    return true;
                }
                lastSwitchAt = now;
                if (code == KeyEvent.KEYCODE_DPAD_UP) host.channelUp();
                else host.channelDown();
                return true;
            }

            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (event.getRepeatCount() == 0) {
                    event.startTracking();      // 交给 longPress 判定
                } else if (!host.isSeeking()) {
                    host.seekStep(code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                }
                return true;

            case KeyEvent.KEYCODE_BACK:
                // 先收 OSD，再唤回面板；两者都没有时才交给 Activity 退出
                if (host.isOsdVisible()) { host.hideOsd(); return true; }
                host.showPanel();
                return true;

            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                // 纯播放态按 OK：唤回面板。
                // 以前没处理，事件一路漏到 Activity 的默认行为，
                // 表现就是"黑屏时按确认没有任何反应"。
                host.showPanel();
                return true;

            default:
                if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
                    host.appendDigit(code - KeyEvent.KEYCODE_0);
                    return true;
                }
                if (code == KeyEvent.KEYCODE_LAST_CHANNEL
                        || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
                    host.jumpToLastChannel();
                    return true;
                }
                // 遥控器上的 CH+ / CH-（部分遥控器有独立按键）
                if (code == KeyEvent.KEYCODE_CHANNEL_UP) { host.channelUp(); return true; }
                if (code == KeyEvent.KEYCODE_CHANNEL_DOWN) { host.channelDown(); return true; }
                return false;
        }
    }

    /** 不吞：纯播放态处理不了的键（媒体键等）应交给 Activity。 */
    @Override public boolean consume() { return false; }

    @Override public boolean longPress(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT) return host.startSeek(true);
        if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT) return host.startSeek(false);
        return false;
    }

    @Override public boolean keyUp(KeyEvent event) {
        if (host.isSeeking() && (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT
                || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT)) {
            host.endSeek();
            return true;
        }
        return false;
    }
}
