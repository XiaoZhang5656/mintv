package com.mintv.tv.ui;

import com.mintv.tv.data.PlaylistParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 内置示范数据。
 *
 * <p>只在两种情况下使用：
 * 1. 没配置直播源（首次安装）
 * 2. 配置的源拉取失败，且本地没有缓存
 *
 * <p>真实渲染一律走 {@link PlaylistParser} 的解析结果，图标只认 tvg-logo。
 */
public final class DemoData {

    private DemoData() {}

    public static final class Category {
        public final String name;
        public final int count;
        public final int glyph;
        public Category(String n, int c, int g) { name = n; count = c; glyph = g; }
    }

    /** 把解析结果转成左侧导航用的分类。图标按名字粗略匹配，匹配不到就走默认字形。 */
    public static List<Category> toCategories(PlaylistParser.Playlist pl) {
        List<Category> l = new ArrayList<>();
        l.add(new Category("全部", pl.all.size(), 0));
        for (Map.Entry<String, List<PlaylistParser.Channel>> e : pl.byGroup.entrySet()) {
            l.add(new Category(e.getKey(), e.getValue().size(), glyphOf(e.getKey())));
        }
        return l;
    }

    private static int glyphOf(String group) {
        if (group == null) return 5;
        if (group.contains("央视") || group.contains("CCTV")) return 3;
        if (group.contains("卫视")) return 4;
        if (group.contains("省") || group.contains("本地") || group.contains("市")) return 1;
        if (group.contains("电台") || group.contains("广播")) return 2;
        if (group.contains("电影")) return 6;
        if (group.contains("体育")) return 7;
        if (group.contains("新闻")) return 5;
        if (group.contains("儿童") || group.contains("少儿")) return 7;
        if (group.contains("4K") || group.contains("超清")) return 5;
        return 5;
    }

    /**
     * 设置页的四个标签。
     *
     * <p>「手机助手」改叫「订阅」—— 那一页里是二维码 + 订阅列表，
     * 用户找的是"我的源在哪"，不是"手机怎么连"。
     */
    public static final String[] TABS = {"登录", "设置", "关于", "订阅"};

    /**
     * 三张旧表保留成"视图"，内容从 {@link com.mintv.tv.ui.setting.Settings}
     * 派生，不再手工维护。
     *
     * <p>这样老代码里所有 ROW_TITLES[i] / ROW_OPTIONS[i] / ROW_DEFAULT[i]
     * 的引用都不用改，但数据只有一份（Settings.ALL），不可能再对不上。
     *
     * @deprecated 新代码请直接用 Settings.*，这三张表只为兼容旧引用。
     */
    @Deprecated
    public static final String[][] ROW_TITLES = buildTitles();

    @Deprecated
    public static final String[][] ROW_OPTIONS = buildOptions();

    @Deprecated
    public static final int[] ROW_DEFAULT = com.mintv.tv.ui.setting.Settings.defaultSelections();

    private static String[][] buildTitles() {
        com.mintv.tv.ui.setting.SettingDef[] all = com.mintv.tv.ui.setting.Settings.ALL;
        String[][] out = new String[all.length][2];
        for (int i = 0; i < all.length; i++) {
            out[i][0] = all[i].title;
            out[i][1] = all[i].hint;
        }
        return out;
    }

    private static String[][] buildOptions() {
        com.mintv.tv.ui.setting.SettingDef[] all = com.mintv.tv.ui.setting.Settings.ALL;
        String[][] out = new String[all.length][];
        for (int i = 0; i < all.length; i++) {
            out[i] = all[i].options;
        }
        return out;
    }

    // ---------- 无源时的示范 M3U ----------

    /**
     * 示范数据同样走解析器，字段结构跟真实源一致，
     * 这样「有源 / 无源」两条路的渲染代码只有一份。
     */
    // 内置演示频道已删除：正式包里不该出现用户没添加过的频道。
    // 需要看布局效果时，自己加一个 m3u 源即可。
}
