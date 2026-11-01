package com.mintv.tv.ui.focus;

/**
 * 焦点引擎。
 *
 * 为什么不用 android:nextFocusDown 那套 XML：
 *   两栏网格里方向键跨列时的落点由系统按几何位置猜，猜错率极高；
 *   而且每次焦点变化都会触发一次完整的 findNextFocus 遍历。
 *
 * 这里把焦点建模成「区域 + 索引」，按键只做算术，一次 requestFocus 就完事。
 * 全程在主线程、无分配，低端盒子上每次按键盘 < 0.1ms。
 */
public final class FocusEngine {

    public enum Zone { NAV, CHAN, SETTINGS_ROW }

    /** 宿主回调：索引变了，把焦点和滚动同步到 View 上。 */
    public interface Host {
        void onNavFocusChanged(int index);
        void onChannelFocusChanged(int index);
        void onSettingRowChanged(int rowIndex, int optIndex);
        void onZoneChanged(Zone zone);
    }

    private Host host;

    private Zone zone = Zone.CHAN;

    private int navCount = 0, navIndex = 1;      // 默认停在「省内」
    private int chanCount = 0, chanIndex = 0;
    private int rowCount = 0, rowIndex = 0;
    /** 每行选项个数，进设置页时由 SettingRowView 填入 */
    private int[] rowOptCounts = new int[0];
    private int optIndex = 0;

    private boolean settingsMode = false;

    public void attach(Host h) { this.host = h; }

    public void setNavCount(int n) {
        navCount = n;
        if (navIndex >= n) navIndex = Math.max(0, n - 1);
    }

    public void setChannelCount(int n) {
        chanCount = n;
        chanIndex = 0;
    }

    public void enterSettings(int[] optCounts) {
        settingsMode = true;
        rowOptCounts = optCounts;
        rowCount = optCounts.length;
        rowIndex = 0;
        optIndex = 0;
        zone = Zone.SETTINGS_ROW;
        if (host != null) {
            host.onZoneChanged(zone);
            host.onSettingRowChanged(rowIndex, optIndex);
        }
    }

    public void exitSettings() {
        settingsMode = false;
        zone = Zone.CHAN;
        if (host != null) {
            host.onZoneChanged(zone);
            host.onChannelFocusChanged(chanIndex);
        }
    }

    public Zone zone() { return zone; }

    /** 外部直接指定焦点区（触屏用）。 */
    public void setZone(Zone z) {
        if (z == zone) return;
        zone = z;
        if (host != null) host.onZoneChanged(zone);
    }
    public boolean inSettings() { return settingsMode; }
    public int navIndex() { return navIndex; }

    /** 只改索引，不回调（分类切换时用，避免多余的焦点刷新）。 */
    public void setNavIndexSilently(int i) {
        if (i >= 0 && i < navCount) navIndex = i;
    }
    public int chanIndex() { return chanIndex; }
    public int rowIndex() { return rowIndex; }
    public int optIndex() { return optIndex; }

    /** 返回 true 表示这次按键被消费。 */
    public boolean onKey(int keyCode) {
        switch (keyCode) {
            case android.view.KeyEvent.KEYCODE_DPAD_UP:
                return up(-1);
            case android.view.KeyEvent.KEYCODE_DPAD_DOWN:
                return up(1);
            case android.view.KeyEvent.KEYCODE_DPAD_LEFT:
                return left();
            case android.view.KeyEvent.KEYCODE_DPAD_RIGHT:
                return right();
            default:
                return false;
        }
    }

    private boolean up(int d) {
        if (settingsMode) {
            if (rowCount == 0) return true;
            rowIndex = (rowIndex + d + rowCount) % rowCount;
            optIndex = clamp(optIndex, 0, Math.max(0, rowOptCounts[rowIndex] - 1));
            if (host != null) host.onSettingRowChanged(rowIndex, optIndex);
            return true;
        }
        if (zone == Zone.NAV) {
            if (navCount == 0) return true;
            int n = clamp(navIndex + d, 0, navCount - 1);
            if (n != navIndex) {
                navIndex = n;
                if (host != null) host.onNavFocusChanged(navIndex);
            }
            return true;
        }
        if (chanCount == 0) return true;
        int n = clamp(chanIndex + d, 0, chanCount - 1);
        if (n != chanIndex) {
            chanIndex = n;
            if (host != null) host.onChannelFocusChanged(chanIndex);
        }
        return true;
    }

    private boolean left() {
        if (settingsMode) {
            if (optIndex > 0) {
                optIndex--;
                if (host != null) host.onSettingRowChanged(rowIndex, optIndex);
            } else {
                // 行内最左再按一次 → 留给 Activity 处理（切页签 / 关闭）
                return false;
            }
            return true;
        }
        if (zone == Zone.CHAN) {
            zone = Zone.NAV;
            if (host != null) {
                host.onZoneChanged(zone);
                host.onNavFocusChanged(navIndex);
            }
            return true;
        }
        return false;   // 导航列最左：留给 Activity（唤出更多菜单）
    }

    private boolean right() {
        if (settingsMode) {
            int max = Math.max(0, rowOptCounts[rowIndex] - 1);
            if (optIndex < max) {
                optIndex++;
                if (host != null) host.onSettingRowChanged(rowIndex, optIndex);
            }
            return true;
        }
        if (zone == Zone.NAV) {
            zone = Zone.CHAN;
            if (host != null) {
                host.onZoneChanged(zone);
                host.onChannelFocusChanged(chanIndex);
            }
            return true;
        }
        return false;   // 频道列最右：留给 Activity（出 EPG / 时移）
    }

    // ---- 分类切换后频道列重置 ----
    public void resetChannelFocus() {
        chanIndex = 0;
        if (host != null) host.onChannelFocusChanged(0);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
