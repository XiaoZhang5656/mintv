package com.mintv.tv.ui.key;

import android.util.Log;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 按键路由：按注册顺序问每一层，第一个 active 且愿意处理的层胜出。
 *
 * <p>顺序 = 优先级，**显式声明在 addLayer 的调用处**，不再隐含在
 * if-else 的书写顺序里。看 KeyRouter 的构造代码就能知道完整优先级，
 * 不用在 160 行 if 链里找。
 */
public final class KeyRouter {

    private static final String TAG = "MinTVKey";

    private final List<KeyLayer> layers = new ArrayList<>();

    /** 按优先级从高到低添加。先加的在上层。 */
    public KeyRouter add(KeyLayer layer) {
        layers.add(layer);
        return this;
    }

    /** 当前活动层的名字，调试用。 */
    public String activeName() {
        for (KeyLayer l : layers) {
            if (l.active()) return l.name();
        }
        return "none";
    }

    /**
     * 选出该由哪一层处理这个按键。
     *
     * <p>抽成独立的纯函数（只做决策、不产生副作用），是为了能脱离
     * Android 运行时做单元测试 —— KeyEvent 在 android.jar 里是桩实现，
     * 测试环境根本 new 不出来。
     *
     * @return 该层在列表中的下标；-1 表示没有任何层在场，调用方兜底。
     */
    public int pickLayer(int code) {
        for (int i = 0; i < layers.size(); i++) {
            KeyLayer l = layers.get(i);
            if (l.active()) return i;      // 第一个在场的层就是胜出者
        }
        return -1;
    }

    /** 供测试使用：当前在场层的下标。 */
    public int activeIndex() {
        for (int i = 0; i < layers.size(); i++) {
            if (layers.get(i).active()) return i;
        }
        return -1;
    }

    public boolean onKeyDown(KeyEvent event) {
        int code = event.getKeyCode();
        // 音量键是最底层的全局约定：任何界面都要能调音量，
        // 所以它不走层，直接在路由这里兜底。
        if (code == KeyEvent.KEYCODE_VOLUME_UP
                || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return false;   // 交给 Activity 的统一处理
        }

        for (KeyLayer l : layers) {
            if (!l.active()) continue;
            boolean handled = l.key(event);
            if (handled) {
                Log.d(TAG, "layer=" + l.name() + " key=" + code + " -> handled");
                return true;
            }
            if (l.consume()) {
                // 这一层拦住了所有按键：即使它没处理这个键，也不许下发。
                // 这是"弹窗开着时，方向键不能改到下面频道焦点"的保证。
                Log.d(TAG, "layer=" + l.name() + " key=" + code + " -> consumed");
                return true;
            }
            Log.d(TAG, "layer=" + l.name() + " key=" + code + " -> pass");
        }
        Log.d(TAG, "key=" + code + " -> unhandled");
        return false;
    }

    public boolean onKeyLongPress(KeyEvent event) {
        for (KeyLayer l : layers) {
            if (!l.active()) continue;
            if (l.longPress(event)) {
                Log.d(TAG, "layer=" + l.name() + " longPress=" + event.getKeyCode());
                return true;
            }
            if (l.consume()) return true;
        }
        return false;
    }

    public boolean onKeyUp(KeyEvent event) {
        for (KeyLayer l : layers) {
            if (!l.active()) continue;
            if (l.keyUp(event)) {
                Log.d(TAG, "layer=" + l.name() + " keyUp=" + event.getKeyCode());
                return true;
            }
            if (l.consume()) return true;
        }
        return false;
    }
}
