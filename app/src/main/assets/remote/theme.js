/*
 * MinTV 主题：跟随系统 / 浅色 / 深色
 * 所有页面共用这一份，主题存 localStorage，跨页面保持一致。
 * 用法：<script src="theme.js"></script>
 *      有切换按钮的页面：按钮 onclick="MintvTheme.cycle()"，
 *      文案由 MintvTheme.apply() 自动写到 [data-theme-btn]。
 */
(function () {
  var KEY = 'mintv-theme';
  var modes = ['auto', 'light', 'dark'];
  var labels = { auto: '跟随系统', light: '浅色', dark: '深色' };

  function current() {
    var v = localStorage.getItem(KEY);
    return (v === 'light' || v === 'dark') ? v : 'auto';
  }

  function paint() {
    var cur = current();
    var root = document.documentElement;
    if (cur === 'auto') root.removeAttribute('data-theme');
    else root.setAttribute('data-theme', cur);
    document.querySelectorAll('[data-theme-btn]').forEach(function (b) {
      b.textContent = labels[cur];
    });
  }

  function cycle() {
    var cur = current();
    var next = modes[(modes.indexOf(cur) + 1) % modes.length];
    localStorage.setItem(KEY, next);
    paint();
  }

  // 尽早应用，避免闪一下
  paint();
  document.addEventListener('DOMContentLoaded', paint);

  window.MintvTheme = { paint: paint, cycle: cycle, current: current };
})();
