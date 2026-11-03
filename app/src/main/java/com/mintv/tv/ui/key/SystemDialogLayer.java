package com.mintv.tv.ui.key;

import android.view.KeyEvent;

/**
 * 系统弹窗层（AlertDialog）。
 *
 * <p>系统弹窗是**独立窗口**，自己管理焦点。这条层存在的唯一目的就是
 * "全部放行"：让 Activity 不拦按键，事件才能派发到弹窗上。
 *
 * <p>以前没有这一层，Activity.onKeyDown 先一步吃掉方向键，
 * 结果四个 AlertDialog 的按钮遥控器一个都选不到。
 */
public final class SystemDialogLayer implements KeyLayer {

    /** 宿主：查询系统弹窗是否开着。 */
    public interface Host {
        boolean isSystemDialogShowing();
    }

    private final Host host;

    public SystemDialogLayer(Host host) {
        this.host = host;
    }

    @Override public String name() { return "SysDialog"; }

    @Override public boolean active() { return host.isSystemDialogShowing(); }

    /** 返回 false 让路由继续，最终落到 Activity 的 super，事件交给系统。 */
    @Override public boolean key(KeyEvent event) { return false; }

    /** 不吞：必须让事件漏到 Activity 默认处理，否则弹窗收不到。 */
    @Override public boolean consume() { return false; }

    @Override public boolean longPress(KeyEvent event) { return false; }

    @Override public boolean keyUp(KeyEvent event) { return false; }
}
