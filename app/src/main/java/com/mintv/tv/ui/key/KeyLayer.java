package com.mintv.tv.ui.key;

import android.view.KeyEvent;

/**
 * 按键处理框架。
 *
 * <p>要解决的问题：以前所有按键逻辑挤在 Activity.onKeyDown 的一个 160 行
 * if-else 链里，「谁先谁后」靠书写顺序表达。结果是：
 * <ul>
 *   <li>音量键写在 L1 后面 → 设置弹窗里调不了音量；</li>
 *   <li>数字键写在节目单前面 → 节目单里按数字会换台；</li>
 *   <li>BACK 在三个地方各判一次，条件各不相同；</li>
 *   <li>MENU 的 startTracking 和 return true 顺序一颠倒，长按就成了死代码。</li>
 * </ul>
 * 每加一个功能就往链子中间插一段 if，插错位置就是一个 bug。
 *
 * <p>现在改成三层结构：
 * <pre>
 *   KeyLayer（层）     —— 表达"谁在上层"，物理隔离，互不影响
 *   KeyHandler（处理） —— 每层内部自己组织，怎么写都不会漏到别层
 *   KeyRouter（路由）  —— 只负责按优先级找到第一个愿意处理的层
 * </pre>
 *
 * <p>加新功能时的正确姿势：
 * <ul>
 *   <li>功能属于已有层 → 在那一层的 key 方法里加，别的层不受影响；</li>
 *   <li>功能是新的一层（比如"搜索框"）→ 新建一个 KeyLayer，
 *       在 KeyRouter 里按位置插入即可，不用碰任何已有代码。</li>
 * </ul>
 */
public interface KeyLayer {

    /** 层的名字，只用于日志排查。 */
    String name();

    /**
     * 这一层当前是否"在场"。
     *
     * <p>不在场的层会被路由直接跳过，连 key() 都不会调用 ——
     * 这就是"谁在上层谁优先"的实现方式：不是靠顺序比较，
     * 而是靠"下层根本没被问"。不在场时 {@link #consume()} 也不会执行。
     */
    boolean active();

    /**
     * 处理按键。返回 true 表示已消费。
     *
     * <p>注意：返回 false 不代表"没处理"，还要看 {@link #consume()}。
     * 这是为了区分"我处理了但不想拦"和"我没处理"两种语义。
     */
    boolean key(KeyEvent event);

    /**
     * 这一层是否吞掉所有未被处理的按键。
     *
     * <p>弹窗/抽屉类必须返回 true：用户在弹窗里按了个没绑定的键，
     * 事件绝不能漏到下面的主界面去（否则设置开着时方向键会改频道焦点）。
     *
     * <p>主界面层返回 false：它处理不了的键应该交给 Activity 的
     * 默认行为（比如返回键退出、媒体键等）。
     */
    boolean consume();

    /**
     * 长按。默认交还给路由继续往下找。
     *
     * <p>单独一个方法而不是塞进 key()：长按和短按的语义完全不同
     * （短按 MENU 开设置、长按开诊断），混在一起极易写错。
     */
    boolean longPress(KeyEvent event);

    /** 抬起。用于"按下先跟踪、抬手才动作"这一类交互。 */
    boolean keyUp(KeyEvent event);
}
