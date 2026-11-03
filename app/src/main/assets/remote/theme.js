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

  // 切到后台就停轮询，回来补一次 —— 手机放一边时不白烧电
  window.MintvPoll = function (fn, ms) {
    var timer = null;
    function tick() { fn(); }
    function start() { if (!timer) { tick(); timer = setInterval(tick, ms); } }
    function stop() { if (timer) { clearInterval(timer); timer = null; } }
    document.addEventListener('visibilitychange', function () {
      if (document.hidden) stop(); else start();
    });
    start();
    return { stop: stop, start: start };
  };
})();
