package com.mintv.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * 开机自启动。
 *
 * <p>各版本的实际情况不一样，这里按「能做就做、做不了不崩」处理：
 * <ul>
 *   <li><b>Android 4.4 ~ 9（API 19~28）</b>：从广播里直接
 *       {@code startActivity} 是允许的，能真正自启。</li>
 *   <li><b>Android 10+（API 29+）</b>：后台启动 Activity 被系统限制，
 *       {@code startActivity} 通常被静默丢弃（不抛异常也不显示）。
 *       在国产 ROM（MIUI/EMUI 等）上，还需要用户手动打开
 *       「自启动 / 后台弹出界面」权限才可能生效。</li>
 * </ul>
 *
 * <p>另外一条更可靠的路：把本应用设为**默认桌面（Launcher）**，
 * 开机必然进入 —— 本项目在 Manifest 里已经带 LEANBACK_LAUNCHER，
 * 电视盒子上可以在系统设置里把它设成开机启动的桌面。
 *
 * <p>所以这个 receiver 的定位是「低版本真的能自启，高版本尽力尝试，
 * 失败也绝不产生崩溃或 ANR」。
 */
public class BootReceiver extends BroadcastReceiver {

    /** 与 MainActivity 里的 PREF 保持一致。 */
    private static final String PREF = "mintv";
    /** 开机自启开关：true 才拉起。 */
    private static final String KEY_AUTOSTART = "boot_autostart";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)
                && !"android.intent.action.HTC.QUICKBOOT_POWERON".equals(action)) {
            return;
        }

        SharedPreferences sp = context.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE);
        // 兼容老 key（旧版本写的是 boot_autoplay）
        if (!sp.getBoolean(KEY_AUTOSTART, sp.getBoolean("boot_autoplay", false))) return;

        try {
            Intent i = new Intent();
            i.setClassName(context, "com.mintv.tv.ui.MainActivity");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(i);
        } catch (Throwable t) {
            // Android 10+ 后台启动被拒、或厂商 ROM 拦截，都走这里。
            // 不重试、不弹通知 —— 用户下次手动打开即可。
            android.util.Log.w("BootReceiver", "开机自启动被系统拦下: " + t);
        }
    }
}
