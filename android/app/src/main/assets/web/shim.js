/*
 * WebView 壳 <-> 页面 之间的桥接脚本。
 * 由 MainActivity 在返回 index.html 时同步插入到 <head> 里（在主 bundle 之前执行），
 * 负责四件网页端做不到 / 做不好的事：
 *   1. blob:/data: 的「下载」（导出聊天记录 ZIP）转交给原生保存到系统「下载」目录；
 *   2. 剪贴板写入走原生（WebView 里 Clipboard API 经常被静默拒绝）；
 *   3. window.open 弹窗转交系统浏览器；
 *   4. 把页面底色同步给原生，做状态栏/导航栏染色（跟随浅色/深色主题）。
 * 另外暴露 __hermesCanCloseOverlay / __hermesCloseOverlay 给系统返回键用。
 */
(function () {
  if (window.__deepseekShell) return;
  window.__deepseekShell = true;

  function native() {
    return window.AndroidApp;
  }
  function safe(fn) {
    try {
      return fn();
    } catch (e) {
      try { native() && native().log('shim: ' + e); } catch (_) {}
      return undefined;
    }
  }

  // ---------------------------------------------------------------- 下载
  function saveHref(href, name) {
    if (!href) return;
    if (href.indexOf('data:') === 0) {
      safe(function () { native().saveDataUrl(name || 'download', href); });
      return;
    }
    fetch(href)
      .then(function (r) { return r.blob(); })
      .then(function (blob) {
        var fr = new FileReader();
        fr.onload = function () {
          safe(function () { native().saveDataUrl(name || 'download', String(fr.result)); });
        };
        fr.onerror = function () { safe(function () { native().log('read blob failed'); }); };
        fr.readAsDataURL(blob);
      })
      .catch(function (e) { safe(function () { native().log('fetch blob failed: ' + e); }); });
  }

  // file-saver / JSZip 都是「造一个 <a download> 再 click()」，这里拦 click 就够
  var origClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    var href = this.href || '';
    var dl = this.getAttribute('download');
    if (dl !== null && (href.indexOf('blob:') === 0 || href.indexOf('data:') === 0)) {
      saveHref(href, dl);
      return;
    }
    return origClick.apply(this, arguments);
  };

  // msSaveBlob 兜底（个别 polyfill 会探测它）
  if (!navigator.msSaveBlob) {
    navigator.msSaveBlob = function (blob, name) {
      safe(function () {
        var fr = new FileReader();
        fr.onload = function () { native().saveDataUrl(name || 'download', String(fr.result)); };
        fr.readAsDataURL(blob);
      });
      return true;
    };
  }

  // ------------------------------------------------------------ 剪贴板
  safe(function () {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      var origWrite = navigator.clipboard.writeText.bind(navigator.clipboard);
      navigator.clipboard.writeText = function (text) {
        try { native().copyText(String(text)); } catch (e) {}
        return origWrite(text).catch(function () { return undefined; });
      };
    }
  });

  // -------------------------------------------------------- 外部链接
  safe(function () {
    var origOpen = window.open;
    window.open = function (url) {
      if (!url) return null;
      try {
        native().openUrl(String(url));
        return null;
      } catch (e) {
        return origOpen.apply(window, arguments);
      }
    };
  });

  // ------------------------------------------------ 状态栏跟随页面主题
  function hexOf(color) {
    var m = /rgba?\((\d+)[,\s]+(\d+)[,\s]+(\d+)/.exec(color || '');
    if (!m) return '';
    function h(n) {
      var s = Number(n).toString(16);
      return s.length < 2 ? '0' + s : s;
    }
    return '#' + h(m[1]) + h(m[2]) + h(m[3]);
  }
  var lastChrome = '';
  function syncChrome() {
    var body = document.body;
    var el = body || document.documentElement;
    var c = hexOf(getComputedStyle(el).backgroundColor);
    if (!c || c === '#000000') {
      c = hexOf(getComputedStyle(document.documentElement).backgroundColor) || c || '#ffffff';
    }
    if (c && c !== lastChrome) {
      lastChrome = c;
      safe(function () { native().setChromeColor(c); });
    }
  }
  setInterval(syncChrome, 700);
  document.addEventListener('DOMContentLoaded', syncChrome);

  // ------------------------------------------------ 键盘弹出时的页面收缩
  // 做法来自 WebView 键盘处理的公认做法（native 拥有数字，页面拥有布局）：
  //   - WebView 里 visualViewport 看不到键盘（M139 之前的 WebView 都不行），
  //     所以键盘高度只能由 native 推进来；
  //   - 页面拿到「键盘高度（设备像素）」后，自己把「铺满一屏的根容器」高度改短。
  //     改的是页面自己的布局（渲染器内重排），不动 WebView 尺寸，所以既不会重建
  //     绘制表面（那才是"一帧贴一帧闪"的根源），又能逐帧跟着键盘动画走。
  var kbFrameEl = null;

  /**
   * 正在使用的根容器：从 textarea 往上找，优先取最外层的 `position: fixed` 祖先
   * （移动端 CSS 就是 `.frame { position:fixed; height: var(--app-viewport-height) }`），
   * 没有的话退回「高度=整屏」的最外层祖先。不写死 CSS-module 哈希。
   * 注意不要在解析失败时退回 body.firstElementChild —— 那是 frame 的父节点，改它没用。
   */
  function kbFrame() {
    var ta = document.querySelector('textarea');
    if (!ta) return null;
    if (kbFrameEl && kbFrameEl.isConnected && kbFrameEl.contains(ta)) return kbFrameEl;
    kbFrameEl = null;
    var el = ta;
    var h = document.documentElement.clientHeight;
    var fixedOne = null;
    var fullOne = null;
    while (el && el !== document.body) {
      var cs = getComputedStyle(el);
      if (cs.position === 'fixed') fixedOne = el;
      else if (el.getBoundingClientRect().height >= h - 4) fullOne = el;
      el = el.parentElement;
    }
    kbFrameEl = fixedOne || fullOne || null;
    return kbFrameEl;
  }

  /** devicePx = 键盘高度（设备像素，0 表示收起）。每帧调一次，纯样式写，无过渡。 */
  window.__dsKeyboardInset = function (devicePx) {
    try {
      var f = kbFrame();
      if (!f) return;
      if (!devicePx || devicePx <= 0) {
        f.style.removeProperty('height');
        return;
      }
      var dpr = window.devicePixelRatio || 1;
      var h = document.documentElement.clientHeight;
      var px = Math.max(80, h - devicePx / dpr);
      // inline + important：页面自己那套 --app-viewport-height 会时不时改写同一处高度
      f.style.setProperty('height', px.toFixed(1) + 'px', 'important');
    } catch (e) {}
  };

  // ------------------------------------------- 返回键：先关浮层关不掉再说
  var OVERLAY_SELECTORS = [
    '[role="dialog"]',
    '[aria-modal="true"]',
    '[data-radix-popper-content-wrapper]',
    '[class*="modal"]',
    '[class*="Modal"]',
    '[class*="drawer"]',
    '[class*="Drawer"]'
  ];
  window.__deepseekCanCloseOverlay = function () {
    for (var i = 0; i < OVERLAY_SELECTORS.length; i++) {
      var nodes = document.querySelectorAll(OVERLAY_SELECTORS[i]);
      for (var j = 0; j < nodes.length; j++) {
        var r = nodes[j].getBoundingClientRect();
        if (r.width > 40 && r.height > 40 && r.bottom > 0 && r.top < window.innerHeight) {
          var st = getComputedStyle(nodes[j]);
          if (st.display !== 'none' && st.visibility !== 'hidden' && Number(st.opacity) > 0.05) return true;
        }
      }
    }
    return false;
  };
  window.__deepseekCloseOverlay = function () {
    var ev = { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true };
    try {
      var target = document.activeElement || document.body;
      target.dispatchEvent(new KeyboardEvent('keydown', ev));
      document.dispatchEvent(new KeyboardEvent('keydown', ev));
      document.dispatchEvent(new KeyboardEvent('keyup', ev));
    } catch (e) {}
    return true;
  };

  // ------------------------------------------------ 「+」原生附件面板
  // 官方 App 的附件入口是一个「+」，点开有 拍照 / 相册 / 文件 并在下方直接给最近照片。
  // 这里把页面的回形针图标换成「+」，点击交给原生 sheet；选完的文件走 __deepseekTakeStaged 回灌。
  function findChatFileInput() {
    var inputs = document.querySelectorAll('input[type="file"]');
    for (var i = 0; i < inputs.length; i++) {
      var accept = inputs[i].getAttribute('accept') || '';
      if (inputs[i].multiple && accept.indexOf('.zip') === -1) return inputs[i];
    }
    return null;
  }

  function injectStyle() {
    if (document.getElementById('__ds_shell_style')) return;
    var st = document.createElement('style');
    st.id = '__ds_shell_style';
    st.textContent =
      'button[aria-label="上传文件"]>svg,button[aria-label="Upload files"]>svg{display:none!important}' +
      'button[aria-label="上传文件"]::after,button[aria-label="Upload files"]::after{' +
      'content:"+";font-size:22px;line-height:1;font-weight:300;display:block;margin-top:-2px}' +
      'button[aria-label="上传文件"],button[aria-label="Upload files"]{' +
      'display:inline-flex;align-items:center;justify-content:center}';
    (document.head || document.documentElement).appendChild(st);
  }
  document.addEventListener('DOMContentLoaded', injectStyle);
  if (document.head) injectStyle();

  function isUploadBtn(el) {
    if (!el || !el.closest) return null;
    return el.closest('button[aria-label="上传文件"],button[aria-label="Upload files"]');
  }

  document.addEventListener('click', function (e) {
    var btn = isUploadBtn(e.target);
    if (!btn) return;
    if (!findChatFileInput()) return; // 不是聊天输入区的上传入口就放行
    e.preventDefault();
    e.stopPropagation();
    safe(function () { native().openAttachmentSheet(); });
  }, true);

  // 原生面板选完后调用：把暂存字节取回，构造成 File 塞进页面的 input，走页面自己的上传流程
  window.__deepseekTakeStaged = function () {
    var list;
    try {
      list = JSON.parse(native().takeStaged() || '[]');
    } catch (e) {
      return;
    }
    if (!list || !list.length) return;
    var input = findChatFileInput();
    if (!input) {
      safe(function () { native().toast('找不到上传入口'); });
      return;
    }
    Promise.all(
      list.map(function (f) {
        return fetch('/__file/' + f.token)
          .then(function (r) {
            if (!r.ok) throw new Error('__file ' + r.status);
            return r.blob();
          })
          .then(function (b) { return new File([b], f.name, { type: f.mime || b.type }); });
      })
    )
      .then(function (files) {
        try {
          var dt = new DataTransfer();
          files.forEach(function (file) { dt.items.add(file); });
          input.files = dt.files;
          input.dispatchEvent(new Event('input', { bubbles: true }));
          input.dispatchEvent(new Event('change', { bubbles: true }));
        } catch (err) {
          safe(function () { native().log('inject files failed: ' + err); });
        }
      })
      .catch(function (err) {
        safe(function () { native().log('take staged failed: ' + err); });
      });
  };

  // ------------------------------------------------ 语音输入：按住说话
  // 页面自己的麦克风按钮是「点一下开始 / 再点一下结束」，这里把按下-抬起映射成这两次点击，
  // 于是长按即录、松手即停，录音界面（含频谱）还是页面自己的。
  var mic = { active: false, synthetic: false, btn: null, at: 0 };

  function micButtonOf(el) {
    if (!el || !el.closest) return null;
    return el.closest('button[aria-label="语音输入"],button[aria-label="Voice input"]');
  }

  function synthClick(btn) {
    if (!btn) return;
    mic.synthetic = true;
    try {
      btn.click();
    } finally {
      mic.synthetic = false;
    }
  }

  document.addEventListener('pointerdown', function (e) {
    var btn = micButtonOf(e.target);
    if (!btn || mic.active) return;
    if (btn.disabled || btn.getAttribute('aria-disabled') === 'true') return;
    mic.active = true;
    mic.btn = btn;
    mic.at = Date.now();
    synthClick(btn);
  }, true);

  function stopRecording() {
    if (!mic.active) return;
    mic.active = false;
    var held = Date.now() - mic.at;
    synthClick(mic.btn || micButtonOf(document.activeElement));
    mic.btn = null;
    if (held < 400) safe(function () { native().toast('按住说话，松手结束'); });
  }

  document.addEventListener('pointerup', stopRecording, true);
  document.addEventListener('pointercancel', stopRecording, true);
  document.addEventListener('click', function (e) {
    if (!micButtonOf(e.target)) return;
    if (mic.synthetic) return; // 我们自己合成的点击，放行给页面
    // 真实点击（pointerup 之后浏览器补发的那次）拦掉，避免立刻把录音又停/又开
    e.preventDefault();
    e.stopPropagation();
  }, true);

  // ------------------------------------------------ 附件卡片：× 常驻 + 点图放大/裁剪
  function injectChipStyle() {
    if (document.getElementById('__ds_chip_style')) return;
    var st = document.createElement('style');
    st.id = '__ds_chip_style';
    st.textContent =
      // 页面把删除按钮做成 hover 才出现，手机上永远 hover 不到 → 只对附件卡片强制常驻
      // 实测 DOM: <div class="attachment-xxxx" title="文件名"><img><button class="remove-xxxx" aria-label="删除">
      'div[class^="attachment-"]>button[class^="remove-"],' +
      'div[class^="attachment-"]>button[aria-label="删除"],' +
      'div[class^="attachment-"]>button[aria-label="Delete"]{' +
      'opacity:1!important;visibility:visible!important;pointer-events:auto!important;transform:none!important}' +
      // 附件缩略图给出可点暗示
      'img[src^="blob:"]{cursor:zoom-in}';
    (document.head || document.documentElement).appendChild(st);
  }
  document.addEventListener('DOMContentLoaded', injectChipStyle);
  if (document.head) injectChipStyle();

  function findChipByName(name) {
    if (!name) return null;
    var nodes = document.querySelectorAll('[title]');
    for (var i = 0; i < nodes.length; i++) {
      if (nodes[i].getAttribute('title') === name && nodes[i].querySelector('img')) return nodes[i];
    }
    return null;
  }

  document.addEventListener('click', function (e) {
    var img = e.target && e.target.closest ? e.target.closest('img[src^="blob:"]') : null;
    if (!img) return;
    var chip = img.closest('[title]');
    var name = chip && chip.getAttribute('title');
    if (!name) return;
    e.preventDefault();
    e.stopPropagation();
    // 诊断用：把 chip 的真实结构打给 logcat，方便校准 CSS 选择器
    safe(function () {
      var host = img.parentElement;
      native().log('CHIPHTML ' + (host ? host.outerHTML.slice(0, 900) : 'none'));
    });
    safe(function () { native().openAttachmentPreview(name); });
  }, true);

  // 裁剪完成后由原生调用：删掉旧附件卡片，再把新图注入回去
  window.__deepseekReplaceAttachment = function (oldName) {
    try {
      var chip = findChipByName(oldName);
      if (chip) {
        var del = chip.querySelector('button[aria-label="删除"],button[aria-label="Delete"]');
        if (del) del.click();
        else if (chip.parentElement) chip.parentElement.removeChild(chip);
      }
    } catch (e) {
      safe(function () { native().log('replace remove failed: ' + e); });
    }
    setTimeout(function () {
      if (window.__deepseekTakeStaged) window.__deepseekTakeStaged();
    }, 320);
  };

  // ------------------------------------------------ 「跟随系统」深色模式
  // 页面用 matchMedia('(prefers-color-scheme: dark)') 判断系统深浅色，但 WebView 里这个
  // 媒体查询常年在 light（本机实测：系统切深色后页面仍全白）。这里把该查询接管到原生的
  // Configuration.uiMode，并在系统切换时补发 change 事件，让页面自己重算主题。
  (function () {
    var realMatchMedia = window.matchMedia.bind(window);
    var tracked = [];

    function sysDark() {
      try {
        return native() ? !!native().systemDark() : null;
      } catch (e) {
        return null;
      }
    }

    function fakeMql(query, wantsDark) {
      var listeners = [];
      var api = {
        media: query,
        onchange: null,
        addListener: function (fn) { listeners.push(fn); },
        removeListener: function (fn) {
          var i = listeners.indexOf(fn);
          if (i >= 0) listeners.splice(i, 1);
        },
        addEventListener: function (type, fn) {
          if (type === 'change') listeners.push(fn);
        },
        removeEventListener: function (type, fn) {
          if (type !== 'change') return;
          var i = listeners.indexOf(fn);
          if (i >= 0) listeners.splice(i, 1);
        },
        dispatchEvent: function (ev) {
          listeners.slice().forEach(function (fn) {
            try { fn(ev); } catch (err) {}
          });
          return true;
        }
      };
      Object.defineProperty(api, 'matches', {
        get: function () {
          var d = sysDark();
          return d === null ? realMatchMedia(query).matches : d === wantsDark;
        }
      });
      tracked.push({ api: api, query: query });
      return api;
    }

    window.matchMedia = function (query) {
      if (typeof query === 'string' && query.indexOf('prefers-color-scheme') >= 0) {
        return fakeMql(query, query.indexOf('dark') >= 0);
      }
      return realMatchMedia(query);
    };

    window.__deepseekSystemThemeChanged = function () {
      tracked.forEach(function (t) {
        var ev = { matches: t.api.matches, media: t.api.query, type: 'change' };
        if (typeof t.api.onchange === 'function') {
          try { t.api.onchange(ev); } catch (e) {}
        }
        t.api.dispatchEvent(ev);
      });
    };
  })();
})();
