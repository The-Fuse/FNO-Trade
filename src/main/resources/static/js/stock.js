/* The stock card: hover a stock on desktop to peek, click or tap to pin it (a sheet on phones). */
(() => {
  'use strict';

  const { $, $$, IST, fmt, esc, initials, col, rgba, names, kindText, statusText, buildUpTag } = OB;
  const LC = window.LightweightCharts;
  const pop = $('#pop');
  const scrim = $('#scrim');
  const els = {
    head: $('.pop-head', pop), stats: $('.pop-stats', pop), chart: $('.pop-chart', pop),
    plan: $('.pop-plan', pop), foot: $('.pop-foot', pop),
  };
  const canHover = () => matchMedia('(hover: hover) and (min-width: 1024px)').matches;

  let sym = null;
  let list = [];
  let pinned = false;
  let anchor = null;
  let data = null;
  let seq = 0;
  let hoverTimer = 0;
  let leaveTimer = 0;
  let chart = null, candles = null, vwap = null, overlay = null;
  let extra = [];

  /* ---------- chart overlay: zone, labelled levels of the current setup, earlier setups, price marker ---------- */
  function tag(ctx, text, x, y, color, right) {
    ctx.font = `600 10px ${col('--font-mono')}`;
    const w = ctx.measureText(text).width + 8;
    const x0 = right ? x - w : x;
    ctx.fillStyle = rgba('--panel', 0.85);
    ctx.fillRect(x0, y - 15, w, 13);
    ctx.fillStyle = color;
    ctx.fillText(text, x0 + 4, y - 5);
  }

  class Overlay {
    constructor() {
      this.c = null;
      this.view = { zOrder: () => 'top', renderer: () => ({ draw: t => this.draw(t) }) };
    }
    attached({ chart: ch, series, requestUpdate }) { this.chart = ch; this.series = series; this.requestUpdate = requestUpdate; }
    detached() { this.chart = null; }
    updateAllViews() {}
    paneViews() { return [this.view]; }
    set(c) {
      this.c = c;
      if (this.requestUpdate) this.requestUpdate();
    }
    draw(target) {
      const c = this.c;
      if (!c || !this.chart) return;
      const ts = this.chart.timeScale(), se = this.series, s = c.row.setup, n = names(c.row.side);
      target.useMediaCoordinateSpace(({ context: ctx, mediaSize }) => {
        const W = mediaSize.width;
        const X = t => t == null ? W : ts.timeToCoordinate(t + IST);
        const Y = p => se.priceToCoordinate(p);
        const bar = Math.max(3, ts.options().barSpacing || 6);
        ctx.save();

        // earlier setups stay where they happened, unlabelled
        for (const z of c.zones) {
          if (s && z.t === s.zone.t && z.side === s.zone.side) continue;
          const x1 = X(z.t), x2 = X(z.brokenT), y1 = Y(z.hi), y2 = Y(z.lo);
          if (x1 == null || x2 == null || y1 == null || y2 == null) continue;
          ctx.fillStyle = rgba(z.side === 'bull' ? '--pos' : '--neg', z.brokenT == null ? 0.1 : 0.05);
          ctx.fillRect(x1 - bar / 2, Math.min(y1, y2), x2 - x1 + bar / 2, Math.max(1, Math.abs(y2 - y1)));
        }

        if (s) {
          const k = c.row.side === 'bull' ? '--pos' : '--neg';
          const z = s.zone, x1 = X(z.t), xf = X(z.formedT), y1 = Y(z.hi), y2 = Y(z.lo);
          const from = xf ?? x1 ?? 0;
          if (x1 != null && y1 != null && y2 != null) {
            const top = Math.min(y1, y2), h = Math.max(2, Math.abs(y2 - y1));
            ctx.fillStyle = rgba(k, 0.12); // the zone runs on from the block
            ctx.fillRect(x1 - bar / 2, top, W - x1 + bar / 2, h);
            const bx = x1 - bar / 2, bw = (xf ?? x1) - x1 + bar;
            ctx.fillStyle = rgba(k, 0.22); // the block itself
            ctx.fillRect(bx, top, bw, h);
            ctx.strokeStyle = rgba(k, 0.9);
            ctx.lineWidth = 1;
            ctx.strokeRect(Math.round(bx) + 0.5, Math.round(top) + 0.5, Math.round(bw), Math.round(h));
          }
          const taken = { left: [], right: [] };
          const line = (price, color, dash, label, left, x0) => {
            const y = Y(price);
            if (y == null) return;
            const xs = x0 ?? from;
            ctx.setLineDash(dash);
            ctx.strokeStyle = color;
            ctx.lineWidth = 1;
            ctx.beginPath();
            ctx.moveTo(xs, Math.round(y) + 0.5);
            ctx.lineTo(W, Math.round(y) + 0.5);
            ctx.stroke();
            ctx.setLineDash([]);
            // labels on the same side step up or down until they clear each other
            const used = taken[left ? 'left' : 'right'];
            let ly = y;
            for (const d of [0, -13, 13, -26, 26, -39, 39]) {
              if (!used.some(q => Math.abs(q - (y + d)) < 13)) { ly = y + d; break; }
            }
            used.push(ly);
            tag(ctx, label, left ? xs + 4 : W - 6, ly, color, !left);
          };
          s.behind.forEach((b, i) => line(b.price, col('--amber'), [2, 3], `${n.behind[i]} ${fmt.num(b.price)}`, true, b.t != null ? (X(b.t) ?? from) : from));
          line(s.stop, col('--stop'), [4, 3], `${n.stop} ${fmt.num(s.stop)}`, false);
          s.targets.forEach((t, i) => line(t.price, col(k), [4, 3], `${n.t[i]} ${fmt.num(t.price)}`, false));
          line(s.z, col('--z'), [], `OB Z ${fmt.num(s.z)}`, true, x1 != null ? Math.max(0, x1 - bar / 2) : from);
        }

        const last = c.candles[c.candles.length - 1];
        if (last) {
          const q = OB.state.quotes[c.row.symbol];
          const x = X(last.t), y = Y(q ? q[0] : last.c);
          if (x != null && y != null) {
            ctx.beginPath();
            ctx.arc(x, y, 6, 0, Math.PI * 2);
            ctx.fillStyle = rgba('--text', 0.15);
            ctx.fill();
            ctx.beginPath();
            ctx.arc(x, y, 3, 0, Math.PI * 2);
            ctx.fillStyle = col('--text');
            ctx.fill();
          }
        }
        ctx.restore();
      });
    }
  }

  function chartTheme() {
    return {
      layout: { background: { type: 'solid', color: 'rgba(0,0,0,0)' }, textColor: col('--faint'), fontFamily: col('--font-mono'), fontSize: 10, attributionLogo: false },
      grid: { vertLines: { color: rgba('--line', 0.6) }, horzLines: { color: rgba('--line', 0.6) } },
    };
  }

  function ensureChart() {
    if (chart) return;
    chart = LC.createChart(els.chart, {
      autoSize: true,
      ...chartTheme(),
      rightPriceScale: { visible: false, scaleMargins: { top: 0.08, bottom: 0.06 } },
      leftPriceScale: { visible: false },
      timeScale: { borderVisible: false, timeVisible: true, secondsVisible: false, rightOffset: 4 },
      crosshair: { mode: LC.CrosshairMode.Magnet, horzLine: { labelVisible: false } },
      handleScroll: false,
      handleScale: false,
    });
    candles = chart.addCandlestickSeries({
      upColor: col('--pos'), downColor: col('--neg'), wickUpColor: col('--pos'), wickDownColor: col('--neg'),
      borderVisible: false, priceLineVisible: false, lastValueVisible: false,
      autoscaleInfoProvider: original => {
        const r = original();
        if (!r || !extra.length) return r;
        return { priceRange: { minValue: Math.min(r.priceRange.minValue, ...extra), maxValue: Math.max(r.priceRange.maxValue, ...extra) }, margins: r.margins };
      },
    });
    vwap = chart.addLineSeries({
      color: col('--vwap'), lineWidth: 1, lineStyle: LC.LineStyle.Dashed,
      priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false,
    });
    overlay = new Overlay();
    candles.attachPrimitive(overlay);
  }

  OB.on('theme', () => {
    if (!chart) return;
    chart.applyOptions(chartTheme());
    candles.applyOptions({ upColor: col('--pos'), downColor: col('--neg'), wickUpColor: col('--pos'), wickDownColor: col('--neg') });
    vwap.applyOptions({ color: col('--vwap') });
  });

  /* ---------- card sections ---------- */
  function renderHead(r) {
    const tone = r.chg >= 0 ? 'pos' : 'neg';
    els.head.innerHTML = `
      <div class="ph">
        <span class="av">${initials(r.symbol)}</span>
        <div class="who"><b>${esc(r.symbol)}</b><small>${esc(r.name)} · ${esc(r.sector)}</small></div>
        ${OB.spark(r.path, 96, 30)}
        <button class="icon-btn x" data-pop-close aria-label="Close"><svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button>
      </div>
      <div class="ppx">
        <b>₹<span data-ltp="${esc(r.symbol)}">${fmt.num(r.ltp)}</span></b>
        <span><span class="${tone}" data-chg="${esc(r.symbol)}">${fmt.pct(r.chg)}</span> <span class="${tone}">· ${fmt.chg(r.chgAbs)}</span> ${buildUpTag(r.buildUp)}</span>
      </div>`;
  }

  function renderStats(r) {
    const n = names(r.side), s = r.setup;
    const cell = (k, v, cls = '') => `<div><dt>${k}</dt><dd class="${cls}">${v}</dd></div>`;
    const rule = r.pick ? `rule-pick ${r.side}` : r.event ? 'rule-event' : 'muted';
    els.stats.innerHTML = '<dl class="pst">'
      + cell('Rank', `p${r.pct.toFixed(1)} · #${r.rank || '—'}/${r.sideCount}`)
      + cell('Conv', OB.conv(r.conv))
      + cell('VWAP', fmt.num(r.vwap))
      + cell('OB', s ? `${fmt.num(s.zone.lo)}–${fmt.num(s.zone.hi)}` : '—')
      + cell(`Z/${n.stop}`, s ? `${fmt.num(s.z)} · <span style="color:var(--stop)">${fmt.num(s.stop)}</span>` : '—')
      + cell(`${n.t[0]}/${n.t[1]}`, s ? `<span class="${r.side === 'bull' ? 'pos' : 'neg'}">${fmt.num(s.targets[0].price)} · ${fmt.num(s.targets[1].price)}</span>` : '—')
      + cell('R:R', s ? fmt.rr(s.rr) : '—')
      + cell('Rule', esc(r.rule), rule)
      + '</dl>';
  }

  function renderPlan(c) {
    const r = c.row, s = r.setup, n = names(r.side), a = r.advice;
    let h = `<div class="pplan"><div class="act-row"><span class="act ${a.action}">${a.action}</span><span>${esc(a.reason)}</span></div>`;
    if (s) {
      const risk = Math.abs(s.z - s.stop);
      const multiple = p => (Math.abs(p - s.z) / risk).toFixed(1) + 'R';
      const live = a.action === 'BUY' || a.action === 'SELL';
      const rules = OB.rules();
      const mode = rules.TARGET || 'half';
      const tgt = i => `${n.t[i]} <span class="num">${fmt.num(s.targets[i].price)}</span> (${multiple(s.targets[i].price)}, ${kindText(s.targets[i].kind)})`;
      const exits = mode === 't2' ? `<li><b>Checkpoint</b>${tgt(0)}: no action</li><li><b>Target</b>${tgt(1)}: exit everything</li>`
        : mode === 't1' ? `<li><b>Target</b>${tgt(0)}: exit everything</li><li><b>Beyond</b>${tgt(1)}: not used</li>`
        : `<li><b>Target 1</b>${tgt(0)}: book half, move stop to entry</li><li><b>Target 2</b>${tgt(1)}: exit the rest</li>`;
      h += `<ol class="steps ${live ? '' : 'off'}">
        <li><b>Entry</b>${n.verb} limit at Z <span class="num">${fmt.num(s.z)}</span> when price comes back to the zone</li>
        <li><b>Stop</b>${n.stop} <span class="num">${fmt.num(s.stop)}</span> · risk <span class="num">${fmt.num(risk)}</span> a share (${fmt.pct(risk / s.z * 100).replace('+', '')} of price)</li>
        ${exits}
        <li><b>Time</b>No entry after ${OB.hm(Number(rules.LAST_ENTRY_MIN || 870))} · exit everything by ${OB.hm(Number(rules.EXIT_MIN || 915))}</li>
      </ol>`;
      const sz = OB.sizing(s.z, s.stop);
      const lot = r.lot ? ` · 1 lot = ${fmt.int(r.lot)} shares, risk ${fmt.inr(r.lot * risk)} a lot` : '';
      h += `<div class="size">${sz.skip ? `<b class="neg">${esc(sz.note)}</b>`
        : `For ${fmt.inr(OB.riskAmount())} risk: <b>${fmt.int(sz.qty)} shares</b>, risking ${fmt.inr(sz.risk)}${sz.note ? ' · ' + esc(sz.note) : ''}`}${lot}<button data-open-settings>Change</button></div>`;
    }
    const p = c.plan;
    if (p) {
      const bits = [`Suggested ${fmt.hhmm(p.plan.createdT)} at Z ${fmt.num(p.plan.entry)}`];
      if (p.filledT) bits.push(`filled ${fmt.hhmm(p.filledT)} at ${fmt.num(p.fill)}`);
      if (p.exitT && p.exitPrice != null) bits.push(`out ${fmt.hhmm(p.exitT)} at ${fmt.num(p.exitPrice)}`);
      const z = p.size;
      if (z) bits.push(z.qty ? `${fmt.int(z.qty)} shares, ${p.exitT ? `net ${fmt.inr(z.net)} after charges` : `risking ${fmt.inr(z.risked)}`}` : `not taken: ${z.note}`);
      const res = p.r != null ? ` · <b class="${p.r >= 0 ? 'pos' : 'neg'}">${fmt.r(p.r)}</b>`
        : p.openR != null ? ` · <b class="${p.openR >= 0 ? 'pos' : 'neg'}">${fmt.r(p.openR)} open</b>` : '';
      h += `<div class="track"><span class="status s-${p.status}">${statusText[p.status]}</span>${res}<div class="muted">${bits.join(' · ')}</div></div>`;
    }
    els.plan.innerHTML = h + '</div>';
  }

  function renderFoot() {
    const i = list.indexOf(sym);
    els.foot.innerHTML = `<div class="pf"><button data-pop-step="-1" ${i <= 0 ? 'disabled' : ''}>‹ Prev</button>`
      + `<span>${i + 1} / ${list.length}</span><button data-pop-step="1" ${i < 0 || i >= list.length - 1 ? 'disabled' : ''}>Next ›</button></div>`;
  }

  function renderChart(c) {
    ensureChart();
    const s = c.row.setup;
    extra = s ? [s.z, s.stop, ...s.targets.map(t => t.price), ...s.behind.map(b => b.price)] : [];
    candles.setData(c.candles.map(k => ({ time: k.t + IST, open: k.o, high: k.h, low: k.l, close: k.c })));
    vwap.setData(c.vwap.map(p => ({ time: p.t + IST, value: p.v })));
    overlay.set(c);
    chart.timeScale().fitContent();
  }

  /* ---------- open, place, close ---------- */
  const rowFor = s => {
    const b = OB.state.board;
    return b && [...b.bullish, ...b.bearish].find(r => r.symbol === s);
  };
  const viewList = () => [...new Set($$('#view [data-pop]').map(e => e.dataset.pop))];

  function place() {
    if (pinned) {
      pop.style.left = pop.style.top = '';
      return;
    }
    if (anchor && !anchor.isConnected) anchor = $(`#view [data-pop="${CSS.escape(sym)}"]`);
    if (!anchor) return;
    const a = anchor.getBoundingClientRect(), w = pop.offsetWidth, h = pop.offsetHeight, g = 10;
    // terminal tiles and tickets open the card over the ladder, to their left
    const leftFirst = !!(anchor.closest && anchor.closest('#tx'));
    let left = leftFirst ? a.left - w - g : a.right + g;
    if (leftFirst && left < 8) left = a.right + g;
    if (!leftFirst && left + w > innerWidth - 8) left = a.left - w - g;
    if (left < 8) left = Math.max(8, Math.min(innerWidth - w - 8, a.left));
    const top = Math.max(8, Math.min(innerHeight - h - 8, a.top + a.height / 2 - h / 2));
    pop.style.left = left + 'px';
    pop.style.top = top + 'px';
  }

  function show(s) {
    sym = s;
    data = null;
    const r = rowFor(s);
    pop.classList.remove('bull', 'bear');
    if (r) {
      pop.classList.add(r.side);
      renderHead(r);
      renderStats(r);
    } else {
      els.head.innerHTML = `<div class="ph"><div class="who"><b>${esc(s)}</b><small>Loading…</small></div></div>`;
      els.stats.innerHTML = '';
    }
    els.plan.innerHTML = '';
    renderFoot();
    pop.hidden = false;
    place();
    load(s);
  }

  async function load(s) {
    const my = ++seq;
    let c;
    try {
      const res = await fetch('/api/chart/' + encodeURIComponent(s), { cache: 'no-store' });
      if (!res.ok) return;
      c = await res.json();
    } catch {
      return;
    }
    if (my !== seq || s !== sym || pop.hidden) return;
    data = c;
    pop.classList.remove('bull', 'bear');
    pop.classList.add(c.row.side);
    renderHead(c.row);
    renderStats(c.row);
    renderPlan(c);
    renderFoot();
    renderChart(c);
    place();
  }

  function open(s, l) {
    clearTimeout(hoverTimer);
    clearTimeout(leaveTimer);
    pinned = true;
    anchor = null;
    list = l && l.length ? l : [s];
    pop.classList.add('pinned');
    scrim.hidden = false;
    show(s);
  }

  function close() {
    pop.hidden = true;
    scrim.hidden = true;
    pop.classList.remove('pinned');
    pinned = false;
    anchor = null;
    sym = null;
    data = null;
    OB.emit('hover', null);
  }

  function step(d) {
    const i = list.indexOf(sym) + d;
    if (i >= 0 && i < list.length) show(list[i]);
  }

  /* ---------- live updates ---------- */
  OB.on('board', () => {
    if (sym && !pop.hidden) load(sym);
  });
  OB.on('quotes', q => {
    if (!data || pop.hidden || !candles) return;
    const v = q[data.row.symbol];
    const f = data.formingT != null && data.candles[data.candles.length - 1];
    if (v && f && f.t === data.formingT) {
      f.h = Math.max(f.h, v[0]);
      f.l = Math.min(f.l, v[0]);
      f.c = v[0];
      candles.update({ time: f.t + IST, open: f.o, high: f.h, low: f.l, close: f.c });
    }
    overlay.set(data);
  });

  /* ---------- input ---------- */
  document.addEventListener('click', e => {
    if (e.target.closest('[data-pop-close]')) return close();
    const st = e.target.closest('[data-pop-step]');
    if (st) return step(Number(st.dataset.popStep));
    if (e.target.closest('#pop')) return;
    const el = e.target.closest('[data-pop]');
    if (el) {
      e.preventDefault();
      open(el.dataset.pop, viewList());
    }
  });
  scrim.addEventListener('click', close);
  document.addEventListener('keydown', e => {
    if (pop.hidden) return;
    if (e.key === 'Escape') close();
    if (pinned && e.key === 'ArrowRight') step(1);
    if (pinned && e.key === 'ArrowLeft') step(-1);
  });
  document.addEventListener('mouseover', e => {
    if (pinned || !canHover()) return;
    if (e.target.closest('#pop')) {
      clearTimeout(leaveTimer);
      return;
    }
    const el = e.target.closest('[data-pop]');
    if (!el) return;
    clearTimeout(leaveTimer);
    OB.emit('hover', el.dataset.pop);
    if (el.dataset.pop === sym && !pop.hidden) return;
    clearTimeout(hoverTimer);
    hoverTimer = setTimeout(() => {
      anchor = el;
      list = viewList();
      pop.classList.remove('pinned');
      show(el.dataset.pop);
    }, 220);
  });
  document.addEventListener('mouseout', e => {
    if (pinned || !canHover()) return;
    const from = e.target.closest('[data-pop], #pop');
    const to = e.relatedTarget && e.relatedTarget.closest ? e.relatedTarget.closest('[data-pop], #pop') : null;
    if (!from || from === to) return;
    clearTimeout(hoverTimer);
    leaveTimer = setTimeout(() => {
      if (pinned) return;
      pop.hidden = true;
      anchor = null;
      sym = null;
      OB.emit('hover', null);
    }, 200);
  });

  /** Peek at a stock found under the pointer somewhere other than a card (a flow line, a ladder bar). */
  function peekAt(s, x, y) {
    if (pinned || !canHover()) return;
    clearTimeout(leaveTimer);
    const rect = { left: x - 10, right: x + 10, top: y - 10, bottom: y + 10, width: 20, height: 20 };
    anchor = { isConnected: true, getBoundingClientRect: () => rect };
    if (s === sym && !pop.hidden) {
      place();
      return;
    }
    clearTimeout(hoverTimer);
    hoverTimer = setTimeout(() => {
      list = [s];
      pop.classList.remove('pinned');
      show(s);
    }, 160);
  }

  function unpeek() {
    clearTimeout(hoverTimer);
    if (pinned || !anchor || anchor.nodeType) return;
    pop.hidden = true;
    anchor = null;
    sym = null;
  }

  OB.pop = { open, close, peekAt, unpeek };
})();
