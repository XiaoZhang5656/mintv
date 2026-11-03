package com.mintv.tv;

import android.content.Context;

import androidx.multidex.MultiDex;
import androidx.multidex.MultiDexApplication;

/**
 * 应用入口。
 *
 * <p>必须存在的原因：依赖加起来已经超过 64K 方法数（media3 + libvlc 绑定 +
 * zxing + androidx.core），构建时会被拆成多个 dex。Android 5.0 以下
 * （我们的 minSdk 是 19）运行时不会自动加载第二个 dex，
 * 必须在 attachBaseContext 里手动 install。
 *
 * <p>5.0+ 系统自带 multidex，调用了也无害。
 */
public class App extends MultiDexApplication {

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        MultiDex.install(this);
    }
}
