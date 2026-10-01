// Language and theme toggles. Preferences live in localStorage when it is available;
// the page renders correctly without it.
(function () {
  var root = document.documentElement;
  function load(key) { try { return window.localStorage.getItem(key); } catch (e) { return null; } }
  function save(key, value) { try { window.localStorage.setItem(key, value); } catch (e) { /* storage unavailable */ } }

  function initialLang() {
    var stored = load('pm-guide-lang');
    if (stored === 'en' || stored === 'ko') return stored;
    var nav = (navigator.language || 'en').toLowerCase();
    return nav.indexOf('ko') === 0 ? 'ko' : 'en';
  }
  function applyLang(lang) {
    root.setAttribute('lang', lang);
    var t = document.querySelector('[data-title-' + lang + ']');
    if (t) document.title = t.getAttribute('data-title-' + lang);
    var btn = document.getElementById('lang-toggle');
    if (btn) { btn.textContent = lang === 'en' ? '한국어' : 'English'; btn.setAttribute('aria-label', lang === 'en' ? '한국어로 보기' : 'View in English'); }
  }
  function applyTheme(theme) {
    if (theme === 'light' || theme === 'dark') root.setAttribute('data-theme', theme); else root.removeAttribute('data-theme');
    var btn = document.getElementById('theme-toggle');
    if (btn) btn.textContent = isDark() ? '☾' : '☀';
  }
  function isDark() {
    var t = root.getAttribute('data-theme');
    if (t) return t === 'dark';
    return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
  }

  applyLang(initialLang());
  applyTheme(load('pm-guide-theme'));

  document.addEventListener('DOMContentLoaded', function () {
    applyLang(root.getAttribute('lang'));
    applyTheme(root.getAttribute('data-theme'));
    var lb = document.getElementById('lang-toggle');
    if (lb) lb.addEventListener('click', function () {
      var next = root.getAttribute('lang') === 'en' ? 'ko' : 'en';
      applyLang(next); save('pm-guide-lang', next);
    });
    var tb = document.getElementById('theme-toggle');
    if (tb) tb.addEventListener('click', function () {
      var next = isDark() ? 'light' : 'dark';
      applyTheme(next); save('pm-guide-theme', next);
    });
    var mb = document.getElementById('menu-toggle');
    var nav = document.getElementById('site-nav');
    if (mb && nav) mb.addEventListener('click', function () {
      var open = nav.classList.toggle('open');
      mb.setAttribute('aria-expanded', open ? 'true' : 'false');
    });
  });
})();
