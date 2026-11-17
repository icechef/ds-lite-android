/*
 * Token 用量统计（注入到页面上下文的第二个脚本，由 MainActivity.rewriteIndex 在 shim.js 之后插入）。
 *
 * 为什么要在页面里拦截 fetch：
 *   页面自己其实拿到了 usage（message_start / message_delta 里都有），但它写 IndexedDB 时
 *   把 usage 丢掉了（真机核对：assistant 消息只存 id/role/createdAt/model/... 没有 usage），
 *   内存里的 store 又不在 JS 可达范围。所以只能旁路嗅探它发出的那次请求。
 *
 * 口径（据 api-docs.deepseek.com/api/create-message 与 guides/anthropic_api）：
 *   - 以【最后一个 message_delta.usage】为准，不要和 message_start 相加
 *     （实测同一请求 message_start 报 input=99、最终 message_delta 报 input=1061）；
 *   - input_tokens = 缓存未命中，cache_read_input_tokens = 缓存命中，
 *     cache_creation_input_tokens 恒为 0；总输入 = input + cache_read + cache_creation；
 *   - output_tokens 已包含思考 token（明细在 output_tokens_details.thinking_tokens / safety_tokens）；
 *   - server_tool_use.* 是联网工具次数（tool_search / tool_fetch / tool_image_search 是计费基数）。
 *
 * 会话归集：标题不可靠（两个会话可能同名），所以用 App 自己的 IndexedDB「sessionSummaries」
 * （{id,title,createdAt,updatedAt}，很轻）把每次请求挂到会话 id 上。
 *
 * 只统计本 App 内的请求：别的设备、网页版、直接调 API 都不在这里。
 */
