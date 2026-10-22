/* Shared state, formatting, the live stream, routing, theme and risk settings. */
window.OB = (() => {
  'use strict';

  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => [...r.querySelectorAll(s)];
  const IST = 19800; // charts have no time zones: shift UTC seconds to show IST
  const nf2 = new Intl.NumberFormat('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const nf0 = new Intl.NumberFormat('en-IN', { maximumFractionDigits: 0 });

  const fmt = {
    num: x => x == null || Number.isNaN(x) ? '—' : nf2.format(x),
    int: x => x == null || Number.isNaN(x) ? '—' : nf0.format(x),
    sign: x => x > 0 ? '+' : x < 0 ? '−' : '',
    pct: x => x == null ? '—' : fmt.sign(x) + Math.abs(x).toFixed(2) + '%',
    chg: x => x == null ? '—' : fmt.sign(x) + nf2.format(Math.abs(x)),
    rr: x => '1:' + Number(x).toFixed(1),
    r: x => x == null ? '—' : fmt.sign(x) + Math.abs(x).toFixed(2) + 'R',
    inr: x => x == null ? '—' : (x < 0 ? '−' : '') + '₹' + nf0.format(Math.abs(x)),
    hhmm: t => new Date((t + IST) * 1000).toISOString().slice(11, 16),
    vol: x => x >= 1e7 ? (x / 1e7).toFixed(2) + ' Cr' : x >= 1e5 ? (x / 1e5).toFixed(2) + ' L' : nf0.format(x),
  };
  const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const initials = s => s.replace(/[^A-Za-z0-9]/g, '').slice(0, 2).toUpperCase();

  let palette = {};
  const col = n => palette[n] || (palette[n] = getComputedStyle(document.documentElement).getPropertyValue(n).trim());
  const rgba = (n, a) => {
    const v = parseInt(col(n).slice(1), 16);
    return `rgba(${(v >> 16) & 255},${(v >> 8) & 255},${v & 255},${a})`;
  };

  /* ---------- events ---------- */
  const handlers = {};
  const on = (ev, fn) => (handlers[ev] = handlers[ev] || []).push(fn);
  const emit = (ev, d) => (handlers[ev] || []).forEach(fn => {
    try { fn(d); } catch (e) { console.error(e); }
  });

  const state = { board: null, status: null, quotes: {}, symbols: [], view: 'home' };

  /* ---------- level names: bullish setups have S below and R above, bearish the other way ---------- */
  const names = side => side === 'bull'
    ? { stop: 'S1', t: ['R1', 'R2'], behind: ['S2', 'S3', 'S4'], verb: 'Buy', word: 'Bullish' }
    : { stop: 'R1', t: ['S1', 'S2'], behind: ['R2', 'R3', 'R4'], verb: 'Sell', word: 'Bearish' };

  const kindText = k => ({
    swing: 'untaken swing', equal: 'equal highs/lows', pdh: "yesterday's high", pdl: "yesterday's low",
    dayHigh: "day's high", dayLow: "day's low", zone: 'opposing block', open: "day's open", gap: 'open gap',
  }[k] || (/^\dR$/.test(k) ? `projected ${k}` : k));

  const statusText = {
    waiting: 'Waiting for entry', active: 'In trade', t1: 'T1 hit · stop at entry', won: 'Target 2 hit',
    breakeven: 'Out at entry after T1', stopped: 'Stopped out', missed: 'Missed: no retest',
    expired: 'No entry by 2:30 PM', cancelled: 'Replaced by newer zone', closed: 'Closed at 3:15 PM',
  };

  const buildUpTag = b => b ? `<span class="tag ${({ 'Long build-up': 'lb', 'Short covering': 'sc', 'Short build-up': 'sb', 'Long unwinding': 'lu' })[b] || ''}">${esc(b)}</span>` : '';

  /* ---------- risk settings ---------- */
  const MIN_RISK = 250;   // a trade that can't risk this much isn't worth its charges; the backtest skipped these
  const MARGIN = 0.2;     // intraday margin blocked, as a share of the position (5× leverage)
  const settings = (() => {
    const d = { capital: 10000, risk: 4 };
    try { return { ...d, ...JSON.parse(localStorage.getItem('ob:settings') || '{}') }; } catch { return d; }
  })();
  const riskAmount = () => settings.capital * settings.risk / 100;

  /** Shares to trade: the risk budget at the stop, capped by what the margin allows. Too small to be worth it → skip. */
  const sizing = (entry, stop) => {
    const per = Math.abs(entry - stop);
    if (!(per > 0) || !(entry > 0)) return { qty: 0, risk: 0, note: '', skip: true };
    const byRisk = Math.floor(riskAmount() / per);
    const byMargin = Math.floor(settings.capital / (entry * MARGIN));
    const qty = Math.max(0, Math.min(byRisk, byMargin));
    const risk = qty * per;
    const note = qty < 1 ? 'Too expensive for this account'
      : risk < MIN_RISK ? `Skip: it could only risk ${fmt.inr(risk)}, under ${fmt.inr(MIN_RISK)} the charges would eat it`
      : byMargin < byRisk ? `capped by margin, position ${fmt.inr(qty * entry)}` : '';
    return { qty, risk, note, skip: qty < 1 || risk < MIN_RISK };
  };
  const qty = (entry, stop) => sizing(entry, stop).qty;

  /** The strategy's dials, as the server reports them. */
  const rules = () => (state.status && state.status.rules) || {};
  const hm = m => {
    const h = Math.floor(m / 60);
    return `${h % 12 || 12}:${String(m % 60).padStart(2, '0')} ${h >= 12 ? 'PM' : 'AM'}`;
  };

  function openSettings() {
    const d = $('#settings');
    d.querySelector('[name=capital]').value = settings.capital;
    d.querySelector('[name=risk]').value = settings.risk;
    d.showModal();
  }
  $('#settings').addEventListener('close', () => {
    const d = $('#settings');
    if (d.returnValue !== 'save') return;
    const c = Number(d.querySelector('[name=capital]').value), r = Number(d.querySelector('[name=risk]').value);
    if (c > 0) settings.capital = c;
    if (r > 0) settings.risk = Math.min(r, 5);
    try { localStorage.setItem('ob:settings', JSON.stringify(settings)); } catch { /* private mode */ }
    emit('settings', settings);
    render();
  });

  /* ---------- theme ---------- */
  function setTheme(t) {
    document.documentElement.dataset.theme = t;
    try { localStorage.setItem('ob:theme', t); } catch { /* private mode */ }
    palette = {};
    emit('theme', t);
    render();
  }

  /* ---------- small drawings shared by the views ---------- */
  function spark(path, w = 96, h = 28) {
    if (!path || path.length < 2) return `<svg class="spark" width="${w}" height="${h}"></svg>`;
    const lo = Math.min(0, ...path), hi = Math.max(0, ...path), span = hi - lo || 1;
    const x = i => 2 + i / (path.length - 1) * (w - 4);
    const y = v => h - 2 - (v - lo) / span * (h - 4);
    const last = path[path.length - 1];
    const pts = path.map((v, i) => `${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(' ');
    return `<svg class="spark ${last >= 0 ? 'up' : 'dn'}" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}" aria-hidden="true">`
      + `<line class="zero" x1="0" x2="${w}" y1="${y(0).toFixed(1)}" y2="${y(0).toFixed(1)}"/>`
      + `<polyline points="${pts}"/><circle cx="${x(path.length - 1).toFixed(1)}" cy="${y(last).toFixed(1)}" r="2.2"/></svg>`;
  }

  const conv = c => `<span class="conv" title="Conviction ${c}/100"><b>${c}</b>${[1, 2, 3, 4, 5].map(i => `<i class="${Math.round(c / 20) >= i ? 'on' : ''}"></i>`).join('')}</span>`;

  /** Volume by price, price running bottom to top; a line marks the last price. */
  function profile(p, ltp, w = 100, h = 40) {
    if (!p) return `<svg class="vp" viewBox="0 0 ${w} ${h}" preserveAspectRatio="none"></svg>`;
    const n = p.bins.length, bh = h / n;
    let s = '';
    p.bins.forEach((v, i) => {
      s += `<rect x="0" y="${(h - (i + 1) * bh + .3).toFixed(2)}" width="${Math.max(.5, v / 100 * w).toFixed(1)}" height="${Math.max(.6, bh - .6).toFixed(2)}"/>`;
    });
    const ly = Math.max(0, Math.min(h, h - (ltp - p.lo) / (p.hi - p.lo || 1) * h));
    return `<svg class="vp" viewBox="0 0 ${w} ${h}" preserveAspectRatio="none" aria-hidden="true">${s}<line class="ltp" x1="0" x2="${w}" y1="${ly.toFixed(2)}" y2="${ly.toFixed(2)}" vector-effect="non-scaling-stroke"/></svg>`;
  }

  /* ---------- live stream ---------- */
  function onStatus(s) {
    state.status = s;
    $('#connect').hidden = !s.needsLogin;
    const n = $('#notice');
    if (s.state === 'ready') {
      n.hidden = true;
      return;
    }
    const title = { starting: 'Starting', loading: 'Loading market data', login: 'Connect Upstox', error: 'Something went wrong' }[s.state] || s.state;
    n.className = 'notice ' + s.state;
    n.innerHTML = `<div><b>${esc(title)}</b><span>${esc(s.message || '')}</span></div>`
      + (s.needsLogin ? '<a class="btn" href="/login/upstox">Connect Upstox</a>' : '');
    n.hidden = false;
  }

  function onBoard(b) {
    state.board = b;
    const m = $('#mkt');
    m.className = 'pill ' + b.market.state;
    m.lastElementChild.textContent = b.market.label;
    m.title = b.market.label;
    m.dataset.short = { open: 'Live', pre: 'Pre-open', closed: 'Closed' }[b.market.state] || 'Market';
    $('#clock').textContent = fmt.hhmm(b.t) + ' IST';
    const open = b.scorecard.open + b.plans.filter(p => p.status === 'waiting').length;
    $$('[data-plans-badge]').forEach(e => { e.hidden = open === 0; });
    if (!state.symbols.length) loadSymbols();
    render();
    emit('board', b);
  }

  function onQuotes(m) {
    Object.assign(state.quotes, m.q);
    for (const el of $$('[data-ltp]')) {
      const v = state.quotes[el.dataset.ltp];
      if (v) el.textContent = fmt.num(v[0]);
    }
    for (const el of $$('[data-chg]')) {
      const v = state.quotes[el.dataset.chg];
      if (!v) continue;
      el.textContent = fmt.pct(v[1]);
      el.classList.toggle('pos', v[1] >= 0);
      el.classList.toggle('neg', v[1] < 0);
    }
    emit('quotes', state.quotes);
  }

  async function loadSymbols() {
    try {
      const list = await (await fetch('/api/universe')).json();
      state.symbols = list.map(x => x.symbol);
      state.universe = { list, bySym: Object.fromEntries(list.map(x => [x.symbol, x])) };
      emit('universe', state.universe);
    } catch { /* retried on the next board */ }
  }

  /* ---------- find a stock: matches by symbol, then name; arrows to move, Enter to open ---------- */
  const finder = document.createElement('div');
  finder.className = 'finder';
  finder.hidden = true;
  $('.search').appendChild(finder);
  let found = [];
  let sel = 0;

  function standing(sym) {
    const b = state.board;
    const r = b && [...b.bullish, ...b.bearish].find(x => x.symbol === sym);
    if (!r) return '<span class="st">Herd</span>';
    const k = r.side === 'bull' ? 'up' : 'dn';
    return r.pick ? `<span class="st ${k}">${r.side === 'bull' ? '▲' : '▼'} Pick #${r.pick}</span>` : `<span class="st ${k}">Contender #${r.rank}</span>`;
  }

  function mark(text, q) {
    const i = text.toUpperCase().indexOf(q);
    if (i < 0 || !q) return esc(text);
    return esc(text.slice(0, i)) + '<em>' + esc(text.slice(i, i + q.length)) + '</em>' + esc(text.slice(i + q.length));
  }

  function search(raw) {
    const q = raw.trim().toUpperCase();
    const u = state.universe;
    if (!q || !u) {
      found = [];
      finder.hidden = true;
      return;
    }
    const chg = new Map((state.board ? state.board.dots : []).map(d => [d.s, d.c]));
    const rank = x => x.symbol.startsWith(q) ? 0 : x.symbol.includes(q) ? 1 : x.name.toUpperCase().includes(q) ? 2 : 9;
    found = u.list.map(x => [rank(x), x]).filter(([r]) => r < 9)
      .sort((a, z) => a[0] - z[0] || a[1].symbol.localeCompare(z[1].symbol)).slice(0, 8).map(([, x]) => x);
    sel = 0;
    finder.innerHTML = found.map((x, i) => {
      const c = chg.get(x.symbol) ?? 0;
      return `<div class="fr ${i === sel ? 'on' : ''} ${c >= 0 ? 'up' : 'dn'}" data-i="${i}"><b>${mark(x.symbol, q)}</b>`
        + `<span class="nm">${mark(x.name, q)}</span><span class="pc">${fmt.pct(c)}</span>${standing(x.symbol)}</div>`;
    }).join('') || '<div class="fr"><span class="nm">No F&amp;O stock matches</span></div>';
    finder.hidden = false;
  }

  function choose(i) {
    const x = found[i];
    if (!x) return;
    const input = $('#search');
    input.value = '';
    finder.hidden = true;
    input.blur();
    emit('hover', x.symbol);
    window.OB.pop.open(x.symbol, [x.symbol]);
  }

  // Without the rail the search field is hidden behind an icon; it opens over the top bar and closes when it loses focus.
  const openSearch = () => {
    $('.top').classList.add('searching');
    $('#search').focus();
  };
  $('#search-open').addEventListener('click', openSearch);
  $('.search-x').addEventListener('mousedown', e => {
    e.preventDefault();
    $('#search').value = '';
    search('');
    $('#search').blur();
  });

  $('#search').addEventListener('input', e => search(e.target.value));
  $('#search').addEventListener('keydown', e => {
    if (e.key === 'Escape' && (finder.hidden || !found.length)) {
      e.target.blur();
      return;
    }
    if (finder.hidden || !found.length) return;
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      sel = (sel + (e.key === 'ArrowDown' ? 1 : found.length - 1)) % found.length;
      $$('.fr', finder).forEach((r, i) => r.classList.toggle('on', i === sel));
    } else if (e.key === 'Enter') {
      e.preventDefault();
      choose(sel);
    } else if (e.key === 'Escape') {
      finder.hidden = true;
    }
  });
  $('#search').addEventListener('blur', () => setTimeout(() => {
    finder.hidden = true;
    $('.top').classList.remove('searching');
  }, 150));
  finder.addEventListener('mousedown', e => {
    const r = e.target.closest('.fr[data-i]');
    if (r) {
      e.preventDefault();
      choose(Number(r.dataset.i));
    }
  });

  function connect() {
    const es = new EventSource('/api/stream');
    es.addEventListener('status', e => onStatus(JSON.parse(e.data)));
    es.addEventListener('board', e => onBoard(JSON.parse(e.data)));
    es.addEventListener('quotes', e => onQuotes(JSON.parse(e.data)));
    es.addEventListener('error', () => {
      if (es.readyState === EventSource.OPEN) return;
      $('#mkt').className = 'pill';
      $('#mkt').lastElementChild.textContent = 'Reconnecting';
      $('#mkt').dataset.short = 'Offline';
    });
  }

  /* ---------- routing ---------- */
  const views = {};

  function route() {
    const v = (location.hash.replace(/^#\/?/, '') || 'home').split(/[/?]/)[0];
    const next = views[v] ? v : 'home';
    if (next !== state.view) $('#view').innerHTML = '';
    state.view = next;
    document.body.dataset.view = next;
    $$('[data-nav]').forEach(a => a.classList.toggle('on', a.dataset.nav === state.view));
    render();
    window.scrollTo(0, 0);
  }

  function render() {
    const v = views[state.view];
    if (!v) return;
    if (!state.board && !v.static) {
      if (!$('#view').children.length) $('#view').innerHTML = '<div class="empty">Waiting for the first board…</div>';
      return;
    }
    v.render($('#view'), state.board);
  }

  /* ---------- wiring ---------- */
  document.addEventListener('click', e => {
    if (e.target.closest('[data-theme-toggle]')) setTheme(document.documentElement.dataset.theme === 'light' ? 'dark' : 'light');
    if (e.target.closest('[data-open-settings]')) openSettings();
  });
  document.addEventListener('keydown', e => {
    if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
      e.preventDefault();
      openSearch();
    }
  });
  window.addEventListener('hashchange', route);
  document.addEventListener('DOMContentLoaded', () => {
    route();
    connect();
  });

  return {
    $, $$, IST, fmt, esc, initials, col, rgba, on, emit, state, names, kindText, statusText, buildUpTag,
    settings, riskAmount, qty, sizing, rules, hm, spark, conv, profile, views, render, openSettings,
  };
})();
