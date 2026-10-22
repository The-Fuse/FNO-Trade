/* Pages: Home (today's picks), Terminal (whole market), Plans (every suggestion and how it went). */
(() => {
  'use strict';

  const { $, $$, fmt, esc, initials, col, names, statusText } = OB;

  /* ---------- shared pieces ---------- */
  const tone = x => x > 0 ? 'pos' : x < 0 ? 'neg' : '';
  const pickWord = () => Number(OB.rules().PICKS || 1) === 1 ? 'the #1 pick on a side' : `a top-${OB.rules().PICKS} pick`;

  /** The strategy as the server runs it, in words. */
  function howItWorks() {
    const r = OB.rules(), hm = OB.hm;
    const market = Math.round(Number(r.MIN_MARKET || 0) * 100);
    const stop = Number(r.MIN_STOP_PCT || 0);
    const mode = r.TARGET || 'half';
    const exit = mode === 't2' ? 'Exit everything at the second target; the first is only a checkpoint.'
      : mode === 't1' ? 'Exit everything at the first target.'
      : 'Book half at the first target and move the stop to entry; exit the rest at the second target.';
    const s = OB.settings;
    return `
      <li><b>When.</b> A stock becomes ${pickWord()} with a live order block, R:R of at least 1:${r.MIN_RR || 1.5}, price on the right side of VWAP${market ? ` and at least ${market}% of the market moving the same way` : ''}, between ${hm(Number(r.FIRST_ENTRY_MIN || 555))} and ${hm(Number(r.LAST_ENTRY_MIN || 870))}.</li>
      <li><b>Entry.</b> A limit order at Z, the order block's body edge. If price never comes back to it, there is no trade.</li>
      <li><b>Stop.</b> S1 (R1 when selling), just beyond the far side of the block${stop ? ` and at least ${stop}% from the entry` : ''}.</li>
      <li><b>Exit.</b> ${exit}</li>
      <li><b>Time.</b> No new entries after ${hm(Number(r.LAST_ENTRY_MIN || 870))}. Anything still open closes at ${hm(Number(r.EXIT_MIN || 915))}.</li>
      <li><b>Size.</b> ${s.risk}% of ${fmt.inr(s.capital)} = ${fmt.inr(OB.riskAmount())} at risk per trade, capped by intraday margin (about 5×). A trade that can't risk at least ₹250 is skipped: the charges would eat it.</li>
      <li><b>Results</b> are in R, multiples of the planned risk. Rupee figures use your risk per trade (<a role="button" tabindex="0" data-open-settings style="cursor:pointer;text-decoration:underline">change</a>).</li>`;
  }

  function scoreTiles(sc) {
    const risk = OB.riskAmount();
    return [
      ['Suggestions', sc.plans],
      ['Triggered', sc.triggered],
      ['Won', sc.wins, 'pos'],
      ['Lost', sc.losses, 'neg'],
      ['Open', sc.open],
      ['Net', fmt.r(sc.netR), tone(sc.netR)],
      ['Net ₹', fmt.inr(sc.netR * risk), tone(sc.netR)],
    ].map(([k, v, c]) => `<div><span class="lbl">${k}</span><b class="${c || ''}">${v}</b></div>`).join('');
  }

  function plansTable(states, limit) {
    const rows = limit ? states.slice(0, limit) : states;
    if (!rows.length) return `<div class="empty">No suggestions yet today. A plan is made when ${pickWord()} has a tradeable setup.</div>`;
    const risk = OB.riskAmount();
    return `<table><thead><tr><th>Time</th><th>Stock</th><th>Side</th><th class="num">Entry</th><th class="num">Stop</th>`
      + `<th class="num">T1</th><th class="num">T2</th><th class="num">R:R</th><th>Status</th><th class="num">Result</th><th class="num">P&amp;L</th></tr></thead><tbody>`
      + rows.map(s => {
        const p = s.plan, res = s.r ?? s.openR, act = p.side === 'bull' ? 'BUY' : 'SELL';
        return `<tr data-pop="${esc(p.symbol)}">
          <td class="num">${fmt.hhmm(p.createdT)}</td>
          <td><b>${esc(p.symbol)}</b> <small class="muted">${esc(p.sector)}</small></td>
          <td><span class="act ${act}">${act}</span></td>
          <td class="num">${fmt.num(p.entry)}</td><td class="num">${fmt.num(p.stop)}</td>
          <td class="num">${fmt.num(p.t1)}</td><td class="num">${fmt.num(p.t2)}</td><td class="num">${fmt.rr(p.rr)}</td>
          <td><span class="status s-${s.status}">${statusText[s.status]}</span></td>
          <td class="num ${tone(res)}">${res == null ? '—' : fmt.r(res) + (s.r == null ? ' open' : '')}</td>
          <td class="num ${tone(res)}">${res == null ? '—' : fmt.inr(res * risk)}</td>
        </tr>`;
      }).join('') + '</tbody></table>';
  }

  function leadLag(b) {
    const lead = b.sectors[0], lag = b.sectors[b.sectors.length - 1];
    if (!lead) return '';
    return `<span>Lagging sector <b class="${tone(lag.chg)}">${esc(lag.name)} ${fmt.pct(lag.chg)}</b></span>`
      + `<span>Leading sector <b class="${tone(lead.chg)}">${esc(lead.name)} ${fmt.pct(lead.chg)}</b></span>`;
  }

  /* ---------- home ---------- */
  function pickCard(r, b) {
    const s = r.setup, n = names(r.side), a = r.advice, sym = esc(r.symbol);
    const plan = b.plans.find(p => p.plan.symbol === r.symbol);
    const res = plan && (plan.r ?? plan.openR);
    const t = r.side === 'bull' ? 'pos' : 'neg';
    return `<article class="pick ${r.side}" data-pop="${sym}">
      <div class="pick-head"><span class="av">${initials(r.symbol)}</span>
        <div class="who"><b>${sym}</b><small>${esc(r.name)} · ${esc(r.sector)}</small></div>${OB.spark(r.path, 110, 34)}</div>
      <div class="pick-px"><b>₹<span data-ltp="${sym}">${fmt.num(r.ltp)}</span></b>
        <span class="num ${tone(r.chg)}" data-chg="${sym}">${fmt.pct(r.chg)}</span>${OB.buildUpTag(r.buildUp)}</div>
      <div class="pick-act"><span class="act ${a.action}">${a.action}</span><span>${esc(a.reason)}</span></div>
      ${s ? `<dl class="pick-lv">
        <div><dt>Entry Z</dt><dd>${fmt.num(s.z)}</dd></div>
        <div><dt>Stop ${n.stop}</dt><dd style="color:var(--stop)">${fmt.num(s.stop)}</dd></div>
        <div><dt>Target ${n.t[0]}</dt><dd class="${t}">${fmt.num(s.targets[0].price)}</dd></div>
        <div><dt>Target ${n.t[1]}</dt><dd class="${t}">${fmt.num(s.targets[1].price)}</dd></div>
        <div><dt>R:R</dt><dd>${fmt.rr(s.rr)}</dd></div>
        <div><dt>Qty</dt><dd>${fmt.int(OB.qty(s.z, s.stop))}</dd></div>
      </dl>` : ''}
      <div class="pick-foot">${OB.conv(r.conv)}<span class="mono">${esc(r.rule)}</span>
        ${plan ? `<span class="status s-${plan.status}">${statusText[plan.status]}${res != null ? ` · <b class="${tone(res)}">${fmt.r(res)}</b>` : ''}</span>` : ''}</div>
    </article>`;
  }

  function pickColumn(b, side) {
    const rows = (side === 'bull' ? b.bullish : b.bearish).filter(r => r.pick).sort((x, y) => x.pick - y.pick);
    const word = side === 'bull' ? 'bullish' : 'bearish';
    return rows.map(r => pickCard(r, b)).join('')
      + (rows.length < 3 ? `<div class="pick-none">${rows.length ? 'No more' : 'No'} ${word} picks right now. A pick needs a top-20 rank and a live order block.</div>` : '');
  }

  OB.views.home = {
    render(el, b) {
      const br = b.breadth;
      el.innerHTML = `<section class="home">
        <div class="hero">
          <div><h1>Today's picks</h1><p>${esc(b.market.label)} · board at ${fmt.hhmm(b.t)} IST · top 3 per side, at most 2 from a sector · refreshed every minute</p></div>
          <div class="score">${scoreTiles(b.scorecard)}</div>
        </div>
        <div class="picks">
          <div class="pick-col bull"><h2><span class="pos">▲</span> Bullish picks <small class="muted">${b.bullCount} on the bullish side</small></h2>${pickColumn(b, 'bull')}</div>
          <div class="pick-col bear"><h2><span class="neg">▼</span> Bearish picks <small class="muted">${b.bearCount} on the bearish side</small></h2>${pickColumn(b, 'bear')}</div>
        </div>
        <div class="mk">
          <div class="panel"><span class="lbl">Breadth</span>
            <div class="bar"><i class="u" style="flex-grow:${br.up}"></i><i class="f" style="flex-grow:${br.flat}"></i><i class="d" style="flex-grow:${br.down}"></i></div>
            <div class="legend"><b class="pos">${br.up} up</b><b class="neg">${br.down} down</b>${br.flat ? `<span>${br.flat} flat</span>` : ''}</div>
          </div>
          <div class="panel">${OB.crestHtml('home-crest')}</div>
        </div>
        <div class="panel scroll-x">
          <div class="t-head" style="padding:12px 14px 4px"><span class="lbl">Today's suggestions</span><a href="#/plans" style="margin-left:auto">All plans →</a></div>
          ${plansTable(b.plans, 8)}
        </div>
      </section>`;
      OB.crest($('#home-crest'), b);
    },
  };

  /* ---------- plans ---------- */
  OB.views.plans = {
    render(el, b) {
      el.innerHTML = `<section class="plans">
        <div class="hero">
          <div><h1>Trade plans</h1><p>Every suggestion made this session, replayed minute by minute against the actual prices.</p></div>
          <div class="score">${scoreTiles(b.scorecard)}</div>
        </div>
        <div class="panel scroll-x">${plansTable(b.plans)}</div>
        <div class="panel">
          <div class="t-head" style="padding:12px 14px 0"><span class="lbl">How a plan works</span></div>
          <ol class="rules">${howItWorks()}</ol>
        </div>
      </section>`;
    },
  };

  /* ---------- learn ---------- */
  // The guide is its own page (learn.html) so its styles stay separate; it is framed here inside the app.
  OB.views.learn = {
    static: true,
    render(el) {
      if (el.querySelector('.learn-frame')) return;
      el.innerHTML = '<iframe class="learn-frame" src="/learn.html" title="F&amp;O Field Guide"></iframe>';
    },
  };
  OB.on('theme', t => {
    const doc = $('.learn-frame')?.contentDocument;
    if (doc) doc.documentElement.dataset.theme = t;
  });
})();
