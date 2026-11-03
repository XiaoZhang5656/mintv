package com.mintv.tv.ui.key;

import android.view.KeyEvent;

/**
 * 节目单抽屉层。
 *
 * <p>属于"面板之上、设置之下"的一层。开着时：
 * <ul>
 *   <li>上下选节目、OK 播放、左右关闭；</li>
 *   <li>BACK 关闭；</li>
 *   <li><b>数字键不换台</b> —— 以前数字键判断在节目单之前，
 *       用户在节目单里按个 5 就被踢去别的台了；</li>
 *   <li>全部吞掉，不许漏到主界面。</li>
 * </ul>
 */
public final class EpgLayer implements KeyLayer {

    public interface Host {
        boolean isEpgOpen();
        /** 上下/OK 等节目单内部导航。 */
        boolean handleEpgKey(KeyEvent event);
        void closeEpg();
    }

    private final Host host;

    public EpgLayer(Host host) {
        this.host = host;
    }

    @Override public String name() { return "Epg"; }

    @Override public boolean active() { return host.isEpgOpen(); }

    @Override public boolean key(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            host.closeEpg();
            return true;
        }
        host.handleEpgKey(event);
        // 无论内部是否"处理"，节目单开着时都算消费掉 ——
        // 节目单是模态的，不能有按键穿透下去。
        return true;
    }

    @Override public boolean consume() { return true; }

    @Override public boolean longPress(KeyEvent event) { return true; }

    @Override public boolean keyUp(KeyEvent event) { return true; }
}
