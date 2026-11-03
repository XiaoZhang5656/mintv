package com.mintv.tv.ui.key;

import android.view.KeyEvent;

/**
 * 主界面层：三栏（分类 / 频道 / 竖轨）。
 *
 * <p>只负责「面板可见」时的事。面板收起时这层不 active，
 * 按键交给 {@link PlayerLayer}，两边物理隔离 ——
 * 以前靠 {@code if (!panelVisible) return true;} 硬拦，
 * 一旦有人把守卫写错，按键就会改到看不见的频道索引上。
 */
public final class PanelLayer implements KeyLayer {

    public interface Host {
        boolean isPanelVisible();
        /** 交给焦点引擎 + OK/BACK 分支。 */
        boolean handlePanelKey(KeyEvent event);
        /** 右键出节目单。 */
        boolean tryOpenEpg();
        void hidePanel();
    }

    private final Host host;

    public PanelLayer(Host host) {
        this.host = host;
    }

    @Override public String name() { return "Panel"; }

    @Override public boolean active() { return host.isPanelVisible(); }

    @Override public boolean key(KeyEvent event) {
        int code = event.getKeyCode();

        // 右键：从频道列出节目单。放在焦点引擎之前，
        // 因为焦点引擎在"频道列最右"时会 return false 表示想出去。
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT && host.tryOpenEpg()) return true;

        if (host.handlePanelKey(event)) return true;

        // BACK 兜底：先收面板，不退出 App
        if (code == KeyEvent.KEYCODE_BACK) {
            host.hidePanel();
            return true;
        }
        // 其它键不吞：交给 Activity 默认行为（媒体键、菜单键等）
        return false;
    }

    /** 不吞：面板层的"没处理"是真空闲，应该让 Activity 兜底。 */
    @Override public boolean consume() { return false; }

    @Override public boolean longPress(KeyEvent event) { return false; }

    @Override public boolean keyUp(KeyEvent event) { return false; }
}
