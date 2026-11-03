/*
 * MinTV 页面公共脚本：主题 + 盒子通信
 * 所有页面共用，保证主题和请求逻辑只有一份。
 */
(function () {
  // ---------------- 主题 ----------------
  var KEY = 'mintv-theme';
  var modes = ['auto', 'light', 'dark'];
  var labels = { auto: '跟随系统', light: '浅色', dark: '深色' };

  function cur() {
    var v = localStorage.getItem(KEY);
    return (v === 'light' || v === 'dark') ? v : 'auto';
  }

  function paint() {
    var c = cur();
    var root = document.documentElement;
    if (c === 'auto') root.removeAttribute('data-theme');
    else root.setAttribute('data-theme', c);
    document.querySelectorAll('[data-theme-btn]').forEach(function (b) {
      b.textContent = labels[c];
    });
  }

  function cycle() {
    var c = cur();
    localStorage.setItem(KEY, modes[(modes.indexOf(c) + 1) % modes.length]);
    paint();
  }

  paint();
  document.addEventListener('DOMContentLoaded', paint);

  // ---------------- 与盒子通信 ----------------
  function get(url) {
    return fetch(url, { cache: 'no-store' }).then(function (r) { return r.json(); });
  }

  function post(url, obj) {
    return fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(obj)
    }).then(function (r) { return r.json(); });
  }

  window.MintvTheme = { paint: paint, cycle: cycle, current: cur };
  window.MintvApi = {
    state: function () { return get('/api/state'); },
    subs: function () { return get('/api/subs'); },
    cmd: function (type, value) { return post('/api/cmd', { type: type, value: value || '' }); },
    subsAction: function (action, extra) {
      var body = Object.assign({ action: action }, extra || {});
      return post('/api/subs', body);
    }
  };

  // ── 轮询控制 ──
  //
  // 两个必须解决的问题：
  //  1) 手机切后台要停，回来补一次（省电）；
  //  2) **同一个页面里只能有一个轮询在跑**。以前每个页面各建各的
  //     setInterval，用户在订阅页和频道页之间来回切时，旧页面的
  //     定时器没人 clear，几个请求同时打 /api/state，数字乱跳、
  //     按钮响应错位 —— 表现就是"遥控和页面打架"。
  var __pollTimer = null;
  var __pollFn = null;

  window.MintvPoll = function (fn, ms) {
    // 先干掉可能存在的上一个
    if (__pollTimer) { clearInterval(__pollTimer); __pollTimer = null; }
    __pollFn = fn;
    function tick() { if (__pollFn) __pollFn(); }
    function start() {
      if (__pollTimer) return;
      tick();
      __pollTimer = setInterval(tick, ms);
    }
    function stop() {
      if (__pollTimer) { clearInterval(__pollTimer); __pollTimer = null; }
    }
    // 页面卸载时一定停 —— 内嵌 WebView 里 visibilitychange 不一定触发
    window.addEventListener('pagehide', stop);
    window.addEventListener('unload', stop);
    document.addEventListener('visibilitychange', function () {
      if (document.hidden) stop(); else start();
    });
    start();
    return { stop: stop, start: start };
  };

  // 页面跳转前主动停掉，避免旧页面的请求打到新页面上
  window.MintvStopPoll = function () {
    if (__pollTimer) { clearInterval(__pollTimer); __pollTimer = null; }
    __pollFn = null;
  };
})();