(function () {
  if (window.__dsUsage) return;
  window.__dsUsage = true;

  var KEY = 'deepseek-usage:v1';
  var MAX_RECORDS = 1000;
  // 页面会打的四类计费端点（真机抓的）：
  //   /anthropic/v1/messages  对话（token 计费，含思考/安全/搜索）
  //   /anthropic/v1/files     上传附件（服务端解析按页计费，页数拿不到 → 只记次数）
  //   /v1/tools/asr           语音输入（按音频时长计费）
  //   /v1/tools/tts           朗读（按字符计费）
  var CHAT_RE = /\/anthropic\/v1\/messages|\/chat\/completions/;
  var FILES_RE = /\/anthropic\/v1\/files(\?|$)/;
  var ASR_RE = /\/v1\/tools\/asr(\?|$)/;
  var TTS_RE = /\/v1\/tools\/tts(\?|$)/;
  var BTN_ID = '__dsu_btn';
  var PANEL_ID = '__dsu_panel';
  var STYLE_ID = '__dsu_style';

  // 官方价目（人民币 / 百万 token）。空闲价 = 高峰价一半。
  // 来源：api-docs.deepseek.com/zh-cn/quick_start/pricing
  var PRICES = {
    flash: { hit: { off: 0.02, peak: 0.04 }, miss: { off: 1, peak: 2 }, out: { off: 4, peak: 8 } },
    pro: { hit: { off: 0.15, peak: 0.3 }, miss: { off: 4.5, peak: 9 }, out: { off: 13.5, peak: 27 } }
  };
  var SEARCH_YUAN = 5 / 1000;   // 搜索（文字/图片）/ 网页抓取：5 元每 1000 次
  var ASR_PER_HOUR = 0.39;      // 语音识别：刊例 0.78 元/小时，当前限时 5 折
  var TTS_PER_CHAR = 1.5 / 10000; // 语音合成：刊例 3 元/万字符，当前限时 5 折

  // ---------------------------------------------------------------- 视觉 token
  //
  // API 的 usage 不单列图片 token（图片按尺寸折算后并进 input_tokens），所以这里用
  // DeepSeek 官方「图片 Token 计算器」里那份算法（v41 规则）自己算：
  //   patchSize=14, downsampleRatio=3, maxNToken=1024, minPixels=295936(≈544²)
  //   numTokens = nW × (nH + 1) + 2
  // 实测校准：1600×1600 → 994（真实增量 994）、300×300 → 184（真实增量 184）。
  var IMG = { patch: 14, ds: 3, maxN: 1024, minPixels: 295936 };

  function imgNumTokens(nW, nH) {
    // 官方 calcNumTokens(nLlmH, nLlmW) = nLlmH × (nLlmW + 1) + 2（注意是 高×(宽+1)）
    return nH * (nW + 1) + 2;
  }

  /** padded 像素 → 网格数（patch 14 再按 3:1 下采样） */
  function imgGrid(padded) {
    return Math.ceil(Math.floor(padded / IMG.patch) / IMG.ds);
  }

  /** 官方 solveResizeRatio：在 maxN 上限内找最贴合的 nLlmW / nLlmH（w 宽、h 高） */
  function imgSolveResize(w, h, maxN) {
    var ratio = h / w;                            // 官方 n = 高/宽
    var i = Math.sqrt((maxN - 2) / ratio + 0.25) - 0.5; // 高方向的格数
    var a = i * ratio;                                  // 宽方向的格数
    var bw, bh;
    if (i < 1) {
      var e1 = 1;
      var t1 = Math.floor((maxN - 2) / (e1 + 1));
      bw = e1 * IMG.patch * IMG.ds;
      bh = t1 * IMG.patch * IMG.ds;
    } else if (a < 1) {
      var e2 = 1;
      var t2 = Math.floor((maxN - 2) / (e2 + 1)) - 1;
      bw = t2 * IMG.patch * IMG.ds;
      bh = e2 * IMG.patch * IMG.ds;
    } else {
      var sh = Math.trunc(i);
      var sw = Math.trunc(a);
      var u = Math.min(sh * IMG.patch * IMG.ds / h, sw * IMG.patch * IMG.ds / w);
      bw = Math.trunc(w * u / IMG.patch) * IMG.patch;
      bh = Math.trunc(h * u / IMG.patch) * IMG.patch;
    }
    return imgNumTokens(imgGrid(bw), imgGrid(bh));
  }

  /** 一张 w×h 的图片折合多少 token（估算，与官方图片 Token 计算器同算法） */
  function imgTokens(w, h) {
    w = Math.round(w || 0);
    h = Math.round(h || 0);
    if (!(w > 0 && h > 0)) return 0;
    var area = w * h;
    if (area < IMG.minPixels) {  // 太小会被等比放大到约 544×544
      var k = Math.sqrt(IMG.minPixels / area);
      w = Math.trunc(w * k);
      h = Math.trunc(h * k);
    }
    function pad(v) { return Math.ceil(v / IMG.patch) * IMG.patch; }
    var t = imgNumTokens(imgGrid(pad(w)), imgGrid(pad(h)));
    if (t <= IMG.maxN) return t;
    return imgSolveResize(w, h, IMG.maxN);
  }

  function log(msg) {
    try { if (window.AndroidApp && window.AndroidApp.log) window.AndroidApp.log('usage: ' + msg); } catch (e) {}
    try { console.log('[usage] ' + msg); } catch (e) {}
    // 调试环：logcat 在部分机型上会被刷掉，页面里留一份最近 60 条供 CDP 查
    try {
      var ring = window.__dsuDebug || (window.__dsuDebug = []);
      ring.push(Date.now() % 100000 + ' ' + msg);
      if (ring.length > 60) ring.shift();
    } catch (e) {}
  }

  // ------------------------------------------------------------------ 价格 / 费用

  function priceTable(model) {
    var m = String(model || '').toLowerCase();
    if (m.indexOf('pro') >= 0) return PRICES.pro;
    if (m.indexOf('flash') >= 0) return PRICES.flash;
    return null;
  }

  /** 北京时间周一至周五 9:00-12:00、14:00-18:00 为高峰；无节假日表，属估算误差。 */
  function isPeak(ts) {
    var d = new Date(ts + 8 * 3600 * 1000);
    var day = d.getUTCDay();
    if (day === 0 || day === 6) return false;
    var h = d.getUTCHours() + d.getUTCMinutes() / 60;
    return (h >= 9 && h < 12) || (h >= 14 && h < 18);
  }

  /** 一次请求的估算费用（元）；模型不认识时返回 null（显示「—」而不是瞎估）。 */
  function costOf(r) {
    var t = priceTable(r.m);
    if (!t) return null;
    var peak = isPeak(r.t);
    function u(pair) { return peak ? pair.peak : pair.off; }
    var c = 0;
    c += (r.i || 0) * u(t.miss);
    c += (r.cr || 0) * u(t.hit);
    c += (r.o || 0) * u(t.out);
    c = c / 1000000;
    c += (r.sb || 0) * SEARCH_YUAN;
    return c;
  }

  /**
   * 按【峰 / 谷两个桶】分别累加真实用量：未命中输入 / 缓存输入 / 输出（含思考·安全）/
   * 视觉（图片 token，已含在输入里）/ 搜索 / 语音识别 / 朗读 / 文件。
   * 每个数都是逐条请求累加出来的，不是拿合计乘单价；同一个请求整条归入它的峰谷档。
   * since 可选：只统计 t >= since 的记录（今日 / 近 7 天用）。
   */
  function blankBucket() {
    return {
      n: 0, cost: 0,
      miss: 0, missCost: 0,
      hit: 0, hitCost: 0,
      out: 0, outCost: 0,
      think: 0, safety: 0,
      vis: 0, visN: 0,          // vis = 图片 token 合计（已计入输入）；visN = 估不出的图片张数
      visCost: 0, visMissCost: 0, // 视觉 token 折算的费用（和输入同价），从输入费用里切出来
      search: 0, searchCost: 0,
      asrN: 0, asrSec: 0, asrCost: 0,
      ttsN: 0, ttsChars: 0, ttsCost: 0,
      fileN: 0, imgN: 0, imgTok: 0,
      unknown: 0,               // 价目表里没有的模型（未计费）
      tab: null                 // 该档用的价目表
    };
  }

  function tally(recs, since) {
    var res = { peak: blankBucket(), off: blankBucket() };
    for (var i = 0; i < recs.length; i++) {
      var r = recs[i];
      if (!r.ok) continue;
      if (since && r.t < since) continue;
      var peak = isPeak(r.t);
      var b = res[peak ? 'peak' : 'off'];
      if (r.kind === 'asr') {
        b.asrN += 1;
        b.asrSec += r.sec || 0;
        b.asrCost += r.c || 0;
        b.cost += r.c || 0;
        continue;
      }
      if (r.kind === 'tts') {
        b.ttsN += 1;
        b.ttsChars += r.chars || 0;
        b.ttsCost += r.c || 0;
        b.cost += r.c || 0;
        continue;
      }
      if (r.kind === 'file') { b.fileN += 1; continue; }
      if (r.kind === 'image') {
        // 图片不单独计费：它的 token 由模型并进 input_tokens（记录里的 r.vis 就是这部分）
        b.imgN += 1;
        b.imgTok += r.tok || 0;
        continue;
      }
      b.n += 1;
      b.miss += r.i || 0;
      b.hit += r.cr || 0;
      b.out += r.o || 0;
      b.think += r.th || 0;
      b.safety += r.sf || 0;
      b.search += r.sb || 0;
      var visCapped = 0;
      if (r.vis != null) {
        visCapped = Math.min(r.vis, (r.i || 0) + (r.cr || 0));
        b.vis += visCapped;
      } else if (r.imgReq) b.visN += r.imgReq;   // 带图但视觉 token 估不出来（旧记录/取不到尺寸）
      b.cost += r.c == null ? 0 : r.c;
      var t = priceTable(r.m);
      if (!t) { b.unknown += 1; continue; }
      b.tab = t;
      var missP = peak ? t.miss.peak : t.miss.off;
      var hitP = peak ? t.hit.peak : t.hit.off;
      b.missCost += (r.i || 0) * missP / 1000000;
      b.hitCost += (r.cr || 0) * hitP / 1000000;
      b.outCost += (r.o || 0) * (peak ? t.out.peak : t.out.off) / 1000000;
      b.searchCost += (r.sb || 0) * SEARCH_YUAN;
      if (visCapped) {
        // 视觉 token 与文本输入同价：按「先扣未命中、再扣缓存」拆出它自己那份费用
        var vm = Math.min(visCapped, r.i || 0);
        var vh = visCapped - vm;
        b.visMissCost += vm * missP / 1000000;
        b.visCost += vm * missP / 1000000 + vh * hitP / 1000000;
      }
    }
    return res;
  }

  var BUCKET_NUM = ['n', 'cost', 'miss', 'missCost', 'hit', 'hitCost', 'out', 'outCost',
    'think', 'safety', 'vis', 'visN', 'visCost', 'visMissCost', 'search', 'searchCost',
    'asrN', 'asrSec', 'asrCost', 'ttsN', 'ttsChars', 'ttsCost',
    'fileN', 'imgN', 'imgTok', 'unknown'];

  /** 峰 + 谷 = 合计桶（tab 取有人用过的那张价目表） */
  function addBuckets(a, b) {
    var t = blankBucket();
    for (var i = 0; i < BUCKET_NUM.length; i++) {
      var k = BUCKET_NUM[i];
      t[k] = (a[k] || 0) + (b[k] || 0);
    }
    t.tab = a.tab || b.tab;
    return t;
  }

  function totalOf(t) { return addBuckets(t.peak, t.off); }

  /** 文本输入 = 输入 − 视觉 token（先从未命中里扣，不够再扣缓存部分） */
  function textInput(b) {
    var v = b.vis || 0;
    var m = Math.max(0, b.miss - Math.min(v, b.miss));
    var h = Math.max(0, b.hit - Math.max(0, v - b.miss));
    return { miss: m, hit: h };
  }

  /** 附件上传接口的响应里取文件 id（不同端点字段名不同，尽量兼容） */
  function fileIdOf(j) {
    if (!j || typeof j !== 'object') return '';
    var v = j.id || j.file_id || j.fileId;
    if (!v && j.data && typeof j.data === 'object') v = j.data.id || j.data.file_id || j.data.fileId;
    return v ? String(v) : '';
  }

  /** 附件 / 语音这类「非对话」的计费事件（没有 token，按次/时长/字符算）。 */
  function eventCost(ev) {
    if (ev.kind === 'asr') return (ev.sec || 0) / 3600 * ASR_PER_HOUR;
    if (ev.kind === 'tts') return (ev.chars || 0) * TTS_PER_CHAR;
    return 0; // 文件解析按页计费，页数拿不到 → 不计费、只记次数
  }

  function addEvent(ev) {
    ev.t = ev.t || Date.now();
    ev.ok = 1;
    ev.c = eventCost(ev);
    ev.title = currentTitle();
    ev.sid = state.sid || '';
    addRecord(ev);
  }

  // ------------------------------------------------------------------ 存取

  function emptyStore() {
    return { records: [], totals: { n: 0, i: 0, cr: 0, o: 0, c: 0 } };
  }

  function load() {
    try {
      var raw = localStorage.getItem(KEY);
      if (!raw) return emptyStore();
      var d = JSON.parse(raw);
      if (!d || !d.records) return emptyStore();
      if (!d.totals) d.totals = { n: 0, i: 0, cr: 0, o: 0, c: 0 };
      // 兼容旧记录：早期版本把附件/语音事件的费用覆盖成了 null，读出来时补算（并补进累计）
      var patched = 0;
      for (var i = 0; i < d.records.length; i++) {
        var r = d.records[i];
        if (r && r.kind && r.c == null) {
          r.c = eventCost(r);
          patched += r.c || 0;
        }
      }
      if (patched) {
        d.totals.c += patched;
        save(d);
      }
      return d;
    } catch (e) {
      log('load failed: ' + e);
      return emptyStore();
    }
  }

  function save(d) {
    try {
      localStorage.setItem(KEY, JSON.stringify(d));
    } catch (e) {
      log('save failed: ' + e);
    }
  }

  function addRecord(rec) {
    // 中断的请求拿不到 message_delta 的最终 usage（message_start 那份只是本轮新增量，
    // 官方也明确说不要拿它当用量），所以这类记录不参与任何合计、也不算费用
    if (!rec.kind) rec.c = rec.ok ? costOf(rec) : null; // 附件/语音事件的费用在 addEvent 里算好了
    var d = load();
    d.records.push(rec);
    while (d.records.length > MAX_RECORDS) d.records.shift();
    var T = d.totals;
    // 中断的请求（没收到 message_stop）数字不完整，只进列表、不进合计
    if (rec.ok) {
      if (!rec.kind) { // 只有对话请求算「次请求 / token」，附件与语音只贡献费用
        T.n += 1;
        T.i += rec.i || 0;
        T.cr += rec.cr || 0;
        T.o += rec.o || 0;
      }
      T.c += rec.c == null ? 0 : rec.c;
    }
    save(d);
    log('record ' + JSON.stringify(rec));
    repaint();
    // 会话 id 要读 IndexedDB，异步补写（补上之后「本会话」口径才准）
    attachSessionId(rec);
  }

  // ------------------------------------------------------------------ 会话归集

  var state = { sessions: null, title: '', sid: '' };

  /** 读 App 自己的会话摘要（只有 id/title/时间，很轻），失败或超时就返回空数组。 */
  function readSessionSummaries() {
    return new Promise(function (resolve) {
      var done = false;
      function finish(v) {
        if (done) return;
        done = true;
        resolve(v);
      }
      setTimeout(function () { finish([]); }, 1500); // 别让按钮/面板卡在 IndexedDB 上
      try {
        var req = indexedDB.open('chat-demo');
        req.onerror = function () { finish([]); };
        req.onsuccess = function () {
          var db = req.result;
          try {
            var q = db.transaction('sessionSummaries', 'readonly').objectStore('sessionSummaries').getAll();
            q.onsuccess = function () { finish(q.result || []); };
            q.onerror = function () { finish([]); };
          } catch (e) {
            finish([]);
          }
        };
      } catch (e) {
        finish([]);
      }
    });
  }

  function currentTitle() {
    var node = document.querySelector('div[class^="title-"]');
    return node ? String(node.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 60) : '';
  }

  /** 用标题找会话 id：同名会话取 updatedAt 最新的那个（刚说过话的就是它）。 */
  function resolveSid(title, sessions) {
    if (!title || !sessions || !sessions.length) return '';
    var best = null;
    for (var i = 0; i < sessions.length; i++) {
      var s = sessions[i];
      if ((s.title || '') !== title) continue;
      if (!best || (s.updatedAt || 0) > (best.updatedAt || 0)) best = s;
    }
    return best ? best.id : '';
  }

  function refreshSessions() {
    return readSessionSummaries().then(function (list) {
      state.sessions = list;
      state.title = currentTitle();
      state.sid = resolveSid(state.title, list);
      repaint();
      return list;
    });
  }

  /** 给刚写完的记录补 sessionId；顺便把「刚上传、还没归属」的图片/文档事件认领到这条会话里
   *  （新会话首轮时，附件上传发生在标题生成之前，那时还没有 title/sid 可挂）。 */
  function attachSessionId(rec) {
    var title = rec.title;
    readSessionSummaries().then(function (list) {
      var sid = resolveSid(title, list);
      var d = load();
      var dirty = false;
      if (sid) {
        for (var i = d.records.length - 1; i >= 0; i--) {
          if (d.records[i].t === rec.t && !d.records[i].kind) {
            d.records[i].sid = sid;
            dirty = true;
            break;
          }
        }
      }
      // 认领这条请求实际用到的附件事件（请求体里 image/document 块的数量 = 应认领的个数）：
      // 取「最近上传且还没归属」的那些，避免把别的会话刚上传的图片算进来
      var visClaimed = 0;
      var pick = function (kind, need) {
        if (!need) return;
        var cands = [];
        for (var j = 0; j < d.records.length; j++) {
          var r = d.records[j];
          if (r.kind !== kind || r.title || r.sid) continue;
          if (r.t > rec.t || r.t < rec.t - 600000) continue;
          cands.push(r);
        }
        cands.sort(function (a, b) { return b.t - a.t; });
        for (var k = 0; k < Math.min(need, cands.length); k++) {
          cands[k].title = title;
          cands[k].sid = sid || '';
          cands[k].reqT = rec.t;
          if (kind === 'image') visClaimed += cands[k].tok || 0;
          dirty = true;
        }
      };
      pick('image', rec.imgReq);
      pick('file', rec.docReq);
      // 兜底：上传时没拿到 file_id（旧版本记录 / 响应解析失败）的图片，没法按 file_id 反查，
      // 就用刚认领到的这张图的视觉 token 补到请求记录上（只在匹配到时补，不硬凑）
      if (visClaimed) {
        for (var k2 = d.records.length - 1; k2 >= 0; k2--) {
          var cr2 = d.records[k2];
          if (cr2.t === rec.t && !cr2.kind) {
            if (!cr2.vis) { cr2.vis = visClaimed; dirty = true; }
            break;
          }
        }
      }
      if (dirty) save(d);
      log('session attach: ' + (sid ? sid.slice(0, 8) : '(no sid)') + ' -> ' + title);
      refreshSessions();
    });
  }

  /** 当前会话范围内的完整请求（优先会话 id，没有就退回标题精确匹配）。 */
  function scopedRecords(recs) {
    var sid = state.sid;
    var title = state.title || currentTitle();
    var out = [];
    for (var i = 0; i < recs.length; i++) {
      var r = recs[i];
      if (!r.ok) continue;
      if (sid ? (r.sid === sid || (!r.sid && r.title === title)) : (title && r.title === title)) {
        out.push(r);
      }
    }
    return out;
  }

  // ------------------------------------------------------------------ 格式化

  function fmt(n) {
    return String(Math.round(n || 0)).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  }

  function yuan(v) {
    if (v == null) return '—';
    if (v === 0) return '¥0';
    // 小数位留够，保证「逐条相加 = 合计」（否则 0.00135 显示成 0.0014 就对不上账）
    if (v < 0.000001) return '<¥0.000001';
    if (v < 0.0001) return '¥' + v.toFixed(6);
    if (v < 0.01) return '¥' + v.toFixed(5);
    if (v < 1) return '¥' + v.toFixed(4);
    return '¥' + v.toFixed(2);
  }

  function pad(n) { return (n < 10 ? '0' : '') + n; }

  function hhmm(ts) {
    var d = new Date(ts);
    return pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
  }

  function tierText(ts) {
    return isPeak(ts) ? '高峰' : '空闲';
  }

  /** 「输入 68 · 视觉 994 · 输出 130 · ¥0.00158」；附件/语音事件按各自口径显示 */
  function metricLine(r) {
    if (!r.ok) return '请求中断 · 未计入合计';
    if (r.kind === 'asr') return '语音识别 ' + (r.sec || 0).toFixed(1) + 's · ' + yuan(r.c);
    if (r.kind === 'tts') return '朗读 ' + fmt(r.chars || 0) + ' 字 · ' + yuan(r.c);
    if (r.kind === 'image') {
      return '图片 ' + (r.w && r.h ? r.w + '×' + r.h + ' ' : '') + '≈ ' + fmt(r.tok || 0) +
        ' 视觉 token · 已计入当轮请求的输入';
    }
    if (r.kind === 'file') {
      return '文档 ' + (r.name ? r.name + ' · ' : '') + (r.bytes ? fmt(Math.round(r.bytes / 1024)) + ' KB · ' : '') + '解析按页计费未计入';
    }
    var vis = r.vis || 0;
    var s = '输入 ' + fmt(Math.max(0, (r.i || 0) - vis));
    if (vis) s += ' · 视觉 ' + fmt(vis);
    if (r.cr) s += ' · 缓存 ' + fmt(r.cr);
    s += ' · 输出 ' + fmt(r.o);
    if (r.sb) s += ' · 搜索 ' + fmt(r.sb) + ' 次';
    s += ' · ' + yuan(r.c);
    return s;
  }

  /** 某个时间窗口的合计桶（峰 + 谷）；since 省略 = 全部记录 */
  function scopeTotals(recs, since) {
    return totalOf(tally(recs, since));
  }

  /** 记录里的 token 总量（输入含视觉 + 输出），用于画峰谷比例条 */
  function tokenSum(b) {
    return (b.miss || 0) + (b.hit || 0) + (b.out || 0);
  }

  function startOfToday() {
    var d = new Date();
    d.setHours(0, 0, 0, 0);
    return d.getTime();
  }

  // ------------------------------------------------------------------ SSE 嗅探

  function num(v) { return typeof v === 'number' && isFinite(v) ? v : 0; }

  function pickUsage(u) {
    if (!u || typeof u !== 'object') return null;
    var d = u.output_tokens_details || {};
    var st = u.server_tool_use || {};
    return {
      i: num(u.input_tokens),
      cr: num(u.cache_read_input_tokens),
      cc: num(u.cache_creation_input_tokens),
      o: num(u.output_tokens),
      th: num(d.thinking_tokens),
      sf: num(d.safety_tokens),
      ws: num(st.web_search_requests),
      sb: num(st.tool_search) + num(st.tool_image_search) + num(st.tool_fetch)
    };
  }

  /** 本轮请求引用的 file_id → 已记录的图片视觉 token 之和（同一张图出现多次就计多次） */
  function visionTokensFor(fids) {
    if (!fids || !fids.length) return 0;
    var d = load();
    var byFid = {};
    for (var i = 0; i < d.records.length; i++) {
      var r = d.records[i];
      if (r.kind === 'image' && r.fid) byFid[r.fid] = r.tok || 0;
    }
    var sum = 0;
    for (var j = 0; j < fids.length; j++) sum += byFid[fids[j]] || 0;
    return sum;
  }

  function finish(usage, meta, model, startedAt, imgReq, docReq, fids) {
    if (!usage) {
      log('no usage parsed (model=' + (model || '?') + ')');
      return;
    }
    var vis = visionTokensFor(fids);
    var rec = {
      t: startedAt,
      m: meta.model || model || '',
      i: usage.i,
      cr: usage.cr,
      cc: usage.cc,
      o: usage.o,
      th: usage.th,
      sf: usage.sf,
      ws: usage.ws,
      sb: usage.sb,
      ms: Date.now() - startedAt,
      ok: meta.stopped ? 1 : 0,
      imgReq: imgReq || 0,
      docReq: docReq || 0,
      title: currentTitle()
    };
    // vis 只在真的匹配到图片时才写：为 0 说明这张图是我们开始统计前传的（尺寸未知）
    if (vis) rec.vis = vis;
    addRecord(rec);
  }

  /** 解析一份 SSE 流：只关心 usage，不碰页面自己的那份流。 */
  function readStream(res, model, startedAt, done) {
    var reader = res.body.getReader();
    var dec = new TextDecoder();
    var buf = '';
    var ev = '';
    var data = [];
    var usage = null;
    var stopped = false;
    var seenModel = model || '';
    var startInput = -1;

    function dispatch() {
      var payload = data.join('\n');
      var evName = ev;
      ev = '';
      data = [];
      if (!payload) return;
      var obj;
      try {
        obj = JSON.parse(payload);
      } catch (e) {
        return;
      }
      var type = obj && obj.type ? obj.type : evName;
      if (type === 'message_start') {
        if (obj.message && obj.message.model) seenModel = obj.message.model;
        var u0 = pickUsage(obj.message && obj.message.usage);
        // message_start 那份只是本轮新增量，只作兜底（中断的记录会被标未完成、不进合计）
        if (u0) {
          if (startInput < 0) startInput = u0.i;
          if (!usage) usage = u0;
        }
      } else if (type === 'message_delta') {
        var u1 = pickUsage(obj.usage); // 累计值，以最后一个为准
        if (u1) usage = u1;
      } else if (type === 'message_stop') {
        stopped = true;
      } else if (obj && obj.usage) {
        // chat/completions 风格兜底：末尾一块带 usage
        var u2 = pickUsage(obj.usage);
        if (u2) usage = u2;
        if (Array.isArray(obj.choices) && obj.choices.length === 0) stopped = true;
      }
    }

    function handleLine(line) {
      if (line === '') {
        dispatch();
        return;
      }
      if (line.charAt(0) === ':') return; // 注释 / keep-alive
      var idx = line.indexOf(':');
      var field = idx < 0 ? line : line.slice(0, idx);
      var value = idx < 0 ? '' : line.slice(idx + 1);
      if (value.charAt(0) === ' ') value = value.slice(1);
      if (field === 'event') ev = value;
      else if (field === 'data') data.push(value);
    }

    function flushTail() {
      buf += dec.decode();
      if (buf) {
        buf.split('\n').forEach(function (line) {
          handleLine(line.replace(/\r$/, ''));
        });
        buf = '';
        dispatch();
      }
    }

    function pump() {
      reader.read().then(function (r) {
        if (r.done) {
          flushTail();
          if (startInput >= 0 && usage) log('usage start=' + startInput + ' final=' + usage.i);
          done(usage, { stopped: stopped, model: seenModel }, seenModel, startedAt);
          return;
        }
        buf += dec.decode(r.value, { stream: true });
        var nl;
        while ((nl = buf.indexOf('\n')) >= 0) {
          var line = buf.slice(0, nl);
          buf = buf.slice(nl + 1);
          handleLine(line.replace(/\r$/, ''));
        }
        pump();
      }).catch(function (e) {
        // 用户中途停止生成时流会被 abort：已拿到的 usage 是半截数，标「未完成」
        log('stream read end: ' + e + ' (start=' + startInput + ')');
        done(usage, { stopped: false, model: seenModel }, seenModel, startedAt);
      });
    }

    pump();
  }

  function watch(res, model, startedAt, imgReq, docReq, fids) {
    if (!res || !res.ok) return;
    var clone;
    try {
      clone = res.clone();
    } catch (e) {
      log('clone failed: ' + e);
      return;
    }
    var ctype = '';
    try { ctype = String(res.headers.get('content-type') || '').toLowerCase(); } catch (e) {}

    if (ctype.indexOf('text/event-stream') < 0) {
      // 非流式（正常不会走到这里）：整个 body 就是一条 message
      clone.text().then(function (txt) {
        var obj;
        try { obj = JSON.parse(txt); } catch (e) { return; }
        var u = pickUsage(obj && obj.usage);
        if (u) finish(u, { stopped: true, model: (obj && obj.model) || model }, model, startedAt, imgReq, docReq, fids);
      }).catch(function () {});
      return;
    }

    if (!clone.body || !clone.body.getReader) return;
    try {
      readStream(clone, model, startedAt, function (usage, meta) {
        finish(usage, meta, meta.model || model, startedAt, imgReq, docReq, fids);
      });
    } catch (e) {
      log('readStream failed: ' + e);
    }
  }

  /** WAV 时长（读 RIFF 头；读不到按 16kHz 单声道 16bit PCM 估） */
  function wavSeconds(file) {
    function fallback() { return file.size > 44 ? (file.size - 44) / 32000 : file.size / 32000; }
    try {
      return file.slice(0, 44).arrayBuffer().then(function (ab) {
        var v = new DataView(ab);
        var rate = ab.byteLength >= 28 ? v.getUint32(24, true) : 0;
        var ch = ab.byteLength >= 24 ? v.getUint16(22, true) : 0;
        var bits = ab.byteLength >= 36 ? v.getUint16(34, true) : 0;
        var perSec = rate * (ch || 1) * ((bits || 16) / 8);
        if (!perSec || !isFinite(perSec) || rate < 4000) return fallback();
        return Math.max(0, file.size - 44) / perSec;
      }).catch(fallback);
    } catch (e) {
      return Promise.resolve(fallback());
    }
  }

  function instrumentFetch() {
    var orig = window.fetch;
    if (typeof orig !== 'function') return;
    window.fetch = function (input, init) {
      var url = '';
      try {
        url = typeof input === 'string' ? input : (input && input.url) || '';
      } catch (e) {}
      var p = orig.apply(this, arguments);
      var startedAt = Date.now();

      // ---- 对话：旁路读一份 SSE 拿 usage
      if (CHAT_RE.test(url)) {
        var model = '', imgReq = 0, docReq = 0, fids = [];
        try {
          if (init && typeof init.body === 'string') {
            var body = JSON.parse(init.body);
            model = (body && body.model) || '';
            var msgs = (body && body.messages) || [];
            for (var mi = 0; mi < msgs.length; mi++) {
              var content = msgs[mi] && msgs[mi].content;
              if (!content || content.length === undefined) continue;
              for (var ci = 0; ci < content.length; ci++) {
                var blk = content[ci];
                if (!blk) continue;
                if (blk.type === 'image') {
                  imgReq += 1;
                  // 图片是按 file_id 引用的（历史消息也一样），拿它反查上传时算好的视觉 token
                  var fid = blk.source && blk.source.file_id;
                  if (fid) fids.push(String(fid));
                } else if (blk.type === 'document') docReq += 1;
              }
            }
          }
        } catch (e) {}
        return p.then(function (res) {
          try { watch(res, model, startedAt, imgReq, docReq, fids); } catch (e) { log('watch failed: ' + e); }
          return res;
        });
      }

      // ---- 附件上传：图片记张数+视觉 token（估算），文档记个数（按页计费，页数拿不到）
      if (FILES_RE.test(url) && (!init || !init.method || /post/i.test(init.method))) {
        var upFile = null;
        try {
          var fd = init && init.body;
          if (fd && typeof fd.forEach === 'function') {
            fd.forEach(function (v) {
              if (v && typeof v === 'object' && typeof v.size === 'number') upFile = v;
            });
          }
        } catch (e) {}
        var isImg = !!(upFile && (
          /^image\//i.test(upFile.type || '') ||
          /\.(png|jpe?g|webp|gif|bmp|heic|heif)$/i.test(upFile.name || '')
        ));
        return p.then(function (res) {
          try {
            if (!res.ok) return res;
            // 服务端返回的 file_id 是后面每轮请求引用这张图的标识：记下来才能反查视觉 token
            var emit = function (fid) {
              try {
                if (isImg && typeof createImageBitmap === 'function') {
                  createImageBitmap(upFile).then(function (bmp) {
                    addEvent({ kind: 'image', fid: fid || '', name: upFile.name || '', bytes: upFile.size || 0, w: bmp.width, h: bmp.height, tok: imgTokens(bmp.width, bmp.height) });
                    try { bmp.close && bmp.close(); } catch (e) {}
                  }).catch(function () {
                    addEvent({ kind: 'image', fid: fid || '', name: upFile.name || '', bytes: upFile.size || 0, w: 0, h: 0, tok: 0 });
                  });
                } else {
                  addEvent({ kind: 'file', fid: fid || '', name: (upFile && upFile.name) || '', bytes: (upFile && upFile.size) || 0 });
                }
              } catch (e) { log('file event failed: ' + e); }
            };
            var done = false;
            var fire = function (fid) { if (!done) { done = true; emit(fid); } };
            if (res.clone) {
              res.clone().json().then(function (j) { fire(fileIdOf(j)); })
                .catch(function () { fire(''); });
            } else {
              fire('');
            }
          } catch (e) { log('file event failed: ' + e); }
          return res;
        });
      }

      // ---- 语音识别：按音频时长计费
      if (ASR_RE.test(url)) {
        var blob = null;
        try {
          var form = init && init.body;
          if (form && typeof form.forEach === 'function') {
            form.forEach(function (v) { if (v && typeof v === 'object' && typeof v.size === 'number') blob = v; });
          }
        } catch (e) {}
        return p.then(function (res) {
          if (blob && wavSeconds) {
            wavSeconds(blob).then(function (sec) {
              if (res.ok) addEvent({ kind: 'asr', sec: sec, bytes: blob.size });
            }).catch(function () {
              if (res.ok) addEvent({ kind: 'asr', sec: 0, bytes: blob.size });
            });
          }
          return res;
        });
      }

      // ---- 朗读：按字符计费（filter_markdown 后字符数会略少，属估算）
      if (TTS_RE.test(url)) {
        var chars = 0;
        try {
          if (init && typeof init.body === 'string') chars = String((JSON.parse(init.body) || {}).text || '').length;
        } catch (e) {}
        return p.then(function (res) {
          try { if (res.ok) addEvent({ kind: 'tts', chars: chars }); } catch (e) {}
          return res;
        });
      }

      return p;
    };
    log('fetch hooked');
  }

  instrumentFetch();

  // ------------------------------------------------------------------ 样式

  var CSS =
    '#' + BTN_ID + '{display:inline-flex;align-items:center;justify-content:center;height:32px;' +
    'padding:0 8px;margin-right:2px;margin-left:auto;border:none;border-radius:16px;background:transparent;' +
    'color:var(--dsw-alias-label-secondary,#4d5157);font-family:inherit;font-size:13px;line-height:1;' +
    'font-variant-numeric:tabular-nums;flex-shrink:0;cursor:pointer;-webkit-tap-highlight-color:transparent}' +
    '#' + BTN_ID + ':active{background:var(--dsw-alias-interactive-bg-hover,rgba(0,0,0,.06))}' +
    '.dsu-mask{position:fixed;inset:0;background:rgba(0,0,0,.32);display:flex;align-items:flex-end;' +
    'justify-content:center;z-index:2147483000;animation:dsuFade .16s ease-out}' +
    '.dsu-sheet{width:100%;max-width:560px;max-height:74vh;display:flex;flex-direction:column;' +
    'background:var(--dsw-alias-bg-base,#fff);border-radius:16px 16px 0 0;' +
    'border-top:1px solid var(--dsw-alias-border-l2,rgba(0,0,0,.08));' +
    'color:var(--dsw-alias-label-primary,#1a1a1a);font-family:inherit;' +
    'animation:dsuUp .18s cubic-bezier(.2,.8,.2,1)}' +
    '.dsu-head{display:flex;align-items:center;justify-content:space-between;padding:14px 16px 6px}' +
    '.dsu-h1{font-size:15px;line-height:1}' +
    '.dsu-x{width:28px;height:28px;border:none;border-radius:50%;background:transparent;padding:0;' +
    'font-size:18px;line-height:1;color:var(--dsw-alias-label-caption,#8a8f99);' +
    'display:inline-flex;align-items:center;justify-content:center}' +
    '.dsu-body{overflow-y:auto;-webkit-overflow-scrolling:touch;padding:2px 16px 0}' +
    '.dsu-card{padding:10px 0;border-bottom:1px solid var(--dsw-alias-border-l2,rgba(0,0,0,.08))}' +
    '.dsu-line1{font-size:13px;line-height:1.5;color:var(--dsw-alias-label-primary,#1a1a1a)}' +
    '.dsu-line2{font-size:12px;line-height:1.6;color:var(--dsw-alias-label-caption,#8a8f99);margin-top:1px}' +
    '.dsu-sub{font-size:11px;line-height:1.7;color:var(--dsw-alias-label-caption,#8a8f99);' +
    'font-variant-numeric:tabular-nums;padding:2px 0 2px 2px}' +
    '.dsu-tbl{width:100%;border-collapse:collapse;font-size:12px;' +
    'font-variant-numeric:tabular-nums;margin:2px 0 4px;table-layout:auto}' +
    '.dsu-tbl th{font-weight:400;font-size:11px;color:var(--dsw-alias-label-caption,#8a8f99);' +
    'text-align:right;padding:3px 0 4px;white-space:nowrap}' +
    '.dsu-tbl th:first-child{text-align:left}' +
    '.dsu-tbl td{text-align:right;padding:4px 0;' +
    'border-top:1px solid var(--dsw-alias-border-l2,rgba(0,0,0,.06));white-space:nowrap}' +
    '.dsu-tbl td:first-child{text-align:left;color:var(--dsw-alias-label-secondary,#4d5157)}' +
    '.dsu-tbl tr.dsu-sum td{border-top:1px solid var(--dsw-alias-border-l1,rgba(0,0,0,.14));font-weight:500}' +
    '.dsu-tbl tr.dsu-barrow td{padding:0 0 7px;border-top:none}' +
    '.dsu-bar{display:flex;width:100%;height:6px;border-radius:3px;overflow:hidden;' +
    'background:var(--dsw-alias-border-l2,rgba(0,0,0,.08));margin:6px 0 4px}' +
    '.dsu-bar i{display:block;height:100%;min-width:2px}' +
    '.dsu-lg{display:flex;flex-wrap:wrap;gap:2px 10px;font-size:11px;line-height:1.7;' +
    'font-variant-numeric:tabular-nums;color:var(--dsw-alias-label-caption,#8a8f99)}' +
    '.dsu-lgi{display:inline-flex;align-items:center;gap:4px;white-space:nowrap}' +
    '.dsu-sw{display:inline-block;width:7px;height:7px;border-radius:2px;flex-shrink:0}' +
    '.dsu-keys{display:flex;flex-wrap:wrap;gap:2px 10px;font-size:11px;line-height:1.7;' +
    'color:var(--dsw-alias-label-caption,#8a8f99);margin:4px 0 2px}' +
    '.dsu-cap{font-size:11px;line-height:1.6;color:var(--dsw-alias-label-caption,#8a8f99);' +
    'font-variant-numeric:tabular-nums;margin-top:3px}' +
    '.dsu-det{margin:14px 0 10px;font-size:11px;color:var(--dsw-alias-label-caption,#8a8f99)}' +
    '.dsu-det summary{padding:4px 0;cursor:pointer;list-style:none}' +
    '.dsu-det summary::-webkit-details-marker{display:none}' +
    '.dsu-det summary::before{content:"\\25B8 ";display:inline-block}' +
    '.dsu-det[open] summary::before{content:"\\25BE "}' +
    '.dsu-sec{font-size:12px;color:var(--dsw-alias-label-caption,#8a8f99);padding:12px 0 2px}' +
    '.dsu-rec{display:flex;flex-direction:column;padding:7px 0;' +
    'border-bottom:1px solid var(--dsw-alias-border-l2,rgba(0,0,0,.06))}' +
    '.dsu-foot{padding:10px 16px calc(14px + env(safe-area-inset-bottom));' +
    'border-top:1px solid var(--dsw-alias-border-l2,rgba(0,0,0,.08))}' +
    '.dsu-clear{width:100%;height:38px;border-radius:10px;' +
    'border:1px solid var(--dsw-alias-border-l1,rgba(0,0,0,.12));background:transparent;' +
    'color:var(--dsw-alias-label-primary,#1a1a1a);font-family:inherit;font-size:13px}' +
    '.dsu-note{font-size:11px;line-height:1.6;color:var(--dsw-alias-label-caption,#8a8f99);margin-top:8px}' +
    '@keyframes dsuUp{from{transform:translateY(12%)}to{transform:none}}' +
    '@keyframes dsuFade{from{opacity:0}to{opacity:1}}';

  function injectStyle() {
    if (document.getElementById(STYLE_ID)) return;
    var st = document.createElement('style');
    st.id = STYLE_ID;
    st.textContent = CSS;
    (document.head || document.documentElement).appendChild(st);
  }

  // ------------------------------------------------------------------ 面板

  function el(tag, cls, text) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text != null) n.textContent = text;
    return n;
  }

  function card(line1, line2) {
    var c = el('div', 'dsu-card');
    c.appendChild(el('div', 'dsu-line1', line1));
    if (line2 != null) c.appendChild(el('div', 'dsu-line2', line2));
    return c;
  }

  /** 通用表格：headers 一行，rows = [{c:[...], sum:bool}] 或 {bar:元素}（整行放一条比例条） */
  function table(headers, rows) {
    var t = el('table', 'dsu-tbl');
    var thead = el('thead');
    var tr0 = el('tr');
    for (var i = 0; i < headers.length; i++) tr0.appendChild(el('th', null, headers[i]));
    thead.appendChild(tr0);
    t.appendChild(thead);
    var tb = el('tbody');
    for (var j = 0; j < rows.length; j++) {
      var row = rows[j];
      var tr = el('tr', row.sum ? 'dsu-sum' : (row.bar ? 'dsu-barrow' : null));
      if (row.bar) {
        var td = el('td');
        td.colSpan = headers.length;
        td.appendChild(row.bar);
        tr.appendChild(td);
      } else {
        for (var k = 0; k < row.c.length; k++) tr.appendChild(el('td', null, row.c[k]));
      }
      tb.appendChild(tr);
    }
    t.appendChild(tb);
    return t;
  }

  function pct(a, total) { return total > 0 ? Math.round(a / total * 100) : 0; }

  // 费用分类与颜色：比例条与图例共用同一份顺序和色值
  var CATS = [
    { k: 'missU', color: '#4d6bfe', name: '未命中输入' },
    { k: 'hit', color: '#93c5fd', name: '缓存输入' },
    { k: 'vis', color: '#a78bfa', name: '视觉' },
    { k: 'out', color: '#f59e0b', name: '输出' },
    { k: 'search', color: '#10b981', name: '搜索' },
    { k: 'asr', color: '#06b6d4', name: '语音识别' },
    { k: 'tts', color: '#ec4899', name: '朗读' }
  ];

  /** 一个桶里各类费用（元）。视觉与输入同价，按「先未命中、后缓存」从输入费用里切出来。 */
  function costCats(b) {
    var visCost = b.visCost || 0;
    var visMiss = Math.min(b.visMissCost || 0, visCost);
    return {
      missU: Math.max(0, (b.missCost || 0) - visMiss),
      hit: Math.max(0, (b.hitCost || 0) - (visCost - visMiss)),
      vis: visCost,
      out: b.outCost || 0,
      search: b.searchCost || 0,
      asr: b.asrCost || 0,
      tts: b.ttsCost || 0
    };
  }

  function catTotal(cats) {
    var total = 0;
    for (var i = 0; i < CATS.length; i++) total += cats[CATS[i].k] || 0;
    return total;
  }

  /** 费用占比条：按类别分色堆叠，顺序与图例一致 */
  function costBar(b) {
    var cats = costCats(b);
    var total = catTotal(cats);
    var bar = el('div', 'dsu-bar');
    if (!(total > 0)) return bar;
    for (var i = 0; i < CATS.length; i++) {
      var v = cats[CATS[i].k] || 0;
      if (!v) continue;
      var seg = el('i');
      seg.style.background = CATS[i].color;
      seg.style.width = (v / total * 100) + '%';
      bar.appendChild(seg);
    }
    return bar;
  }

  /** 各类占比（整数百分比，用最大余数法凑成正好 100%，避免 12+37+25+25=99 这种对不上账） */
  function pctMap(cats) {
    var total = catTotal(cats);
    var res = {};
    if (!(total > 0)) return res;
    var fracs = [];
    var floorSum = 0;
    for (var i = 0; i < CATS.length; i++) {
      var k = CATS[i].k;
      var v = cats[k] || 0;
      if (!(v > 0)) { res[k] = 0; continue; }
      var raw = v / total * 100;
      var f = Math.floor(raw);
      res[k] = f;
      floorSum += f;
      fracs.push({ k: k, frac: raw - f });
    }
    var left = 100 - floorSum;
    fracs.sort(function (a, b) { return b.frac - a.frac; });
    for (var j = 0; j < left && j < fracs.length; j++) res[fracs[j].k] += 1;
    return res;
  }

  /** 图例：色块 + 类别 + 占比（只列有花费的类别）
   *  占比四舍五入到 0% 但确有花费时写「<1%」——否则 512 个缓存 token 会被显示成「缓存输入 0%」，
   *  看起来像没花钱、也不像对得上账。 */
  function costLegend(b) {
    var cats = costCats(b);
    var pms = pctMap(cats);
    var wrap = el('div', 'dsu-lg');
    for (var i = 0; i < CATS.length; i++) {
      var v = cats[CATS[i].k] || 0;
      if (!v) continue;
      var item = el('span', 'dsu-lgi');
      var sw = el('i', 'dsu-sw');
      sw.style.background = CATS[i].color;
      item.appendChild(sw);
      var p = pms[CATS[i].k] || 0;
      item.appendChild(el('span', null, CATS[i].name + ' ' + (p > 0 ? p + '%' : '<1%')));
      wrap.appendChild(item);
    }
    return wrap;
  }

  /** 只有色块 + 名称的图例（时段表用；会话块里的图例带百分比）。skip 里的类别不再重复列。 */
  function catKeys(b, skip) {
    var cats = costCats(b);
    var wrap = el('div', 'dsu-keys');
    for (var i = 0; i < CATS.length; i++) {
      var k = CATS[i].k;
      if (skip && skip[k]) continue;
      if (!(cats[k] > 0)) continue;
      var item = el('span', 'dsu-lgi');
      var sw = el('i', 'dsu-sw');
      sw.style.background = CATS[i].color;
      item.appendChild(sw);
      item.appendChild(el('span', null, CATS[i].name));
      wrap.appendChild(item);
    }
    return wrap;
  }

  /** 本会话 token 表：未命中 / 缓存 / 视觉（从输入里扣出来单列）/ 输出 / 费用，按峰·谷·合计三列
   *  「合计」列一律 = 高峰 + 空闲，保证三列永远对得上账 */
  function sessionTable(t, tot) {
    var pm = textInput(t.peak);
    var om = textInput(t.off);
    var pmiss = Math.round(pm.miss), omiss = Math.round(om.miss);
    var phit = Math.round(pm.hit), ohit = Math.round(om.hit);
    var rows = [];
    if (pmiss + omiss) rows.push({ c: ['未命中输入', fmt(pmiss), fmt(omiss), fmt(pmiss + omiss)] });
    if (tot.hit) rows.push({ c: ['缓存输入', fmt(phit), fmt(ohit), fmt(phit + ohit)] });
    if (tot.vis) {
      var pv = Math.round(t.peak.vis), ov = Math.round(t.off.vis);
      rows.push({ c: ['视觉 token', fmt(pv), fmt(ov), fmt(pv + ov)] });
    }
    rows.push({ c: ['输出', fmt(t.peak.out), fmt(t.off.out), fmt(t.peak.out + t.off.out)] });
    if (tot.search) rows.push({ c: ['搜索（次）', fmt(t.peak.search), fmt(t.off.search), fmt(t.peak.search + t.off.search)] });
    rows.push({ c: ['费用', yuan(t.peak.cost), yuan(t.off.cost), yuan(tot.cost)], sum: true });
    return table(['项目', '高峰', '空闲', '合计'], rows);
  }

  /** 一行小字说明（口径、未计入项） */
  function noteLines(tot) {
    var out = [];
    if (tot.vis) out.push('视觉 ' + fmt(tot.vis) + ' token 已从输入中扣除单列（按官方计算器算法估算，仍按输入价计费）');
    if (tot.visN) out.push(tot.visN + ' 张图片的视觉 token 未能拆出（仍计入输入，未单列）');
    if (tot.think || tot.safety) out.push('输出含思考 ' + fmt(tot.think) + ' · 安全审查 ' + fmt(tot.safety));
    if (tot.asrN) out.push('语音识别 ' + tot.asrSec.toFixed(1) + 's / ' + tot.asrN + ' 次 · ' + yuan(tot.asrCost));
    if (tot.ttsN) out.push('朗读 ' + fmt(tot.ttsChars) + ' 字 / ' + tot.ttsN + ' 次 · ' + yuan(tot.ttsCost));
    if (tot.fileN) out.push('文档 ' + tot.fileN + ' 个（服务端按页计费，页数拿不到，未计入）');
    if (tot.unknown) out.push(tot.unknown + ' 次请求的模型不在价目表里，未计费');
    var wrap = el('div');
    for (var i = 0; i < out.length; i++) wrap.appendChild(el('div', 'dsu-cap', out[i]));
    return wrap;
  }

  function buildPanel() {
    injectStyle();
    var mask = el('div', 'dsu-mask');
    mask.id = PANEL_ID;
    var sheet = el('div', 'dsu-sheet');
    sheet.setAttribute('role', 'dialog');
    sheet.setAttribute('aria-modal', 'true');
    mask.appendChild(sheet);

    var head = el('div', 'dsu-head');
    head.appendChild(el('div', 'dsu-h1', '用量'));
    var x = el('button', 'dsu-x', '×');
    x.setAttribute('aria-label', '关闭');
    head.appendChild(x);
    sheet.appendChild(head);

    var body = el('div', 'dsu-body');
    sheet.appendChild(body);

    var foot = el('div', 'dsu-foot');
    var clear = el('button', 'dsu-clear', '清空本机记录');
    foot.appendChild(clear);
    foot.appendChild(el('div', 'dsu-note', '费用为估算 · 口径见上方「口径说明」'));
    sheet.appendChild(foot);

    function close() {
      document.removeEventListener('keydown', onKey, true);
      if (mask.parentNode) mask.parentNode.removeChild(mask);
    }

    function onKey(e) {
      if (e.key === 'Escape' || e.keyCode === 27) close();
    }

    function clearConfirm() {
      if (clear.__armed) {
        save(emptyStore());
        render();
        paintButton();
        return;
      }
      clear.__armed = true;
      clear.textContent = '再点一次清空';
      setTimeout(function () {
        clear.__armed = false;
        clear.textContent = '清空本机记录';
      }, 3000);
    }

    function render() {
      var d = load();
      var recs = d.records || [];
      body.textContent = '';

      // ---- 本会话优先
      var mine = scopedRecords(recs);
      var sessionCats = null;
      if (!(state.title || currentTitle())) {
        body.appendChild(card('未打开任何对话', '打开一个对话后这里显示该会话的用量'));
      } else if (!mine.length) {
        body.appendChild(card('本会话 · 暂无记录', '本会话还没有发出过请求（改造之前的对话无法回填）'));
      } else {
        var t = tally(mine);
        var tot = totalOf(t);
        body.appendChild(el('div', 'dsu-sec', '本会话 · ' + tot.n + ' 次请求 · ' + yuan(tot.cost)));
        body.appendChild(costBar(tot));
        body.appendChild(costLegend(tot));
        sessionCats = costCats(tot);
        body.appendChild(sessionTable(t, tot));
        body.appendChild(noteLines(tot));
      }

      if (!recs.length) {
        body.appendChild(card('还没有任何记录', '发一条消息后开始计数'));
        return;
      }

      // ---- 时间口径：峰 / 谷 / 合计（token 为输入+输出，费用按各自档位单价）
      body.appendChild(el('div', 'dsu-sec', '时段 · token（输入+输出）'));
      // 时段表里的条覆盖全部记录：会话图例没提到的类别（例如别处的朗读）在这里补上色块
      var keys = catKeys(totalOf(tally(recs)), sessionCats);
      if (keys.children.length) body.appendChild(keys);
      var scopes = [
        ['今日', startOfToday()],
        ['近 7 天', Date.now() - 7 * 24 * 3600 * 1000],
        ['累计', 0]
      ];
      var rows = [];
      for (var s = 0; s < scopes.length; s++) {
        var st = tally(recs, scopes[s][1]);
        var sum = totalOf(st);
        rows.push({ c: [scopes[s][0], fmt(tokenSum(st.peak)), fmt(tokenSum(st.off)),
          fmt(tokenSum(st.peak) + tokenSum(st.off)), yuan(sum.cost)] });
        rows.push({ bar: costBar(sum) });
      }
      body.appendChild(table(['时段', '高峰', '空闲', '合计', '费用'], rows));
      body.appendChild(el('div', 'dsu-cap', '费用列含搜索、语音识别与朗读（按次 / 时长 / 字数计费，不进 token 列）'));

      // ---- 最近请求：上传事件（image/file）不入列表——它们 c=0、没有会话，
      //      视觉 token 已经体现在对应请求那一行里，逐条列出只会把真请求挤到屏幕外。
      var listRecs = [];
      var uploads = 0;
      for (var q = 0; q < recs.length; q++) {
        var rk = recs[q].kind;
        if (rk === 'image' || rk === 'file') { uploads++; continue; }
        listRecs.push(recs[q]);
      }
      body.appendChild(el('div', 'dsu-sec', '最近 ' + Math.min(20, listRecs.length) + ' 次请求'));
      var start = Math.max(0, listRecs.length - 20);
      for (var i = listRecs.length - 1; i >= start; i--) {
        var r = listRecs[i];
        var row = el('div', 'dsu-rec');
        var kindLabel = r.kind === 'asr' ? '语音识别' : r.kind === 'tts' ? '朗读' : '';
        row.appendChild(el('div', 'dsu-line1', kindLabel
          ? hhmm(r.t) + ' · ' + kindLabel + (r.ok ? '' : ' · 未完成')
          : hhmm(r.t) + ' · ' + (r.m || '未知模型') + ' · ' + tierText(r.t) + (r.ok ? '' : ' · 未完成')));
        row.appendChild(el('div', 'dsu-line2', metricLine(r)));
        body.appendChild(row);
      }
      if (uploads) {
        body.appendChild(el('div', 'dsu-cap', uploads + ' 次图片 / 文档上传未逐条列出（视觉 token 已计入对应请求）'));
      }

      // ---- 口径说明（默认收起，避免面板全是字）
      var det = el('details', 'dsu-det');
      det.appendChild(el('summary', null, '口径说明'));
      det.appendChild(el('div', 'dsu-cap', '每轮请求的输入 = 完整对话历史，所以「输入」远大于对话文本本身。'));
      det.appendChild(el('div', 'dsu-cap', '高峰 = 北京时间工作日 9:00-12:00、14:00-18:00（不含法定节假日，属估算）；其余为空闲价（高峰价的一半）。'));
      det.appendChild(el('div', 'dsu-cap', '视觉 token 由图片尺寸按官方计算器算法折算，已含在 usage 的输入里，这里扣出来单列，不重复计费。'));
      det.appendChild(el('div', 'dsu-cap', '只统计本 App 内的请求：其他设备、网页版、直接调 API 都不在内。中断的请求只进列表、不计入合计。'));
      body.appendChild(det);
    }

    render();

    x.addEventListener('click', close);
    clear.addEventListener('click', clearConfirm);
    mask.addEventListener('click', function (e) {
      if (e.target === mask) close();
    });
    document.addEventListener('keydown', onKey, true);

    mask.__dsuRefresh = render;
    document.body.appendChild(mask);
    // 会话 id 要查一下 IndexedDB，拿到后重画
    refreshSessions();
  }

  function openPanel() {
    var panel = document.getElementById(PANEL_ID);
    if (panel) {
      if (panel.__dsuRefresh) panel.__dsuRefresh();
      refreshSessions();
      return;
    }
    buildPanel();
  }

  function repaint() {
    paintButton();
    var panel = document.getElementById(PANEL_ID);
    if (panel && panel.__dsuRefresh) panel.__dsuRefresh();
  }

  // ------------------------------------------------ 入口按钮（显示本会话费用）

  function paintButton() {
    var btn = document.getElementById(BTN_ID);
    if (!btn) return;
    var d = load();
    var recs = d.records || [];
    var label;
    if (state.title || currentTitle()) {
      label = yuan(totalOf(tally(scopedRecords(recs))).cost);
    } else {
      // 首页没有「当前会话」，退成今日费用
      label = yuan(scopeTotals(recs, startOfToday()).cost);
    }
    if (btn.textContent !== label) btn.textContent = label;
  }

  function visibleHeader() {
    var list = document.querySelectorAll('div[class^="header-"]');
    for (var i = 0; i < list.length; i++) {
      var h = list[i];
      if (h.offsetWidth || h.offsetHeight || h.getClientRects().length) return h;
    }
    return list.length ? list[0] : null;
  }

  function ensureButton() {
    var header = visibleHeader();
    if (!header) return;
    var btn = document.getElementById(BTN_ID);
    if (!btn) {
      injectStyle();
      btn = el('button', '', '¥0');
      btn.id = BTN_ID;
      btn.type = 'button';
      btn.setAttribute('aria-label', '本会话用量与费用');
      btn.addEventListener('click', function (e) {
        e.preventDefault();
        e.stopPropagation();
        openPanel();
      }, true);
      log('button mounted');
    }
    // 首页视图没有「开启新对话」，此时贴在右侧末尾；会话视图贴到它左边
    var newChat = header.querySelector('button[aria-label="开启新对话"],button[aria-label="New chat"]');
    if (btn.parentNode !== header) {
      if (newChat) header.insertBefore(btn, newChat);
      else header.appendChild(btn);
    } else if (newChat) {
      if (btn.nextElementSibling !== newChat) header.insertBefore(btn, newChat);
    } else if (btn.nextElementSibling) {
      header.appendChild(btn);
    }
    paintButton();
  }

  /** 标题变了 = 切了会话：重新解析会话 id，按钮上的费用跟着换 */
  function watchTitle() {
    var t = currentTitle();
    if (t !== state.title) {
      state.title = t;
      state.sid = '';
      paintButton();
      refreshSessions();
    }
  }

  // 头部是 React 异步挂载的：以前只靠 700ms 轮询，按钮在标题栏画出来之后才「长出来」
  // （最多晚 700ms，看起来和 UI 渲染不同步，而且按钮插进去那一下还会把右侧按钮挤一下）。
  // 这里用 MutationObserver 在 header 出现的那一帧补挂。
  var mountQueued = false;
  function ensureButtonSoon() {
    var b = document.getElementById(BTN_ID);
    if (b && b.isConnected) return; // 已在树上，位置/数字交给 700ms 的轮询维护
    if (mountQueued) return;
    mountQueued = true;
    (window.requestAnimationFrame || function (f) { return setTimeout(f, 16); })(function () {
      mountQueued = false;
      ensureButton();
    });
  }

  try {
    new MutationObserver(ensureButtonSoon).observe(document.documentElement, {
      childList: true,
      subtree: true
    });
  } catch (e) {}

  // 启动头 3 秒再密一点兜底（header 只是从隐藏切到可见这类改动 Observer 抓不到「可见性」）
  var fastTicks = 0;
  var fastTimer = setInterval(function () {
    ensureButton();
    if (++fastTicks >= 20) clearInterval(fastTimer);
  }, 150);

  setInterval(function () {
    ensureButton();
    watchTitle();
  }, 700);
  if (document.readyState !== 'loading') {
    ensureButton();
    refreshSessions();
  }
  document.addEventListener('DOMContentLoaded', function () {
    ensureButton();
    refreshSessions();
  });
})();
