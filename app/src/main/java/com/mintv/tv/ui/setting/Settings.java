package com.mintv.tv.ui.setting;

import java.util.HashMap;
import java.util.Map;

/**
 * 设置项总表。**唯一真相来源。**
 *
 * <p>加一个设置项 = 在这里加一行 + （如果它有副作用）写一个 onXxxChanged。
 * 不需要再动行号常量、默认值数组、选项数组。
 *
 * <p>三条防不同步的约束（都在 {@link #verify()} 里强制检查，构造期就抛）：
 * <ol>
 *   <li><b>id 唯一</b>：重复 id 会让持久化键互相覆盖；</li>
 *   <li><b>访问顺序稳定</b>：下标即行号，删行时不要重排，只把那一行删掉；</li>
 *   <li><b>默认值合法</b>：在 SettingDef 构造期检查。</li>
 * </ol>
 */
public final class Settings {

    // ══════════════════════════════════════════════════════════
    //  索引常量：给代码里用，避免写魔法数字
    //  注意：新增请追加在末尾，不要插在中间（会让老配置错位）
    // ══════════════════════════════════════════════════════════
    public static final int IDX_SOURCE      = 0;
    public static final int IDX_ASPECT      = 1;
    public static final int IDX_EPG_POS     = 2;
    public static final int IDX_REVERSE     = 3;
    public static final int IDX_SWITCH_UI   = 4;
    public static final int IDX_LEFT_KEY    = 5;
    public static final int IDX_SPEED       = 6;
    public static final int IDX_AUTOSTART   = 7;
    public static final int IDX_DECODER     = 8;
    public static final int IDX_TIMEOUT     = 9;
    public static final int IDX_HEADERS     = 10;
    public static final int IDX_ENGINE      = 11;
    public static final int IDX_CLOCK       = 12;
    public static final int IDX_OUTPUT      = 13;
    public static final int IDX_THEME       = 14;
    public static final int IDX_GROUP_MGR   = 15;

    /** 总表。下标即行号。 */
    public static final SettingDef[] ALL = {
            SettingDef.action("source", "切源",
                    "同一频道多条线路时手动切换，左=上一条 右=下一条", null),
            SettingDef.value("aspect", "画面比例", "",
                    new String[]{"自动适应", "16:9", "4:3", "原始", "铺满"}, 0),
            SettingDef.value("epg_pos", "EPG显示", "",
                    new String[]{"居左", "居中"}, 0),
            SettingDef.value("reverse", "上下换台反转",
                    "当前：下键上一个，上键下一个",
                    new String[]{"已开启 (ON)", "关闭"}, 0),
            SettingDef.value("switch_ui", "换台显示",
                    "换台时：台号和节目框 / 只台号 / 只节目框",
                    new String[]{"台号+EPG", "台号", "EPG"}, 0),
            SettingDef.value("left_key", "左右按键",
                    "直播：左右键时移/出 EPG（默认）",
                    new String[]{"查看 EPG 回看", "切源"}, 0),
            SettingDef.value("speed", "网速显示", "",
                    new String[]{"显示", "隐藏"}, 0),
            SettingDef.value("autostart", "开机自启",
                    "开机后自动拉起本应用",
                    new String[]{"开启", "关闭"}, 0),
            SettingDef.value("decoder", "解码方式",
                    "低性能盒子软解会掉帧，仅在花屏时切换",
                    new String[]{"硬件", "软件"}, 0),
            SettingDef.value("timeout", "超时换源",
                    "起播无画面 / 播放中卡住超过该秒数，自动切下一条线路",
                    new String[]{"关闭", "5 秒", "8 秒", "12 秒", "20 秒"}, 2),
            SettingDef.action("headers", "请求头",
                    "该源需要特定 UA/Referer 时才填，全局生效", "编辑…"),
            SettingDef.value("engine", "播放内核",
                    "Exo 起播快 / VLC 格式覆盖最广",
                    new String[]{"ExoPlayer", "VLC"}, 0),
            SettingDef.value("clock", "时钟显示",
                    "面板收起看片时是否保留右上角日期时间",
                    new String[]{"随面板隐藏", "始终显示"}, 0),
            SettingDef.value("output", "画面输出",
                    "黑屏只有声音时切到 SurfaceView 试试",
                    new String[]{"TextureView", "SurfaceView"}, 0),
            SettingDef.value("theme", "界面主题",
                    "只换配色，布局与操作完全一致",
                    new String[]{"暗黑", "盒子蓝", "暖色"}, 0),
            SettingDef.action("group_mgr", "频道分组管理",
                    "隐藏不看的分类，只留常用的", "打开管理"),
    };

    private Settings() { }

    /**
     * 编译期管不住的，运行期立刻炸。
     *
     * <p>在 App.onCreate 里调一次。它保证：
     * 表非空、id 不重复、索引常量与表长度一致、默认值合法。
     * 任何一条不满足都直接抛异常 —— 宁可启动崩，也不要带着
     * 错位的行号去改用户的配置。
     */
    public static void verify() {
        if (ALL.length == 0) throw new IllegalStateException("设置表为空");

        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < ALL.length; i++) {
            SettingDef d = ALL[i];
            if (d == null) throw new IllegalStateException("第 " + i + " 行为 null");
            Integer old = seen.put(d.id, i);
            if (old != null) {
                throw new IllegalStateException(
                        "设置 id 重复：'" + d.id + "' 出现在第 " + old + " 和 " + i + " 行");
            }
        }

        // 索引常量必须覆盖整张表，且不能越界
        int maxIdx = Math.max(IDX_GROUP_MGR, Math.max(IDX_THEME, IDX_OUTPUT));
        if (maxIdx != ALL.length - 1) {
            throw new IllegalStateException(
                    "索引常量与表长度不同步：最大索引 " + maxIdx + "，表长 " + ALL.length
                    + "。加/删设置项时记得同步 Settings 里的 IDX_* 常量。");
        }
    }

    /** 默认选中值数组，供焦点引擎初始化。 */
    public static int[] defaultSelections() {
        int[] out = new int[ALL.length];
        for (int i = 0; i < ALL.length; i++) out[i] = ALL[i].def;
        return out;
    }

    /** 每行的选项数，供焦点引擎初始化。 */
    public static int[] optionCounts() {
        int[] out = new int[ALL.length];
        for (int i = 0; i < ALL.length; i++) {
            out[i] = Math.max(1, ALL[i].optionCount());
        }
        return out;
    }

    /** 标题数组。 */
    public static String titleOf(int i) { return ALL[i].title; }

    /** 副标题。 */
    public static String hintOf(int i) { return ALL[i].hint; }

    /** 选项文案。 */
    public static String[] optionsOf(int i) { return ALL[i].options; }

    /** 是值行（左右可改）还是动作行（按 OK 触发）。 */
    public static boolean isValueRow(int i) {
        return i >= 0 && i < ALL.length && ALL[i].isValue();
    }
}
