package com.gg.tv.ui;

import java.util.ArrayList;
import java.util.List;

/** 全部静态数据。主线接真实源时，把这里换成 ChannelStore 的输出即可。 */
public final class DemoData {

    private DemoData() {}

    public static final class Category {
        public final String name;
        public final int count;
        public final int glyph;
        public Category(String n, int c, int g) { name = n; count = c; glyph = g; }
    }

    public static final class Channel {
        public final String no, name, now;
        public final String badge;   // null / "播放中" / "可回看"
        public final String url;
        public Channel(String no, String name, String now, String badge, String url) {
            this.no = no; this.name = name; this.now = now; this.badge = badge; this.url = url;
        }
    }

    public static List<Category> categories() {
        List<Category> l = new ArrayList<>();
        l.add(new Category("全部", 463, 0));
        l.add(new Category("省内", 9, 1));
        l.add(new Category("电台", 165, 2));
        l.add(new Category("央视", 29, 3));
        l.add(new Category("卫视", 48, 4));
        l.add(new Category("4K", 8, 5));
        l.add(new Category("新闻", 9, 5));
        l.add(new Category("电影", 31, 6));
        l.add(new Category("体育", 8, 7));
        l.add(new Category("儿童", 19, 7));
        l.add(new Category("纪录", 12, 5));
        l.add(new Category("音乐", 6, 6));
        return l;
    }

    /** 北京本地台（对应设计稿第一屏） */
    public static List<Channel> bj() {
        // 公开测试流，仅用于验证播放链路是否通畅
        final String TEST = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8";
        List<Channel> l = new ArrayList<>();
        l.add(new Channel("0076", "北京卫视", "正在播放 · 19:00 新闻联播", "可回看", TEST));
        l.add(new Channel("0276", "北京文艺", "正在播放 · 19:00 笑动剧场", null, TEST));
        l.add(new Channel("0277", "北京纪实科教", "正在播放 · 暂无节目信息", null, TEST));
        l.add(new Channel("0278", "北京影视", "正在播放 · 18:35 上午剧场", null, TEST));
        l.add(new Channel("0279", "北京财经", "正在播放 · 18:50 总裁读书会", "播放中", TEST));
        l.add(new Channel("0280", "北京生活", "正在播放 · 19:00 王芳遇上王为民", null, TEST));
        l.add(new Channel("0281", "北京新闻", "正在播放 · 17:00 直播北京", null, TEST));
        l.add(new Channel("0282", "卡酷少儿", "正在播放 · 18:40 熊出没", null, TEST));
        return l;
    }

    public static List<Channel> all() {
        final String TEST = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8";
        List<Channel> l = new ArrayList<>();
        String[][] d = {
                {"0001", "CCTV-1 综合", "19:00 新闻联播", "可回看"},
                {"0002", "CCTV-2 财经", "19:00 经济半小时", ""},
                {"0003", "CCTV-5 体育", "19:30 体育新闻", ""},
                {"0005", "CCTV-5+ 体育赛事", "19:00 赛事直播", ""},
                {"0006", "CCTV-6 电影", "19:30 佳片有约", ""},
                {"0009", "CCTV-9 纪录", "19:00 大美中国", ""},
                {"0013", "CCTV-13 新闻", "19:00 新闻联播", "可回看"},
                {"0017", "CCTV-17 农业", "19:00 乡土", ""},
                {"0201", "湖南卫视", "19:30 金鹰独播剧场", ""},
                {"0203", "浙江卫视", "19:00 奔跑吧", ""},
                {"0208", "东方卫视", "19:00 东方新闻", ""},
                {"0212", "江苏卫视", "19:00 幸福剧场", ""},
        };
        for (String[] r : d) {
            l.add(new Channel(r[0], r[1], "正在播放 · " + r[2], r[3].isEmpty() ? null : r[3], TEST));
        }
        return l;
    }

    // ---------- 设置页 ----------

    public static final String[] TABS = {"账户登录", "播放设置", "手机助手"};

    public static final String[][] ROW_TITLES = {
            {"画面比例", ""},
            {"EPG显示", ""},
            {"上下换台反转", "当前：下键上一个，上键下一个"},
            {"换台显示", "换台时：台号和节目框 / 只台号 / 只节目框"},
            {"左右按键", "直播：左右键时移/出 EPG（默认）"},
            {"网速显示", ""},
            {"开机自动播放", ""},
            {"解码方式", "低性能盒子软解会掉帧，仅在花屏时切换"},
    };

    public static final String[][] ROW_OPTIONS = {
            {"自动适应", "16:9", "4:3", "原始", "铺满"},
            {"居左", "居中"},
            {"已开启 (ON)", "关闭"},
            {"台号+EPG", "台号", "EPG"},
            {"查看 EPG 回看", "切源"},
            {"显示", "隐藏"},
            {"开启", "关闭"},
            {"硬件", "软件"},
    };

    public static final int[] ROW_DEFAULT = {0, 0, 0, 0, 0, 0, 1, 0};
}
