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

    /** 总表。下标即行号。 */
    public static final SettingDef[] ALL = {
            // ════════ 频道源 ════════
            // 用户最常动的两块放最前面：换订阅、切线路
            SettingDef.value("show_subs", "显示订阅列",
                    "开启后左侧多一列订阅，可在播放页直接切换源。默认关闭",
                    new String[]{"已关闭", "开启"}, 0),
            SettingDef.action("source", "切源",
                    "同一频道多条线路时手动切换，左=上一条 右=下一条", null),
            SettingDef.action("headers", "请求头",
                    "该源需要特定 UA/Referer 时才填，全局生效", "编辑…"),
            SettingDef.action("group_mgr", "频道分组管理",
                    "隐藏不看的分类，只留常用的", "打开管理"),

            // ════════ 播放 ════════
            SettingDef.value("decoder", "解码方式",
                    "自动＝VLC 原生策略（硬解失败自动退软解）；内置硬解码＝强制 MediaCodec；软件解码＝不用显卡",
                    new String[]{"自动", "内置硬解码", "软件解码"}, 0),
            SettingDef.value("aspect", "画面比例", "",
                    new String[]{"自动适应", "16:9", "4:3", "原始", "铺满"}, 0),
            SettingDef.value("output", "画面输出",
                    "黑屏只有声音时切到 SurfaceView 试试",
                    new String[]{"TextureView", "SurfaceView"}, 0),
            SettingDef.value("timeout", "超时换源",
                    "起播无画面 / 播放中卡住超过该秒数，自动切下一条线路",
                    new String[]{"关闭", "5 秒", "8 秒", "12 秒", "20 秒"}, 2),
            SettingDef.action("net_proxy", "播放器代理",
                    "只作用于播放器连源。支持 http:// 或 socks5://，留空直连。脚本要代理需自己在脚本里写", "设置…"),

            // ════════ 界面 ════════
            SettingDef.value("switch_ui", "换台显示",
                    "换台时：台号和节目框 / 只台号 / 只节目框",
                    new String[]{"台号+EPG", "台号", "EPG"}, 0),
            SettingDef.value("epg_pos", "EPG 位置",
                    "信息条贴左（不挡画面中心）还是水平居中",
                    new String[]{"居左", "居中"}, 0),
            SettingDef.value("clock", "时钟显示",
                    "面板收起看片时是否保留右上角日期时间",
                    new String[]{"随面板隐藏", "始终显示"}, 0),
            SettingDef.value("speed", "网速显示", "",
                    new String[]{"显示", "隐藏"}, 0),
            SettingDef.action("theme", "主题颜色",
                    "调的是强调色：选中框、进度、按钮。背景保持深色", "选择…"),

            // ════════ 操作 ════════
            SettingDef.value("reverse", "上下换台反转",
                    "当前：下键上一个，上键下一个",
                    new String[]{"已开启 (ON)", "关闭"}, 0),
            SettingDef.value("left_key", "左右按键",
                    "直播：左右键时移/出 EPG（默认）",
                    new String[]{"查看 EPG 回看", "切源"}, 0),

            // ════════ 其它 ════════
            SettingDef.value("autostart", "开机自启",
                    "开机后自动拉起本应用",
                    new String[]{"开启", "关闭"}, 0),
            SettingDef.action("storage_dir", "存储目录",
                    "选一个外部目录放脚本，文件管理器可见；不选则用 App 私有目录", "选择…"),
            SettingDef.value("p2p_boost", "源力加速",
                    "和同频道观众共享带宽，高峰期更流畅；会使用部分上传流量",
                    new String[]{"已关闭", "开启"}, 0),
    };

    // ══════════════════════════════════════════════════════════
    //  索引常量：由 id 在总表里的位置算出来，**不再手工编号**
    //
    //  以前是 public static final int IDX_CLOCK = 11; 这样硬写。
    //  删掉「播放内核」那行时，后面四个常量必须手工往前挪，漏一个
    //  编译器不会报错，运行时却静默读到相邻的另一项（用户看到的是
    //  "改 A 结果 B 变了"）。改成查表后，删行自动重排，没有漏改的可能。
    // ══════════════════════════════════════════════════════════
    public static final int IDX_SOURCE = indexOf("source");
    public static final int IDX_ASPECT = indexOf("aspect");
    public static final int IDX_EPG_POS = indexOf("epg_pos");
    public static final int IDX_REVERSE = indexOf("reverse");
    public static final int IDX_SWITCH_UI = indexOf("switch_ui");
    public static final int IDX_LEFT_KEY = indexOf("left_key");
    public static final int IDX_SPEED = indexOf("speed");
    public static final int IDX_AUTOSTART = indexOf("autostart");
    public static final int IDX_DECODER = indexOf("decoder");
    public static final int IDX_TIMEOUT = indexOf("timeout");
    public static final int IDX_HEADERS = indexOf("headers");
    public static final int IDX_CLOCK = indexOf("clock");
    public static final int IDX_OUTPUT = indexOf("output");
    public static final int IDX_THEME = indexOf("theme");
    public static final int IDX_SHOW_SUBS = indexOf("show_subs");
    public static final int IDX_STORAGE_DIR = indexOf("storage_dir");
    public static final int IDX_P2P_BOOST = indexOf("p2p_boost");
    public static final int IDX_NET_PROXY = indexOf("net_proxy");

    public static final int IDX_GROUP_MGR = indexOf("group_mgr");

    /** 按 id 查行号。找不到直接抛 —— 说明常量名和总表里的 id 对不上。 */
    private static int indexOf(String id) {
        for (int i = 0; i < ALL.length; i++) {
            if (ALL[i].id.equals(id)) return i;
        }
        throw new IllegalStateException("设置项不存在: " + id);
    }

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

        // 索引常量必须在表范围内。
        //
        // 原来这里检查的是「最大索引必须等于最后一行」，那等于要求
        // 最后一行必须是某个特定设置项 —— 重排一次就报错，而重排
        // 本身是允许的（indexOf 会自动跟上）。改成只验范围。
        int[] all = {IDX_SOURCE, IDX_ASPECT, IDX_EPG_POS, IDX_REVERSE, IDX_SWITCH_UI,
                IDX_LEFT_KEY, IDX_SPEED, IDX_AUTOSTART, IDX_DECODER, IDX_TIMEOUT,
                IDX_HEADERS, IDX_CLOCK, IDX_OUTPUT, IDX_THEME, IDX_SHOW_SUBS,
                IDX_STORAGE_DIR, IDX_NET_PROXY, IDX_GROUP_MGR};
        for (int v : all) {
            if (v < 0 || v >= ALL.length) {
                throw new IllegalStateException("索引常量越界：" + v + "，表长 " + ALL.length);
            }
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
