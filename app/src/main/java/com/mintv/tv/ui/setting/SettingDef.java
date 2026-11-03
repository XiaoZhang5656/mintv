package com.mintv.tv.ui.setting;

/**
 * 一个设置行的完整定义。
 *
 * <p>以前一行设置要同时维护四个地方：ROW_TITLES、ROW_OPTIONS、ROW_DEFAULT、
 * 以及 MainActivity 里的 ROW_XXX 常量。删一行就全错位 —— 上一轮删「检查更新」
 * 时行号整体前移，三张表长度一度不一致，差点整页崩。
 *
 * <p>现在一行就是一个对象，标题/说明/选项/默认值放在一起，
 * 不可能出现"改了这个忘了那个"。
 */
public final class SettingDef {

    /** 这一行是"多选一的配置值"还是"一个按钮"。 */
    public enum Kind {
        /** 左右键在 options 里选，选中立即生效。 */
        VALUE,
        /** 只有一个动作，按 OK 触发（左右键不会扫出一堆弹窗）。 */
        ACTION
    }

    /** 稳定标识，用于持久化和回调分发。**不要复用**，删掉的就空着。 */
    public final String id;
    /** 显示标题。 */
    public final String title;
    /** 副标题说明，可为空。 */
    public final String hint;
    /** 类型。 */
    public final Kind kind;
    /** 选项文案。ACTION 类型可以是单元素或空。 */
    public final String[] options;
    /** 默认选中下标。 */
    public final int def;

    private SettingDef(String id, String title, String hint, Kind kind,
                       String[] options, int def) {
        this.id = id;
        this.title = title;
        this.hint = hint;
        this.kind = kind;
        this.options = options == null ? new String[0] : options;
        this.def = def;
        // 构造期就把非法状态挡住，而不是等运行时数组越界
        if (this.options.length == 0 && kind == Kind.VALUE) {
            throw new IllegalArgumentException("VALUE 类型必须有选项: " + id);
        }
        if (def < 0 || (this.options.length > 0 && def >= this.options.length)) {
            throw new IllegalArgumentException("默认值越界: " + id + " def=" + def);
        }
    }

    /** 便捷构造：值行。 */
    public static SettingDef value(String id, String title, String hint,
                                   String[] options, int def) {
        return new SettingDef(id, title, hint, Kind.VALUE, options, def);
    }

    /** 便捷构造：动作行（按钮）。 */
    public static SettingDef action(String id, String title, String hint, String label) {
        return new SettingDef(id, title, hint, Kind.ACTION,
                label == null ? new String[0] : new String[]{label}, 0);
    }

    public boolean isValue() { return kind == Kind.VALUE; }

    public int optionCount() { return options.length; }
}
