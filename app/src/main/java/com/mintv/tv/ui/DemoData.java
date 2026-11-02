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

    public static final String[] TABS = {"账户登录", "播放设置", "手机助手"};

    public static final String[][] ROW_TITLES = {
            {"切源", "同一频道多条线路时手动切换，左=上一条 右=下一条"},
            {"画面比例", ""},
            {"EPG显示", ""},
            {"上下换台反转", "当前：下键上一个，上键下一个"},
            {"换台显示", "换台时：台号和节目框 / 只台号 / 只节目框"},
            {"左右按键", "直播：左右键时移/出 EPG（默认）"},
            {"网速显示", ""},
            {"开机自启", "开机后自动拉起本应用"},
            {"解码方式", "低性能盒子软解会掉帧，仅在花屏时切换"},
            {"超时换源", "起播无画面 / 播放中卡住超过该秒数，自动切下一条线路"},
            {"请求头", "该源需要特定 UA/Referer 时才填，全局生效"},
            {"播放内核", "Exo 起播快 / VLC 格式覆盖最广"},
            {"时钟显示", "面板收起看片时是否保留右上角日期时间"},
            {"画面输出", "黑屏只有声音时切到 SurfaceView 试试"},
    };

    public static final String[][] ROW_OPTIONS = {
            {"◀ 上一条", "下一条 ▶"},
            {"自动适应", "16:9", "4:3", "原始", "铺满"},
            {"居左", "居中"},
            {"已开启 (ON)", "关闭"},
            {"台号+EPG", "台号", "EPG"},
            {"查看 EPG 回看", "切源"},
            {"显示", "隐藏"},
            {"开启", "关闭"},
            {"硬件", "软件"},
            {"关闭", "5 秒", "8 秒", "12 秒", "20 秒"},
            {"未设置", "编辑…"},
            {"ExoPlayer", "VLC"},
            {"随面板隐藏", "始终显示"},
            {"TextureView", "SurfaceView"},
    };

    public static final int[] ROW_DEFAULT = {0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0, 0, 0, 0};

    // ---------- 无源时的示范 M3U ----------

    /**
     * 示范数据同样走解析器，字段结构跟真实源一致，
     * 这样「有源 / 无源」两条路的渲染代码只有一份。
     */
    public static final String SAMPLE_M3U =
            "#EXTM3U x-tvg-url=\"\"\n"
            + "#EXTINF:-1 tvg-id=\"bjws\" tvg-name=\"北京卫视\" group-title=\"省内\",北京卫视\n"
            + "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8\n"
            + "#EXTINF:-1 group-title=\"省内\",北京文艺\n"
            + "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8\n"
            + "#EXTINF:-1 group-title=\"省内\",北京纪实科教\n"
            + "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8\n"
            + "#EXTINF:-1 group-title=\"省内\",北京影视\n"
            + "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8\n"
            + "#EXTINF:-1 group-title=\"省内\",北京财经\n"
            + "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8\n"
            + "#EXTINF:-1 group-title=\"省内\",北京生活\n"
            + "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8\n"
            + "#EXTINF:-1 group-title=\"省内\",北京新闻\n"
            + "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8\n"
            + "#EXTINF:-1 group-title=\"省内\",卡酷少儿\n"
            + "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8\n"
            + "央视,#genre#\n"
            + "CCTV-1 综合,https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8\n"
            + "CCTV-2 财经,https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8\n"
            + "CCTV-5 体育,https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8\n"
            + "CCTV-6 电影,https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8\n"
            + "CCTV-9 纪录,https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8\n"
            + "CCTV-13 新闻,https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8\n"
            + "卫视,#genre#\n"
            + "湖南卫视,https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8\n"
            + "浙江卫视,https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8\n"
            + "东方卫视,https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8\n"
            + "江苏卫视,https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8\n";
}
