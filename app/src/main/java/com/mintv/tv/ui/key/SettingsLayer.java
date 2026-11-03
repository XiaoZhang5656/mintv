package com.mintv.tv.ui.key;

import android.view.KeyEvent;

/**
 * 设置弹窗层。
 *
 * <p>这一层**必须吞掉所有按键**。以前 handleSettingsKey 最后是
 * {@code return handled || true}，看起来是吞了，但中间有几处
 * {@code return false}，事件就漏到了主界面 —— 用户在「关于」页按方向键，
 * 隐藏的设置列表焦点却被改了，切回去发现焦点莫名跳了。
 *
 * <p>现在把"处理"和"吞"分开：key() 只管能不能处理，consume() 恒为 true。
 */
public final class SettingsLayer implements KeyLayer {

    /** 宿主：设置界面自己的按键逻辑。 */
    public interface Host {
        boolean isSettingsVisible();
        /** 处理一个按键，返回是否已消费。 */
        boolean handleSettingsKey(KeyEvent event);
    }

    private final Host host;

    public SettingsLayer(Host host) {
        this.host = host;
    }

    @Override public String name() { return "Settings"; }

    @Override public boolean active() { return host.isSettingsVisible(); }

    @Override public boolean key(KeyEvent event) { return host.handleSettingsKey(event); }

    /** 恒吞：设置开着时任何按键都不许漏到主界面。 */
    @Override public boolean consume() { return true; }

    /** 长按交给自己处理（设置里没有长按语义，直接吃掉）。 */
    @Override public boolean longPress(KeyEvent event) { return true; }

    @Override public boolean keyUp(KeyEvent event) { return true; }
}
