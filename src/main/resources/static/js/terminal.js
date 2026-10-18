/* Terminal: the whole market on one screen.
   Desktop is a fixed stage proportioned on a 1379×862 reference: the market crest, sector chips,
   the flow ending at a NOW line, the ranked ladder with its top/bottom gates, bridges out to the
   contender tiles, and the setup tickets. Phones get the same pieces as stacked sections. */
(() => {
  'use strict';

  const { $, $$, fmt, esc, initials, col, rgba, names } = OB;
  const tone = x => x > 0 ? 'pos' : x < 0 ? 'neg' : '';
  const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
  const pad2 = n => String(n).padStart(2, '0');
  const WIDE = '(min-width: 1240px)';
  const wide = () => matchMedia(WIDE).matches;
  const calm = () => matchMedia('(prefers-reduced-motion: reduce)').matches;

  // reference stage, in px, that the desktop layout is proportioned on
  const REF = { w: 1379, h: 862 };
  const G = { thermo: 14, origin: 46, now: 424, ladder: 505, top: 84, bottom: 836 };

  let flow = null;
  let flowSeq = 0;
  let hover = null;        // stock under the pointer (tile, line, ladder bar) or found in search
  let sector = null;       // sector chip that is selected
  let replay = 1;          // 0 → 1 while the day's build replays
  let built = false;       // the build replays once when the terminal first opens
  let lines = [];          // screen polylines of ranked stocks, for the particles
  let gates = [];          // top / bottom gate rects, for the twinkles
  let geom = null;         // last paint's geometry, for pointer lookups
  let fxRaf = 0;
  let fxLast = 0;
  let paintRaf = 0;
  let ro = null;
  let mode = null;

  const secOf = sym => {
    const u = OB.state.universe;
    return u ? u.bySym[sym]?.sector : null;
  };
  const rowOf = sym => {
    const b = OB.state.board;
    return b && [...b.bullish, ...b.bearish].find(r => r.symbol === sym);
  };
  const openSec = b => Math.floor(Date.parse(b.session + 'T09:15:00+05:30') / 1000);
  const hash = s => { let h = 7; for (const c of s) h = (h * 31 + c.charCodeAt(0)) % 9973; return h / 9973; };

  /* ---------- data ---------- */
  function finals() {
    if (!flow) return [];
    return Object.entries(flow.s).filter(([, p]) => p.length).map(([s, p]) => ({ s, p, v: p[p.length - 1] }));
  }

  async function loadFlow() {
    const my = ++flowSeq;
    try {
      const r = await fetch('/api/flow', { cache: 'no-store' });
      if (!r.ok) return;
      const f = await r.json();
      if (my !== flowSeq) return;
      flow = f;
      if (!built && !calm()) {
        built = true;
        startReplay();
      } else {
        built = true;
        paint();
      }
      crestTails();
    } catch { /* the next board retries */ }
  }

  /* ---------- scale: zero sits where the bull/bear split puts it; each half is stretched near zero ---------- */
  function scale(b, all, top, bottom) {
    const q = (xs, f) => { const a = xs.slice().sort((x, y) => x - y); return a.length ? a[Math.min(a.length - 1, Math.floor(a.length * f))] : 0; };
    const upMax = Math.max(0.5, q(all.filter(f => f.v > 0).map(f => f.v), 0.97) * 1.2);
    const dnMax = Math.max(0.5, q(all.filter(f => f.v < 0).map(f => -f.v), 0.97) * 1.2);
    const n = b.bullCount + b.bearCount || 1;
    const zeroY = top + (bottom - top) * clamp(b.bullCount / n, 0.28, 0.72);
    const C = 0.25;
    const sl = (a, m) => Math.min(1, Math.log1p(a / C) / Math.log1p(m / C));
    const Y = v => v >= 0 ? zeroY - sl(v, upMax) * (zeroY - top) : zeroY + sl(-v, dnMax) * (bottom - zeroY);
    return { upMax, dnMax, zeroY, Y };
  }

  function sizeCanvas(cv, W, H) {
    const dpr = devicePixelRatio || 1;
    if (cv.width !== Math.round(W * dpr) || cv.height !== Math.round(H * dpr)) {
      cv.width = Math.round(W * dpr);
      cv.height = Math.round(H * dpr);
      cv.style.width = W + 'px';
      cv.style.height = H + 'px';
    }
    const ctx = cv.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, W, H);
    return ctx;
  }

  /** Thermometer, sector walls, every stock's thread to NOW, the ladder and its gates, the zero chain. */
  function paintStage(ctx, b, all, geo) {
    const { Y, zeroY, upMax, dnMax } = scale(b, all, geo.top, geo.bottom);
    const last = Math.max(1, ...all.map(f => f.p.length - 1));
    const shown = replay < 1 ? replay * last : last;
    const X = i => geo.x0 + i / last * (geo.now - geo.x0);
    const mono = col('--font-mono');
    const ranked = new Map([...b.bullish, ...b.bearish].map(r => [r.symbol, r]));
    const brushing = !!(hover || sector);
    const lit = s => hover ? s === hover : sector ? secOf(s) === sector : false;
    const k = v => v >= 0 ? '--pos' : '--neg';

    // thermometer: green above zero, red below, labels on a stretched % scale
    const tg = ctx.createLinearGradient(0, geo.top, 0, geo.bottom);
    tg.addColorStop(0, rgba('--pos', 0.9));
    tg.addColorStop((zeroY - geo.top) / (geo.bottom - geo.top), rgba('--pos', 0.35));
    tg.addColorStop((zeroY - geo.top) / (geo.bottom - geo.top) + 0.001, rgba('--neg', 0.35));
    tg.addColorStop(1, rgba('--neg', 0.9));
    ctx.fillStyle = tg;
    ctx.fillRect(geo.thermoX - 1.5, geo.top, 3, geo.bottom - geo.top);
    ctx.fillStyle = col('--text');
    ctx.fillRect(geo.thermoX - 5, Math.round(zeroY) - 1, 10, 2);
    ctx.font = `9px ${mono}`;
    ctx.textAlign = 'left';
    const labelled = [];
    const tick = (v, label) => {
      const y = Math.round(Y(v));
      if (labelled.some(q => Math.abs(q - y) < 11)) return;
      labelled.push(y);
      ctx.fillStyle = col('--faint');
      ctx.fillText(label, geo.thermoX + 7, y + 3);
      ctx.fillStyle = col('--line-2');
      ctx.fillRect(geo.thermoX + 3, y, 3, 1);
    };
    tick(0, '0');
    [0.2, 0.5, 1, 2, 3, 5, 8].filter(v => v <= upMax).forEach(v => tick(v, '+' + v));
    [0.2, 0.5, 0.75, 1, 1.5, 2, 3, 5].filter(v => v <= dnMax).forEach(v => tick(-v, '−' + v));

    // sector walls: each sector's average move, brightening toward NOW
    for (const s of b.sectors) {
      const y = Math.round(Y(s.chg));
      const on = sector === s.name;
      const g = ctx.createLinearGradient(geo.x0, 0, geo.now, 0);
      g.addColorStop(0, rgba('--wall', 0));
      g.addColorStop(0.5, rgba('--wall', on ? 0.3 : brushing ? 0.04 : 0.1));
      g.addColorStop(1, rgba('--wall', on ? 0.85 : brushing ? 0.08 : 0.32));
      ctx.fillStyle = g;
      ctx.fillRect(geo.x0, y - 1, geo.now - geo.x0, on ? 3 : 2);
    }

    // texture: every stock at every 5 minutes as a short dash (once the build is complete)
    if (replay >= 1) {
      const bw = (geo.now - geo.x0) / last;
      for (const f of all) {
        ctx.fillStyle = rgba(k(f.v), brushing && !lit(f.s) ? 0.025 : 0.065);
        f.p.forEach((v, i) => ctx.fillRect(X(i) - bw * 0.42, Y(v) - 0.6, bw * 0.84, 1.2));
      }
    }

    // threads
    const thread = (p, color, width, glow) => {
      const end = Math.min(p.length - 1, shown);
      if (end < 0) return;
      ctx.strokeStyle = color;
      ctx.lineWidth = width;
      ctx.shadowBlur = glow ? 6 : 0;
      ctx.shadowColor = color;
      ctx.beginPath();
      ctx.moveTo(geo.x0, zeroY);
      const whole = Math.floor(end);
      for (let i = 0; i <= whole; i++) {
        const px = X(i), py = Y(p[i]);
        if (i === 0) { ctx.lineTo(px, py); continue; }
        const qx = X(i - 1), qy = Y(p[i - 1]);
        ctx.quadraticCurveTo(qx, qy, (qx + px) / 2, (qy + py) / 2);
      }
      const fr = end - whole;
      if (fr > 0 && whole + 1 < p.length) ctx.lineTo(X(whole + fr), Y(p[whole] + (p[whole + 1] - p[whole]) * fr));
      else ctx.lineTo(X(whole), Y(p[whole]));
      ctx.stroke();
      ctx.shadowBlur = 0;
    };
    for (const f of all) {
      if (ranked.has(f.s) || lit(f.s)) continue;
      thread(f.p, rgba(k(f.v), brushing ? 0.05 : 0.15), 1, false);
    }
    lines = [];
    for (const f of all) {
      if (!ranked.has(f.s) || lit(f.s)) continue;
      thread(f.p, rgba(k(f.v), brushing ? 0.12 : 0.7), 1.2, !brushing);
      lines.push({ s: f.s, seed: hash(f.s), c: k(f.v), pts: f.p.slice(0, Math.floor(shown) + 1).map((v, i) => [X(i), Y(v)]) });
    }
    for (const f of all) if (lit(f.s)) thread(f.p, hover === f.s ? col('--text') : col(k(f.v)), hover === f.s ? 2.2 : 1.6, true);

    // order blocks where they formed, as hollow squares on the ranked threads
    const t0 = openSec(b);
    for (const r of ranked.values()) {
      const s = r.setup;
      if (!s) continue;
      const i = (s.zone.formedT - t0) / 300;
      if (i > shown) continue;
      const pc = r.ltp - r.chgAbs;
      const x = X(i), y = Y((s.z / pc - 1) * 100), w = 4 + (21 - r.rank) / 3;
      ctx.strokeStyle = rgba(r.side === 'bull' ? '--pos' : '--neg', brushing && !lit(r.symbol) ? 0.15 : 0.8);
      ctx.lineWidth = 1;
      ctx.strokeRect(Math.round(x - w / 2) + 0.5, Math.round(y - w / 2) + 0.5, Math.round(w), Math.round(w));
    }

    // NOW
    const ng = ctx.createLinearGradient(geo.now - 14, 0, geo.now + 14, 0);
    ng.addColorStop(0, rgba('--text', 0));
    ng.addColorStop(0.5, rgba('--text', 0.13));
    ng.addColorStop(1, rgba('--text', 0));
    ctx.fillStyle = ng;
    ctx.fillRect(geo.now - 14, geo.top, 28, geo.bottom - geo.top);
    ctx.fillStyle = rgba('--text', 0.75);
    ctx.fillRect(Math.round(geo.now), geo.top, 1, geo.bottom - geo.top);

    // the ladder: every stock in order of its move, bar length by the size of the move
    const sorted = all.slice().sort((a, z) => z.v - a.v);
    const n = sorted.length;
    const slot = i => geo.top + (n > 1 ? i / (n - 1) : 0.5) * (geo.bottom - geo.top);
    const index = new Map(sorted.map((f, i) => [f.s, i]));
    const maxAbs = Math.max(0.5, ...sorted.map(f => Math.abs(f.v)));
    const room = geo.lad - geo.now - 5;
    const grow = replay < 1 ? replay : 1;
    sorted.forEach((f, i) => {
      const edge = i < 20 || i >= n - 20;
      const len = Math.max(2, Math.pow(Math.abs(f.v) / maxAbs, 0.6) * room) * grow;
      ctx.fillStyle = hover === f.s ? col('--text') : rgba(k(f.v), brushing ? (lit(f.s) ? 0.95 : 0.08) : edge ? 0.9 : 0.32);
      ctx.fillRect(geo.now + 3, Math.round(slot(i)), len, Math.max(1, (geo.bottom - geo.top) / n * 0.55));
    });

    // gates around the top 20 and bottom 20
    gates = [];
    const gate = (i0, i1, kk, label, above) => {
      if (!geo.gateLabels) label = '';
      const y0 = slot(i0) - 4, y1 = slot(i1) + 5, x0 = geo.now + 1, x1 = geo.lad;
      gates.push({ x0, y0, x1, y1, k: kk });
      ctx.fillStyle = rgba(kk, 0.07);
      ctx.fillRect(x0, y0, x1 - x0, y1 - y0);
      ctx.strokeStyle = col(kk);
      ctx.lineWidth = 1.5;
      const c = 7;
      for (const [x, y, dx, dy] of [[x0, y0, c, c], [x1, y0, -c, c], [x0, y1, c, -c], [x1, y1, -c, -c]]) {
        ctx.beginPath(); ctx.moveTo(x + dx, y); ctx.lineTo(x, y); ctx.lineTo(x, y + dy); ctx.stroke();
      }
      ctx.font = `700 9px ${mono}`;
      ctx.fillStyle = col(kk);
      ctx.textAlign = 'right';
      ctx.fillText(label, x1, above ? y0 - 5 : y1 + 12);
      ctx.textAlign = 'left';
    };
    if (n) {
      gate(0, Math.min(19, n - 1), '--pos', '▲ TOP', true);
      gate(Math.max(0, n - 20), n - 1, '--neg', '▼ TOP', false);
    }

    // the zero chain: origin → NOW → ladder
    ctx.strokeStyle = rgba('--text', 0.55);
    ctx.lineWidth = 1.2;
    ctx.beginPath(); ctx.moveTo(geo.x0, zeroY); ctx.lineTo(geo.lad, zeroY); ctx.stroke();
    const og = ctx.createRadialGradient(geo.x0, zeroY, 0, geo.x0, zeroY, 16);
    og.addColorStop(0, rgba('--text', 0.85));
    og.addColorStop(1, rgba('--text', 0));
    ctx.fillStyle = og;
    ctx.beginPath(); ctx.arc(geo.x0, zeroY, 16, 0, Math.PI * 2); ctx.fill();
    ctx.strokeStyle = col('--text');
    ctx.lineWidth = 1.2;
    ctx.beginPath(); ctx.arc(geo.x0, zeroY, 4.5, 0, Math.PI * 2); ctx.stroke();
    ctx.fillStyle = col('--text');
    ctx.save();
    ctx.translate(geo.now, zeroY);
    ctx.rotate(Math.PI / 4);
    ctx.fillRect(-3.5, -3.5, 7, 7);
    ctx.restore();
    ctx.beginPath(); ctx.arc(geo.lad, zeroY, 3.5, 0, Math.PI * 2); ctx.fill();
    ctx.font = `10px ${mono}`;
    ctx.fillStyle = col('--muted');
    ctx.textAlign = 'right';
    ctx.fillText(String(n), geo.now - 7, zeroY - 6);
    ctx.textAlign = 'left';
    ctx.fillText(String(b.bullish.length + b.bearish.length), geo.lad + 7, zeroY - 6);

    // time axis: the open, each hour, and now
    ctx.font = `9.5px ${mono}`;
    ctx.fillStyle = col('--faint');
    const at = idx => X(idx * last / last);
    ctx.fillText('09:15', geo.x0 - 12, geo.timeY);
    for (let h = 10; h <= 15; h++) {
      const i = (h * 60 - 555) / 5;
      if (at(i) > geo.now - 46) break;
      ctx.fillText(`${h}:00`, at(i) - 13, geo.timeY);
    }
    ctx.fillStyle = col('--text');
    ctx.font = `600 9.5px ${mono}`;
    ctx.fillText(fmt.hhmm(t0 + Math.round(shown) * 300), geo.now - 14, geo.timeY);

    geom = { ...geo, X, Y, zeroY, last, sorted, slot, index };
    return geom;
  }

  /* ---------- desktop paint: the stage, then bridges out to the tiles and the chain to the tickets ---------- */
  function paintWide() {
    const tx = $('#tx'), cv = $('#tx-cv'), b = OB.state.board;
    if (!tx || !cv || !b) return;
    const R = tx.getBoundingClientRect();
    if (!R.width || !R.height) return;
    const W = R.width, H = R.height, sx = W / REF.w, sy = H / REF.h;
    const ctx = sizeCanvas(cv, W, H);
    const geo = { thermoX: G.thermo * sx, x0: G.origin * sx, now: G.now * sx, lad: G.ladder * sx, top: G.top * sy, bottom: G.bottom * sy, timeY: G.bottom * sy + 18, W, H };
    const st = paintStage(ctx, b, finals(), geo);
    const rel = el => { const r = el.getBoundingClientRect(); return { x: r.left - R.left, y: r.top - R.top, w: r.width, h: r.height }; };
    const brushing = !!(hover || sector);
    const lit = s => hover ? s === hover : sector ? secOf(s) === sector : false;

    // bridges: from each ranked stock's ladder bar to its tile
    for (const t of $$('#tx .tl[data-rank]')) {
      const i = st.index.get(t.dataset.pop);
      if (i == null) continue;
      const r = rel(t), kk = t.dataset.side === 'bull' ? '--pos' : '--neg';
      const sy0 = st.slot(i), ty = r.y + r.h / 2, mx = (geo.lad + r.x) / 2;
      const on = lit(t.dataset.pop);
      ctx.strokeStyle = on ? col('--text') : rgba(kk, brushing ? 0.08 : 0.42 * (replay < 1 ? replay : 1));
      ctx.lineWidth = on ? 1.6 : 1;
      ctx.beginPath(); ctx.moveTo(geo.lad, sy0); ctx.bezierCurveTo(mx, sy0, mx, ty, r.x, ty); ctx.stroke();
      ctx.fillStyle = on ? col('--text') : col(kk);
      ctx.beginPath(); ctx.arc(r.x, ty, 1.7, 0, Math.PI * 2); ctx.fill();
    }

    // the zero chain carries on between the decks to the setups reactor
    const bull = rel($('#tx-bull')), bear = rel($('#tx-bear'));
    const dy = (bull.y + bull.h + bear.y) / 2;
    const tick = rel($('#tx-bull .col.tks'));
    const rx = tick.x - 10;
    ctx.strokeStyle = rgba('--text', 0.5);
    ctx.lineWidth = 1.2;
    ctx.beginPath();
    ctx.moveTo(geo.lad, st.zeroY);
    ctx.bezierCurveTo((geo.lad + bull.x) / 2, st.zeroY, (geo.lad + bull.x) / 2, dy, bull.x - 4, dy);
    ctx.lineTo(W - 4, dy);
    ctx.stroke();
    ctx.fillStyle = col('--text');
    ctx.beginPath(); ctx.arc(bull.x - 4, dy, 3.5, 0, Math.PI * 2); ctx.fill();
    // reactor: rings around the setups count
    const picks = [...b.bullish, ...b.bearish].filter(r => r.pick).length;
    for (const [rad, a] of [[11, 0.25], [7, 0.5]]) {
      ctx.strokeStyle = rgba('--text', a);
      ctx.beginPath(); ctx.arc(rx, dy, rad, 0, Math.PI * 2); ctx.stroke();
    }
    ctx.beginPath(); ctx.arc(rx, dy, 3, 0, Math.PI * 2); ctx.fill();
    ctx.font = `600 10px ${col('--font-mono')}`;
    ctx.fillText(String(picks), rx + 15, dy - 6);

    // plugs: each pick tile wired to its ticket
    for (const tk of $$('#tx .tk[data-sym]')) {
      const tile = $(`#tx .tl.c1[data-pop="${CSS.escape(tk.dataset.sym)}"]`);
      if (!tile) continue;
      const a = rel(tile), z = rel(tk), y = a.y + Math.min(a.h, z.h) / 2, kk = tk.classList.contains('bull') ? '--pos' : '--neg';
      ctx.strokeStyle = col(kk);
      ctx.lineWidth = 1.5;
      ctx.beginPath(); ctx.moveTo(a.x + a.w + 3, y); ctx.lineTo(z.x - 1, y); ctx.stroke();
      ctx.fillStyle = col(kk);
      ctx.fillRect(a.x + a.w + 2, y - 3, 3, 6);
      ctx.fillRect(z.x - 4, y - 3, 3, 6);
    }
  }

  function paintNarrow() {
    const box = $('#ph-flow'), cv = $('#ph-cv'), b = OB.state.board;
    if (!box || !cv || !b) return;
    const W = box.clientWidth, H = box.clientHeight;
    if (!W || !H) return;
    const ctx = sizeCanvas(cv, W, H);
    paintStage(ctx, b, finals(), { thermoX: 10, x0: 42, now: W - 92, lad: W - 10, top: 22, bottom: H - 30, timeY: H - 10, W, H, gateLabels: true });
  }

  function paint() {
    if (OB.state.view !== 'terminal') return;
    if (wide()) paintWide(); else paintNarrow();
    kickFx();
  }

  function repaintSoon() {
    if (paintRaf) return;
    paintRaf = requestAnimationFrame(() => {
      paintRaf = 0;
      paint();
    });
  }

  /* ---------- motion: particles along the ranked threads, twinkles in the gates ---------- */
  function kickFx() {
    if (!fxRaf && !calm()) fxRaf = requestAnimationFrame(fx);
  }

  function fx(ts) {
    fxRaf = 0;
    const cv = $(wide() ? '#tx-fx' : '#ph-fx');
    if (!cv || OB.state.view !== 'terminal' || document.hidden) return;
    if (ts - fxLast > 33) {
      fxLast = ts;
      const W = cv.parentElement.clientWidth, H = cv.parentElement.clientHeight;
      const ctx = sizeCanvas(cv, W, H);
      for (const ln of lines) {
        if (ln.pts.length < 2 || (hover && ln.s !== hover)) continue;
        const p = (ts / 9000 + ln.seed) % 1, f = p * (ln.pts.length - 1), i = Math.floor(f), t = f - i;
        const a = ln.pts[i], z = ln.pts[Math.min(i + 1, ln.pts.length - 1)];
        const x = a[0] + (z[0] - a[0]) * t, y = a[1] + (z[1] - a[1]) * t;
        ctx.fillStyle = col(ln.c);
        ctx.shadowColor = col(ln.c);
        ctx.shadowBlur = 8;
        ctx.beginPath(); ctx.arc(x, y, 1.7, 0, Math.PI * 2); ctx.fill();
      }
      ctx.shadowBlur = 0;
      for (const g of gates) {
        for (let j = 0; j < 6; j++) {
          const s = hash(g.k + j), a = 0.5 + 0.5 * Math.sin(ts / 600 + j * 1.7);
          ctx.fillStyle = rgba(g.k, 0.6 * a);
          ctx.fillRect(g.x0 + 6 + s * (g.x1 - g.x0 - 12), g.y0 + 4 + ((s * 7.3 + j * 0.13) % 1) * (g.y1 - g.y0 - 8), 1.5, 1.5);
        }
      }
    }
    fxRaf = requestAnimationFrame(fx);
  }

  function startReplay() {
    if (calm()) {
      replay = 1;
      paint();
      return;
    }
    const t0 = performance.now(), D = 2600;
    replay = 0;
    const step = now => {
      replay = Math.min(1, (now - t0) / D);
      paint();
      if (replay < 1) requestAnimationFrame(step);
    };
    requestAnimationFrame(step);
  }

  /* ---------- crest: every stock as a dot at its move, with where it was half an hour ago ---------- */
  function crest(el, b) {
    if (!el) return;
    const svg = $('svg', el), W = svg.clientWidth || 480, H = svg.clientHeight || 70, mid = Math.round(H * 0.62);
    const ext = Math.max(2, Math.ceil(Math.max(1, ...b.dots.map(d => Math.abs(d.c)))));
    const X = c => 20 + (clamp(c, -ext, ext) + ext) / (2 * ext) * (W - 40);
    const picks = new Set([...b.bullish, ...b.bearish].filter(r => r.pick).map(r => r.symbol));
    const inSec = s => sector && secOf(s) === sector;
    const stacks = new Map();
    let s = `<line class="ax" x1="10" x2="${W - 10}" y1="${mid}" y2="${mid}"/>`
      + `<text class="axl" x="0" y="${mid + 3}">−${ext}%</text><text class="axl" x="${W}" y="${mid + 3}" text-anchor="end">+${ext}%</text>`
      + `<rect class="pz" x="${X(0) - 7}" y="4" width="14" height="${H - 8}"/><line class="ax0" x1="${X(0)}" x2="${X(0)}" y1="4" y2="${H - 4}"/>`;
    let tails = '';
    const tailFrom = sym => {
      const p = flow && flow.s[sym];
      return p && p.length > 7 ? p[p.length - 7] : null;
    };
    for (const d of [...b.dots].sort((a, z) => Math.abs(a.c) - Math.abs(z.c))) {
      const x = X(d.c), up = d.c >= 0;
      const key = Math.round(x / 4.4) * (up ? 1 : -1);
      const k = stacks.get(key) || 0;
      stacks.set(key, k + 1);
      const y = up ? mid - 5 - k * 4.2 : mid + 5 + k * 4.2;
      if (y < 3 || y > H - 3) continue;
      const was = tailFrom(d.s);
      if (was != null && Math.abs(X(was) - x) > 3) tails += `<line class="tail ${up ? 'u' : 'd'}" x1="${X(was).toFixed(1)}" x2="${x.toFixed(1)}" y1="${y.toFixed(1)}" y2="${y.toFixed(1)}"/>`;
      s += `<circle data-pop="${esc(d.s)}" class="${up ? 'u' : 'd'}${inSec(d.s) ? ' hot' : ''}" cx="${x.toFixed(1)}" cy="${y.toFixed(1)}" r="${inSec(d.s) ? 3 : 1.8}"><title>${esc(d.s)} ${fmt.pct(d.c)}</title></circle>`;
    }
    const lead = b.sectors[0], lag = b.sectors[b.sectors.length - 1];
    const full = el.clientWidth || W;
    const dia = (sec, tagX) => sec ? `<rect class="dia" x="${(X(sec.chg) - 3.5).toFixed(1)}" y="${mid - 3.5}" width="7" height="7" transform="rotate(45 ${X(sec.chg).toFixed(1)} ${mid})"/>`
      + `<path class="lead" d="M${tagX.toFixed(1)} 1H${X(sec.chg).toFixed(1)}V${mid - 5}"/>` : '';
    let rings = '';
    for (const d of b.dots.filter(d => picks.has(d.s))) {
      rings += `<circle data-pop="${esc(d.s)}" class="ring ${d.c >= 0 ? 'u' : 'd'}" cx="${X(d.c).toFixed(1)}" cy="${mid}" r="5.5"><title>${esc(d.s)} ${fmt.pct(d.c)}</title></circle>`;
    }
    svg.setAttribute('viewBox', `0 0 ${W} ${H}`);
    svg.innerHTML = s.replace('</text><rect', '</text>' + tails + '<rect') + dia(lead, full * 0.5) + dia(lag, full * 0.04) + rings;
    const tag = (sec, up) => {
      if (!sec) return '';
      return `<div class="ctag ${up ? 'up' : 'dn'}">`
        + (up ? '<span class="arr pos">↗</span>' : '') + `<span class="k">${up ? 'Leading' : 'Lagging'} sector</span><b>${esc(sec.name)}</b>`
        + `<span class="${tone(sec.chg)}">${fmt.pct(sec.chg)}</span>` + (up ? '' : '<span class="arr neg">↘</span>') + '</div>';
    };
    $('.ctags', el).innerHTML = tag(lag, false) + tag(lead, true);
    $('.ccount', el).innerHTML = `<div><span class="k pos">▲ Bullish</span><b class="pos">${b.bullCount}</b></div><div><span class="k neg">▼ Bearish</span><b class="neg">${b.bearCount}</b></div>`;
  }

  function crestTails() {
    const b = OB.state.board;
    if (b) crest($(wide() ? '#tx-crest' : '#ph-crest'), b);
  }

  const crestHtml = id => `<div class="crest" id="${id}"><div class="ctags"></div><svg class="cdots"></svg><div class="ccount"></div></div>`;

  /* ---------- sector chips and the sector sheet ---------- */
  const chip = s => `<button class="chip ${s.chg >= 0 ? 'up' : 'dn'}${sector === s.name ? ' hot' : ''}" data-sector="${esc(s.name)}">`
    + `<span class="r1"><span class="cap">${esc(s.name)}</span><b class="${tone(s.chg)}">${fmt.pct(s.chg)}</b></span>`
    + `<span class="strip">${s.moves.map(m => `<i class="${m >= 0 ? 'u' : ''}"></i>`).join('')}</span></button>`;

  function sectorSheet(name) {
    const b = OB.state.board, u = OB.state.universe;
    const s = b && b.sectors.find(x => x.name === name);
    if (!s || !u) return;
    const chg = new Map(b.dots.map(d => [d.s, d.c]));
    const status = sym => {
      const r = rowOf(sym);
      if (!r) return '<span class="st hd">Herd</span>';
      if (r.pick) return `<span class="st ${r.side}">${r.side === 'bull' ? '▲ Bullish' : '▼ Bearish'} #${r.pick}</span>`;
      return `<span class="st c ${r.side}">Contender #${r.rank}</span>`;
    };
    const list = u.list.filter(x => x.sector === name).sort((a, z) => (chg.get(z.symbol) ?? 0) - (chg.get(a.symbol) ?? 0));
    let sh = $('#ssheet');
    if (!sh) {
      sh = document.createElement('div');
      sh.id = 'ssheet';
      sh.className = 'ssheet';
      document.body.appendChild(sh);
      sh.addEventListener('click', e => {
        if (e.target === sh || e.target.closest('[data-ss-close]')) closeSector();
      });
    }
    sh.innerHTML = `<div class="ss-card">
      <header><div><b>${esc(name)}</b> <span class="${tone(s.chg)}">${fmt.pct(s.chg)}</span>
        <small>${list.length} stocks · <span class="pos">▲${s.up}</span> <span class="neg">▼${s.down}</span></small></div>
        <button class="icon-btn" data-ss-close aria-label="Close"><svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button></header>
      <div class="ss-bar">${s.moves.map(m => `<i class="${m >= 0 ? 'u' : ''}"></i>`).join('')}</div>
      <div class="ss-list">${list.map(x => `<div class="ss-row" data-pop="${esc(x.symbol)}"><b>${esc(x.symbol)}</b><span class="nm">${esc(x.name)}</span>`
        + `<span class="num ${tone(chg.get(x.symbol))}">${fmt.pct(chg.get(x.symbol) ?? 0)}</span>${status(x.symbol)}</div>`).join('')}</div>
    </div>`;
    sh.hidden = false;
  }

  function closeSector() {
    const sh = $('#ssheet');
    if (sh) sh.hidden = true;
    selectSector(null);
  }

  function selectSector(name) {
    sector = name;
    $$('.chip[data-sector]').forEach(c => c.classList.toggle('hot', c.dataset.sector === name));
    const tx = $('#tx');
    if (tx) tx.classList.toggle('brush', !!(hover || sector));
    $$('#tx .tl, #tx .tk').forEach(t => t.classList.toggle('lit', !!sector && secOf(t.dataset.pop || t.dataset.sym) === sector));
    crestTails();
    repaintSoon();
  }

  /* ---------- tiles: contenders, sized by rank; picks get a reticle ---------- */
  const ROWS = { c4: 6, c3: 10, c2: 14, c1: 20, ph: 10 };

  function profile(r, size, tag) {
    const p = r.profile;
    if (!p) return '<div class="vp2"></div>';
    const bull = r.side === 'bull';
    const n = ROWS[size] || 12, g = Math.ceil(p.bins.length / n);
    const up = [], dn = [];
    for (let i = 0; i < p.bins.length; i += g) {
      up.push(p.up.slice(i, i + g).reduce((a, b) => a + b, 0));
      dn.push(p.dn.slice(i, i + g).reduce((a, b) => a + b, 0));
    }
    const max = Math.max(1, ...up.map((u, i) => u + dn[i]));
    let rows = '';
    for (let i = up.length - 1; i >= 0; i--) {
      const tot = up[i] + dn[i], len = Math.sqrt(tot / max) * 100;
      const w = tot ? len * (bull ? up[i] : dn[i]) / tot : 0;
      rows += `<div><i class="w" style="width:${w.toFixed(1)}%"></i><i class="a" style="width:${(len - w).toFixed(1)}%"></i></div>`;
    }
    const y = clamp((1 - (r.ltp - p.lo) / ((p.hi - p.lo) || 1)) * 100, 0, 100);
    return `<div class="vp2" style="--y:${y.toFixed(1)}%">${rows}<b class="vp-line"></b>`
      + (tag ? `<span class="vp-tag" data-ltp="${esc(r.symbol)}">${fmt.num(r.ltp)}</span>` : '') + '</div>';
  }

  const pips = c => `<span class="pips">${[1, 2, 3, 4, 5].map(i => `<i class="${Math.round(c / 20) >= i ? 'on' : ''}"></i>`).join('')}</span>`;

  function tile(r, size) {
    const sym = esc(r.symbol), pick = size === 'c1';
    const meta = pick ? `<div class="tl-m">${pips(r.conv)}${OB.buildUpTag(r.buildUp)}</div>`
      : size === 'c2' && r.buildUp ? `<div class="tl-m">${OB.buildUpTag(r.buildUp)}</div>` : '';
    return `<article class="tl ${size} ${r.side}${pick ? ' pk' : ''}" data-pop="${sym}" data-rank="${r.rank}" data-side="${r.side}">`
      + (pick ? '<i class="ret"><b></b><b></b><b></b><b></b></i>' : '')
      + '<i class="br a"></i><i class="br b"></i><i class="br c"></i><i class="br d"></i>'
      + `<div class="tl-h">${pick ? `<span class="av">${initials(r.symbol)}</span>` : ''}<div class="tl-p"><b class="tkr">${sym}</b>`
      + `<span class="pc ${tone(r.chg)}" data-chg="${sym}">${fmt.pct(r.chg)}</span></div><span class="rk">${pad2(r.rank)}</span></div>`
      + meta + profile(r, size, pick || size === 'c2') + '</article>';
  }

  /* ---------- tickets: the setups, with the plan ---------- */
  function ring(c) {
    const r = 9, L = 2 * Math.PI * r;
    return `<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="${r}" class="rb"/><circle cx="12" cy="12" r="${r}" class="rf" stroke-dasharray="${(c / 100 * L).toFixed(1)} ${L.toFixed(1)}" transform="rotate(-90 12 12)"/></svg>`;
  }

  function ticket(r, p) {
    const s = r.setup, n = names(r.side), sym = esc(r.symbol), bull = r.side === 'bull', a = r.advice.action;
    let plan = '';
    if (s) {
      const risk = Math.abs(s.z - s.stop), riskPct = risk / s.z * 100;
      const rr = Math.min(s.rr, 6), stopShare = 1 / (rr + 1);
      const t1Share = Math.min(1, Math.abs(s.targets[0].price - s.z) / (Math.abs(s.targets[1].price - s.z) || 1));
      plan = `<div class="cells">
          <div class="pcell sl"><span>${n.stop}</span><b>${fmt.num(s.stop)}</b></div>
          <div class="pcell en"><span>Zone</span><b>${fmt.num(s.zone.lo)}–${fmt.num(s.zone.hi)}</b></div>
          <div class="pcell t1"><span>${n.t[0]}</span><b>${fmt.num(s.targets[0].price)}</b></div>
          <div class="pcell t2"><span>${n.t[1]}</span><b>${fmt.num(s.targets[1].price)}</b></div>
          <div class="pcell rr"><span>Risk ${riskPct.toFixed(2)}%</span><b>${fmt.rr(s.rr)}</b></div>
        </div>
        <div class="rrbar"><i class="r0" style="width:${(stopShare * 100).toFixed(1)}%"></i><i class="r1"></i>`
        + `<i class="tk1" style="left:${((stopShare + (1 - stopShare) * t1Share) * 100).toFixed(1)}%"></i></div>`;
    }
    return `<div class="tk ${r.side} p${p} ${p === 1 ? 'big' : 'std'}" data-pop="${sym}" data-sym="${sym}">
      <i class="tk-acc"></i>
      <div class="tk-info">
        <div class="tk-a"><span class="av">${initials(r.symbol)}</span><div class="tk-id"><b class="tkr">${sym}</b><small>${esc(r.name)} · ${esc(r.sector)}</small></div><span class="act ${a}">${a}</span></div>
        <div class="tk-b"><span class="pill">${n.word.toUpperCase()}</span><span class="ring">${ring(r.conv)}<b>${r.conv}</b></span>${pips(r.conv)}</div>
        <div class="tk-c ${tone(r.chg)}" data-chg="${sym}">${fmt.pct(r.chg)}</div>
        <div class="tk-d"><span>₹<b data-ltp="${sym}">${fmt.num(r.ltp)}</b></span><span class="${tone(r.chg)}">${fmt.chg(r.chgAbs)}</span><span class="pctl">P${r.pct.toFixed(1)}</span></div>
      </div>
      <div class="tk-chart"><canvas></canvas></div>
      <div class="tk-plan">${plan || `<span class="muted">${esc(r.advice.reason)}</span>`}</div>
    </div>`;
  }

  const noTicket = side => `<div class="tk none ${side}"><span class="lbl">No ${side === 'bull' ? 'bullish' : 'bearish'} setup</span><small>Picks need a top-20 rank and a live order block</small></div>`;

  /** Candles, the zone, and the setup's levels labelled on the right. */
  function drawTicketCharts(root) {
    for (const tk of $$('.tk[data-sym]', root)) {
      const r = rowOf(tk.dataset.sym), cv = $('canvas', tk);
      if (!r || !cv) continue;
      const box = cv.parentElement, W = box.clientWidth, H = box.clientHeight;
      if (!W || !H) continue;
      const ctx = sizeCanvas(cv, W, H);
      const bars = r.bars || [];
      const s = r.setup, n = names(r.side), kk = r.side === 'bull' ? '--pos' : '--neg';
      if (bars.length < 2) continue;
      const lv = s ? [[s.targets[1].price, n.t[1], col(kk)], [s.targets[0].price, n.t[0], col(kk)], [s.stop, n.stop, col('--stop')]] : [];
      const vals = [...bars.flatMap(c => [c.h, c.l]), ...lv.map(l => l[0]), ...(s ? [s.zone.lo, s.zone.hi, s.z] : [])];
      const lo = Math.min(...vals), hi = Math.max(...vals), pad = (hi - lo) * 0.06 || 1;
      const L = 2, Rr = W - 58, T = 4, B = H - 4;
      const Y = v => T + (hi + pad - v) / (hi - lo + 2 * pad) * (B - T);
      const bw = (Rr - L) / 75;
      const X = i => L + (i + 0.5) * bw;
      const t0 = bars[0].t;
      ctx.strokeStyle = rgba('--line', 0.6);
      ctx.lineWidth = 1;
      for (let i = 1; i < 4; i++) { const y = Math.round(T + i * (B - T) / 4) + 0.5; ctx.beginPath(); ctx.moveTo(L, y); ctx.lineTo(W, y); ctx.stroke(); }
      if (s) {
        const zx = X((s.zone.t - t0) / 300) - bw / 2, zy = Y(s.zone.hi), zh = Math.max(2, Y(s.zone.lo) - zy);
        ctx.fillStyle = rgba(kk, 0.13);
        ctx.fillRect(zx, zy, W - zx, zh);
        ctx.strokeStyle = rgba(kk, 0.85);
        ctx.strokeRect(Math.round(zx) + 0.5, Math.round(zy) + 0.5, Math.max(4, X((s.zone.formedT - t0) / 300) - zx + bw / 2), Math.round(zh));
      }
      bars.forEach((c, i) => {
        const up = c.c >= c.o, x = X(i);
        ctx.strokeStyle = ctx.fillStyle = col(up ? '--pos' : '--neg');
        ctx.beginPath(); ctx.moveTo(Math.round(x) + 0.5, Y(c.h)); ctx.lineTo(Math.round(x) + 0.5, Y(c.l)); ctx.stroke();
        const y1 = Y(Math.max(c.o, c.c)), y2 = Y(Math.min(c.o, c.c));
        ctx.fillRect(x - Math.max(1, bw * 0.35), y1, Math.max(2, bw * 0.7), Math.max(1, y2 - y1));
      });
      ctx.font = `600 9px ${col('--font-mono')}`;
      const used = [];
      const label = (price, name, color, dash) => {
        const y = Y(price);
        ctx.setLineDash(dash);
        ctx.strokeStyle = color;
        ctx.beginPath(); ctx.moveTo(s ? X((s.zone.formedT - t0) / 300) : L, Math.round(y) + 0.5); ctx.lineTo(W - 56, Math.round(y) + 0.5); ctx.stroke();
        ctx.setLineDash([]);
        let ly = y;
        for (const d of [0, -10, 10, -20, 20]) if (!used.some(q => Math.abs(q - (y + d)) < 10)) { ly = y + d; break; }
        used.push(ly);
        ctx.fillStyle = color;
        ctx.fillText(`${name} ${fmt.num(price)}`, W - 54, ly + 3);
      };
      lv.forEach(([p, nm, c]) => label(p, nm, c, [3, 3]));
      if (s) label(s.z, 'Z', col('--z'), []);
      const lastBar = bars[bars.length - 1], q = OB.state.quotes[r.symbol];
      const px = X(bars.length - 1), py = Y(q ? q[0] : lastBar.c);
      ctx.fillStyle = rgba('--text', 0.2);
      ctx.beginPath(); ctx.arc(px, py, 5, 0, Math.PI * 2); ctx.fill();
      ctx.fillStyle = col('--text');
      ctx.beginPath(); ctx.arc(px, py, 2.5, 0, Math.PI * 2); ctx.fill();
    }
  }

  /* ---------- decks ---------- */
  function columns(rows, side) {
    const bear = side === 'bear';
    const picks = rows.filter(r => r.pick).sort((a, z) => a.pick - z.pick);
    const rest = rows.filter(r => !r.pick);
    const flip = list => (bear ? list.slice().reverse() : list);
    return {
      c4: flip(rest.slice(9, 17)),
      c3: flip(rest.slice(4, 9)),
      c2: flip(rest.slice(0, 4)),
      c1: picks,
      tk: picks,
    };
  }

  function deck(el, rows, side) {
    const c = columns(rows, side);
    $('.col.c4', el).innerHTML = c.c4.map(r => tile(r, 'c4')).join('');
    $('.col.c3', el).innerHTML = c.c3.map(r => tile(r, 'c3')).join('');
    $('.col.c2', el).innerHTML = c.c2.map(r => tile(r, 'c2')).join('');
    $('.col.c1', el).innerHTML = c.c1.map(r => tile(r, 'c1')).join('')
      + Array.from({ length: 3 - c.c1.length }, () => `<div class="tl c1 none ${side}"></div>`).join('');
    $('.col.tks', el).innerHTML = c.tk.map((r, i) => ticket(r, i + 1)).join('') + Array.from({ length: 3 - c.tk.length }, () => noTicket(side)).join('');
  }

  /* ---------- desktop ---------- */
  const deckHtml = (side, id) => `<div class="tx-deck ${side}" id="${id}"><i class="brk tl"></i><i class="brk tr"></i><i class="brk bl"></i><i class="brk br"></i>`
    + '<div class="col c4"></div><div class="col c3"></div><div class="col c2"></div><div class="col c1"></div><div class="col tks"></div></div>';

  const wideSkeleton = `<section class="tx" id="tx">
    <canvas id="tx-cv"></canvas>
    <div class="tx-fxw"><canvas id="tx-fx"></canvas></div>
    ${crestHtml('tx-crest')}
    <div class="tx-chips up" id="tx-chips-up"></div>
    <div class="tx-chips dn" id="tx-chips-dn"></div>
    <div class="tx-flowhd"><button class="replay" data-replay title="Replay the build" aria-label="Replay the build">
      <svg viewBox="0 0 24 24"><path d="M4 12a8 8 0 1 0 2.3-5.6M4 4v4h4"/></svg></button></div>
    ${deckHtml('bull', 'tx-bull')}
    ${deckHtml('bear', 'tx-bear')}
  </section>`;

  function lookup(e) {
    const g = geom, tx = $('#tx');
    if (!g || !tx || !flow) return null;
    const R = tx.getBoundingClientRect(), x = e.clientX - R.left, y = e.clientY - R.top;
    if (y < g.top - 6 || y > g.bottom + 6) return null;
    if (x >= g.x0 && x <= g.now) {
      const i = clamp(Math.round((x - g.x0) / (g.now - g.x0) * g.last), 0, g.last);
      let best = null, d = 7;
      for (const f of g.sorted) {
        if (i >= f.p.length) continue;
        const dd = Math.abs(g.Y(f.p[i]) - y);
        if (dd < d) { d = dd; best = f.s; }
      }
      return best;
    }
    if (x > g.now && x <= g.lad) {
      const n = g.sorted.length;
      const i = clamp(Math.round((y - g.top) / (g.bottom - g.top) * (n - 1)), 0, n - 1);
      return g.sorted[i]?.s ?? null;
    }
    return null;
  }

  function bindWide(el) {
    const tx = $('#tx', el);
    tx.addEventListener('pointermove', e => {
      if (e.target.closest('[data-pop], .chip, .tx-flowhd')) return;
      const s = lookup(e);
      setHover(s);
      if (s) OB.pop.peekAt(s, e.clientX, e.clientY);
      else OB.pop.unpeek();
    });
    tx.addEventListener('pointerleave', () => {
      OB.pop.unpeek();
      setHover(null);
    });
    tx.addEventListener('click', e => {
      if (e.target.closest('[data-pop], .chip, button')) return;
      const s = lookup(e);
      if (s) OB.pop.open(s, [s]);
    });
  }

  function renderWide(el, b) {
    if (!$('#tx', el)) {
      el.innerHTML = wideSkeleton;
      bindWide(el);
    }
    crest($('#tx-crest'), b);
    $('#tx-chips-up').innerHTML = b.sectors.filter(s => s.chg >= 0).map(chip).join('');
    $('#tx-chips-dn').innerHTML = b.sectors.filter(s => s.chg < 0).map(chip).join('');
    deck($('#tx-bull'), b.bullish, 'bull');
    deck($('#tx-bear'), b.bearish, 'bear');
    requestAnimationFrame(() => drawTicketCharts($('#tx')));
  }

  /* ---------- phone ---------- */
  const narrowSkeleton = `<section class="phx">
    <nav class="ph-nav">
      <button data-go="ph-market" class="on"><span class="c">Market</span><span class="mb"><i class="u"></i><i class="d"></i></span></button>
      <button data-go="ph-flow"><span class="c">Flow</span><b id="ph-n"></b></button>
      <button data-go="ph-bull" class="up"><span class="c"><span class="pos">▲</span> Top</span><b class="pos" id="ph-nb"></b></button>
      <button data-go="ph-bear" class="dn"><span class="c"><span class="neg">▼</span> Top</span><b class="neg" id="ph-ns"></b></button>
      <button data-find aria-label="Find a stock"><svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><path d="M20 20l-4-4"/></svg></button>
      <div class="ph-prog"><i></i></div>
    </nav>
    <div class="ph-body">
      <section id="ph-market" class="ph-sec">
        <header class="ph-h"><b>Market</b><span id="ph-asof"></span></header>
        ${crestHtml('ph-crest')}
        <div class="ph-rail"><div class="ph-railhd"><span class="reactor"><i></i><i></i><i></i></span><b class="cnt" id="ph-su-n"></b><span class="k">Setups</span><span class="sub">Tap to jump · hold for details</span></div>
          <div class="ph-picks" id="ph-picks"></div></div>
        <div class="ph-sub"><b>Sectors</b><span id="ph-secn"></span></div>
        <div class="ph-chips" id="ph-chips"></div>
      </section>
      <section id="ph-flow-s" class="ph-sec">
        <header class="ph-h"><b>Flow</b><span id="ph-range"></span><button class="replay" data-replay aria-label="Replay the build"><svg viewBox="0 0 24 24"><path d="M4 12a8 8 0 1 0 2.3-5.6M4 4v4h4"/></svg></button></header>
        <div class="ph-flow" id="ph-flow"><canvas id="ph-cv"></canvas><canvas id="ph-fx"></canvas></div>
      </section>
      <section id="ph-bull" class="ph-sec bull"></section>
      <section id="ph-bear" class="ph-sec bear"></section>
    </div>
  </section>`;

  function lens(side) {
    const W = 600, H = 70, x0 = 170, x1 = 430;
    let s = `<svg class="lens" viewBox="0 0 ${W} ${H}" preserveAspectRatio="none" aria-hidden="true">`
      + `<path class="lb" d="M${x0 + 6} 6h-6v12h6M${x1 - 6} 6h6v12h-6"/><line class="lr" x1="${x0}" x2="${x1}" y1="12" y2="12"/>`;
    for (let i = 0; i < 20; i++) {
      const x = x0 + 8 + i * (x1 - x0 - 16) / 19, tx = 20 + ((i * 7) % 20) * (W - 40) / 19;
      s += `<line class="lt" x1="${x}" x2="${x}" y1="8" y2="16"/><path class="lf" d="M${x} 12C${x} 40 ${tx} 40 ${tx} ${H - 4}"/>`;
    }
    return s + `<line class="lr" x1="0" x2="${W}" y1="${H - 4}" y2="${H - 4}"/></svg>`;
  }

  function contenders(el, rows, side) {
    const c = columns(rows, side);
    const word = side === 'bull' ? 'Bullish' : 'Bearish', arrow = side === 'bull' ? '▲' : '▼';
    const tier = (list, size) => {
      if (!list.length) return '';
      const rk = list.map(r => r.rank);
      return `<div class="ph-tier ${size}"><div class="ph-thd"><b>Rank ${pad2(Math.min(...rk))}–${pad2(Math.max(...rk))}</b><span>${list.length}</span></div>`
        + `<div class="ph-tb">${list.slice().sort((a, z) => a.rank - z.rank).map(r => tile(r, size)).join('')}</div></div>`;
    };
    el.innerHTML = `<header class="ph-h ${side === 'bull' ? 'up' : 'dn'}"><b>${arrow} ${word}</b><span>${rows.length} contenders</span></header>`
      + lens(side) + tier(c.c4, 'c4') + tier(c.c3, 'c3') + tier(c.c2, 'c2')
      + `<header class="ph-h set ${side === 'bull' ? 'up' : 'dn'}"><b>${arrow} ${word} setups</b><span>${c.c1.length}</span></header>`
      + (c.c1.map((r, i) => `<div class="ph-bay" id="bay-${esc(r.symbol)}">${tile(r, 'c1')}<i class="ph-bridge"></i>${ticket(r, i + 1)}</div>`).join('') || noTicket(side));
  }

  function renderNarrow(el, b) {
    if (!$('.phx', el)) {
      el.innerHTML = narrowSkeleton;
      bindNarrow(el);
    }
    const picks = [...b.bullish, ...b.bearish].filter(r => r.pick).sort((a, z) => (a.side === z.side ? a.pick - z.pick : a.side === 'bull' ? -1 : 1));
    $('#ph-n').textContent = b.dots.length;
    $('#ph-nb').textContent = b.bullish.length;
    $('#ph-ns').textContent = b.bearish.length;
    const mb = $('.ph-nav .mb');
    mb.firstElementChild.style.flexGrow = b.breadth.up;
    mb.lastElementChild.style.flexGrow = b.breadth.down;
    $('#ph-asof').textContent = `${b.dots.length} stocks · as of ${fmt.hhmm(b.t)}`;
    $('#ph-range').textContent = `09:15 → ${fmt.hhmm(b.t)}`;
    crest($('#ph-crest'), b);
    $('#ph-su-n').textContent = picks.length;
    $('#ph-picks').innerHTML = picks.map(r => `<button class="ph-pick ${r.side}" data-pick="${esc(r.symbol)}"><span class="av">${initials(r.symbol)}</span>`
      + `<span class="tl-p"><b class="tkr">${esc(r.symbol)}</b><span class="pc ${tone(r.chg)}" data-chg="${esc(r.symbol)}">${fmt.pct(r.chg)}</span></span></button>`).join('')
      || '<div class="empty">No setups yet</div>';
    $('#ph-secn').textContent = b.sectors.length;
    $('#ph-chips').innerHTML = b.sectors.map(chip).join('');
    contenders($('#ph-bull'), b.bullish, 'bull');
    contenders($('#ph-bear'), b.bearish, 'bear');
    requestAnimationFrame(() => drawTicketCharts($('.phx')));
  }

  function bindNarrow(el) {
    const ph = $('.phx', el);
    $$('[data-go]', ph).forEach(btn => btn.addEventListener('click', () => {
      const id = btn.dataset.go === 'ph-flow' ? 'ph-flow-s' : btn.dataset.go;
      $('#' + id).scrollIntoView({ behavior: 'smooth', block: 'start' });
    }));
    $('[data-find]', ph).addEventListener('click', () => $('#search').focus());
    // tap a setup to jump to it, hold for its card
    let holdTimer = 0, held = false;
    ph.addEventListener('pointerdown', e => {
      const b = e.target.closest('[data-pick]');
      if (!b) return;
      held = false;
      holdTimer = setTimeout(() => {
        held = true;
        OB.pop.open(b.dataset.pick, [b.dataset.pick]);
      }, 450);
    });
    const cancel = () => clearTimeout(holdTimer);
    ph.addEventListener('pointerup', cancel);
    ph.addEventListener('pointerleave', cancel);
    ph.addEventListener('pointercancel', cancel);
    ph.addEventListener('click', e => {
      const b = e.target.closest('[data-pick]');
      if (!b || held) return;
      const bay = document.getElementById('bay-' + b.dataset.pick);
      if (bay) bay.scrollIntoView({ behavior: 'smooth', block: 'center' });
    });
    ph.addEventListener('contextmenu', e => { if (e.target.closest('[data-pick]')) e.preventDefault(); });
    // tap the flow to open the stock under your finger
    $('#ph-flow', ph).addEventListener('click', e => {
      const g = geom, box = $('#ph-flow').getBoundingClientRect();
      if (!g) return;
      const fake = { clientX: e.clientX, clientY: e.clientY };
      const x = fake.clientX - box.left, y = fake.clientY - box.top;
      let s = null;
      if (x >= g.x0 && x <= g.now) {
        const i = clamp(Math.round((x - g.x0) / (g.now - g.x0) * g.last), 0, g.last);
        let d = 12;
        for (const f of g.sorted) {
          if (i >= f.p.length) continue;
          const dd = Math.abs(g.Y(f.p[i]) - y);
          if (dd < d) { d = dd; s = f.s; }
        }
      } else if (x > g.now) {
        const n = g.sorted.length;
        s = g.sorted[clamp(Math.round((y - g.top) / (g.bottom - g.top) * (n - 1)), 0, n - 1)]?.s;
      }
      if (s) OB.pop.open(s, [s]);
    });
    // which section is on screen, and how far down the page you are
    const secs = ['ph-market', 'ph-flow-s', 'ph-bull', 'ph-bear'];
    const onScroll = () => {
      if (OB.state.view !== 'terminal' || !$('.phx')) return;
      const max = document.documentElement.scrollHeight - innerHeight;
      $('.ph-prog i').style.width = (max > 0 ? scrollY / max * 100 : 0).toFixed(1) + '%';
      let cur = secs[0];
      for (const id of secs) {
        const s = document.getElementById(id);
        if (s && s.getBoundingClientRect().top < innerHeight * 0.4) cur = id;
      }
      $$('.ph-nav [data-go]').forEach(b => b.classList.toggle('on', (b.dataset.go === 'ph-flow' ? 'ph-flow-s' : b.dataset.go) === cur));
    };
    window.addEventListener('scroll', onScroll, { passive: true });
  }

  /* ---------- shared wiring ---------- */
  function setHover(s) {
    if (s === hover) return;
    hover = s;
    const tx = $('#tx');
    if (tx) {
      tx.classList.toggle('brush', !!(hover || sector));
      $$('#tx .tl, #tx .tk').forEach(t => t.classList.toggle('lit', (t.dataset.pop || t.dataset.sym) === hover || (!hover && !!sector && secOf(t.dataset.pop || t.dataset.sym) === sector)));
    }
    repaintSoon();
  }

  OB.on('hover', s => {
    if (OB.state.view === 'terminal') setHover(s);
  });
  OB.on('quotes', () => {
    if (OB.state.view === 'terminal') drawTicketCharts($('#view'));
  });

  document.addEventListener('click', e => {
    if (OB.state.view !== 'terminal') return;
    if (e.target.closest('[data-replay]')) startReplay();
    const c = e.target.closest('.chip[data-sector]');
    if (c) {
      const name = c.dataset.sector;
      if (sector === name) {
        closeSector();
      } else {
        selectSector(name);
        sectorSheet(name);
      }
    }
  });
  document.addEventListener('keydown', e => {
    if (e.key === 'Escape' && $('#ssheet') && !$('#ssheet').hidden) closeSector();
  });
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden) kickFx();
  });

  OB.views.terminal = {
    render(el, b) {
      const m = wide() ? 'wide' : 'narrow';
      if (m !== mode || !el.querySelector('.tx, .phx')) {
        mode = m;
        el.innerHTML = '';
        if (ro) ro.disconnect();
        ro = new ResizeObserver(() => {
          const bb = OB.state.board;
          if (bb) {
            crest($(wide() ? '#tx-crest' : '#ph-crest'), bb);
            drawTicketCharts($('#view'));
          }
          paint();
        });
      }
      if (m === 'wide') renderWide(el, b); else renderNarrow(el, b);
      ro.observe(el.querySelector(m === 'wide' ? '#tx' : '#ph-flow'));
      if (hover || sector) {
        $$('#tx .tl, #tx .tk').forEach(t => t.classList.toggle('lit', (t.dataset.pop || t.dataset.sym) === hover || (!hover && !!sector && secOf(t.dataset.pop || t.dataset.sym) === sector)));
      }
      paint();
      loadFlow();
    },
  };

  matchMedia(WIDE).addEventListener('change', () => {
    if (OB.state.view === 'terminal') OB.render();
  });

  OB.crest = crest;
  OB.crestHtml = crestHtml;
})();
