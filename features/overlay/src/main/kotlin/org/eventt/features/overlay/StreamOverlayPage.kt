package org.eventt.features.overlay

import org.eventt.core.model.stringBlocking
import org.eventt.overlay.generated.resources.*

// The actual OBS Browser Source page: transparent background, all styling/animation self-contained
// (no CDN — OBS's embedded Chromium shouldn't depend on outbound network to render a local overlay;
// only the optional item icons come from images.evetech.net and simply hide if unreachable).
// Visual config comes from the page's own query string, read client-side in JS, so the server
// stays a static file: ?accent=00e5ff and ?hide=chart,sales,top,orders.
object StreamOverlayPage {
    // Fixed block heights (px), so the panel never grows as data arrives and OBS can use one size.
    private const val WIDTH = 400
    private const val MARGIN = 14
    private const val H_HEADER = 44
    private const val H_PNL = 168
    private const val H_PNL_NO_CHART = 100
    private const val H_TILES = 56
    private const val SALE_ROWS = 6
    private const val H_SALE = 44
    private const val H_SALES = 46 + SALE_ROWS * H_SALE
    private const val H_TOP = 142
    private const val H_ORDERS = 116

    /** Browser Source size (width to height) for a panel with [hidden] sections (?hide= keys). */
    fun obsSize(hidden: Set<String>): Pair<Int, Int> {
        val h =
            H_HEADER + (if ("chart" in hidden) H_PNL_NO_CHART else H_PNL) + H_TILES +
                (if ("sales" in hidden) 0 else H_SALES) + (if ("top" in hidden) 0 else H_TOP) + (if ("orders" in hidden) 0 else H_ORDERS)
        // +2: the panel's 1px border.
        return (WIDTH + 2 * MARGIN) to (h + 2 + 2 * MARGIN)
    }

    private val LABELS =
        mapOf(
            "live" to Res.string.so_live,
            "title" to Res.string.so_title,
            "pnl" to Res.string.so_pnl,
            "perHour" to Res.string.so_per_hour,
            "trades" to Res.string.so_trades,
            "winRate" to Res.string.so_win_rate,
            "revenue" to Res.string.so_revenue,
            "relists" to Res.string.so_relists,
            "recentSales" to Res.string.so_recent_sales,
            "waiting" to Res.string.so_waiting,
            "topItems" to Res.string.so_top_items,
            "orderBook" to Res.string.so_order_book,
            "sellOrders" to Res.string.so_sell_orders,
            "buyOrders" to Res.string.so_buy_orders,
            "beaten" to Res.string.so_beaten,
            "iskInOrders" to Res.string.so_isk_in_orders,
            "expectedProfit" to Res.string.so_expected_profit,
            "relistFees" to Res.string.so_relist_fees,
        )

    /** [HTML] with every {{label}} in the app's UI language. */
    fun localizedHtml(): String = LABELS.entries.fold(HTML) { html, (key, res) -> html.replace("{{$key}}", escape(stringBlocking(res))) }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    val HTML =
        """
        <!doctype html>
        <html>
        <head>
        <meta charset="utf-8">
        <title>eventt stream overlay</title>
        <style>
          :root {
            --accent: #00e5ff;
            --bg: rgba(10, 13, 19, 0.93);
            --line: rgba(255, 255, 255, 0.07);
            --text: #e9eef5;
            --muted: #8592a3;
            --pos: #34d399;
            --neg: #f87171;
            --warn: #fbbf24;
          }
          * { box-sizing: border-box; }
          html, body { margin: 0; padding: 0; background: transparent; overflow: hidden; }
          body {
            font-family: 'Inter', 'Segoe UI', 'Roboto', system-ui, sans-serif;
            color: var(--text);
            -webkit-font-smoothing: antialiased;
          }
          .num { font-variant-numeric: tabular-nums; font-feature-settings: 'tnum'; }
          .pos { color: var(--pos); }
          .neg { color: var(--neg); }
          .muted { color: var(--muted); }

          #panel {
            width: ${WIDTH}px;
            margin: ${MARGIN}px;
            background: var(--bg);
            border: 1px solid var(--line);
            border-radius: 14px;
            overflow: hidden;
            box-shadow: 0 12px 40px rgba(0, 0, 0, 0.45);
            position: relative;
          }
          #panel::before {
            content: ''; position: absolute; left: 0; right: 0; top: 0; height: 2px;
            background: linear-gradient(90deg, transparent, var(--accent), transparent);
          }
          section { padding: 12px 16px; border-top: 1px solid var(--line); overflow: hidden; }
          header { height: ${H_HEADER}px; }
          #sec-pnl { height: ${H_PNL}px; }
          #sec-pnl.nochart { height: ${H_PNL_NO_CHART}px; }
          .tiles { height: ${H_TILES}px; }
          #sec-sales { height: ${H_SALES}px; }
          #sec-top { height: ${H_TOP}px; }
          #sec-orders { height: ${H_ORDERS}px; }
          section:first-of-type { border-top: 0; }
          .caption {
            font-size: 10px; font-weight: 600; letter-spacing: 0.14em; text-transform: uppercase;
            color: var(--muted); margin-bottom: 8px; display: flex; justify-content: space-between;
          }

          /* Header */
          header { display: flex; align-items: center; gap: 10px; padding: 12px 16px; }
          .live {
            display: inline-flex; align-items: center; gap: 6px;
            font-size: 10px; font-weight: 700; letter-spacing: 0.14em;
            padding: 3px 8px; border-radius: 4px;
            background: rgba(248, 113, 113, 0.14); color: var(--neg);
          }
          .live i { width: 7px; height: 7px; border-radius: 50%; background: var(--neg); animation: pulse 1.6s ease-in-out infinite; }
          @keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.25; } }
          header .title { font-size: 13px; font-weight: 600; flex: 1; }
          header .timer { font-size: 13px; font-weight: 600; color: var(--muted); }

          /* P&L hero */
          .hero { display: flex; align-items: baseline; justify-content: space-between; }
          .hero .big { font-size: 34px; font-weight: 750; letter-spacing: -0.02em; line-height: 1.1; }
          .hero .big small { font-size: 14px; font-weight: 600; color: var(--muted); margin-left: 4px; letter-spacing: 0; }
          .rate { text-align: right; font-size: 13px; font-weight: 600; }
          .rate .muted { font-size: 11px; font-weight: 500; }
          #chart { display: block; width: 100%; height: 64px; margin-top: 10px; overflow: visible; }

          /* Tiles */
          .tiles { display: grid; grid-template-columns: repeat(4, 1fr); }
          .tile { padding: 10px 0 10px 16px; border-left: 1px solid var(--line); }
          .tile:first-child { border-left: 0; }
          .tile .k { font-size: 10px; letter-spacing: 0.1em; text-transform: uppercase; color: var(--muted); }
          .tile .v { font-size: 17px; font-weight: 700; margin-top: 2px; }

          /* Sales feed */
          .sale {
            display: grid; grid-template-columns: 28px 1fr auto; gap: 10px; align-items: center;
            height: ${H_SALE}px; border-top: 1px solid var(--line);
          }
          .sale:first-child { border-top: 0; }
          .sale.new { animation: slide 0.6s cubic-bezier(.2,.8,.2,1); }
          @keyframes slide {
            from { opacity: 0; transform: translateX(-16px); background: color-mix(in srgb, var(--accent) 18%, transparent); }
            to { opacity: 1; transform: none; background: transparent; }
          }
          .icon { width: 28px; height: 28px; border-radius: 6px; background: rgba(255,255,255,0.05); overflow: hidden; }
          .icon img { width: 100%; height: 100%; display: block; }
          .sale .name { font-size: 13px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
          .sale .meta { font-size: 11px; color: var(--muted); margin-top: 1px; }
          .sale .p { font-size: 13px; font-weight: 700; text-align: right; }
          .empty { font-size: 12px; color: var(--muted); padding: 4px 0; }

          /* Top items */
          .top { display: flex; flex-direction: column; gap: 6px; }
          .top .row { display: grid; grid-template-columns: 16px 22px 1fr auto; gap: 8px; align-items: center; font-size: 12px; }
          .top .rank { color: var(--muted); font-weight: 700; }
          .top .icon { width: 22px; height: 22px; border-radius: 4px; }
          .top .name { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; font-weight: 600; }
          .bar { height: 3px; border-radius: 2px; background: var(--line); grid-column: 3 / 5; overflow: hidden; margin-top: -3px; }
          .bar i { display: block; height: 100%; background: var(--accent); transition: width 0.6s ease; }

          /* Order book */
          .book { display: grid; grid-template-columns: repeat(3, 1fr); row-gap: 8px; }
          .book .k { font-size: 10px; letter-spacing: 0.08em; text-transform: uppercase; color: var(--muted); }
          .book .v { font-size: 14px; font-weight: 700; margin-top: 1px; }

          .flash-up { animation: fu 0.9s ease; }
          .flash-down { animation: fd 0.9s ease; }
          @keyframes fu { 0% { color: var(--pos); text-shadow: 0 0 12px var(--pos); } }
          @keyframes fd { 0% { color: var(--neg); text-shadow: 0 0 12px var(--neg); } }
          .hidden { display: none !important; }
        </style>
        </head>
        <body>
        <div id="panel">
          <header>
            <span class="live"><i></i>{{live}}</span>
            <span class="title">{{title}}</span>
            <span class="timer num" id="timer">00:00:00</span>
          </header>

          <section id="sec-pnl">
            <div class="caption"><span id="pnlLabel">{{pnl}}</span></div>
            <div class="hero">
              <div class="big num" id="profit">0<small>ISK</small></div>
              <div class="rate num"><div id="rate">0</div><div class="muted" id="rateLabel">ISK {{perHour}}</div></div>
            </div>
            <svg id="chart" viewBox="0 0 368 64" preserveAspectRatio="none">
              <defs>
                <linearGradient id="fill" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0" stop-color="var(--accent)" stop-opacity="0.35"/>
                  <stop offset="1" stop-color="var(--accent)" stop-opacity="0"/>
                </linearGradient>
              </defs>
              <line id="zero" x1="0" x2="368" stroke="rgba(255,255,255,0.15)" stroke-dasharray="3 4"/>
              <path id="area" fill="url(#fill)"/>
              <path id="line" fill="none" stroke="var(--accent)" stroke-width="2" stroke-linejoin="round" vector-effect="non-scaling-stroke"/>
              <circle id="dot" r="3.5" fill="var(--accent)"/>
            </svg>
          </section>

          <div class="tiles" style="border-top: 1px solid var(--line)">
            <div class="tile"><div class="k">{{trades}}</div><div class="v num" id="trades">0</div></div>
            <div class="tile"><div class="k">{{winRate}}</div><div class="v num" id="winRate">—</div></div>
            <div class="tile"><div class="k">{{revenue}}</div><div class="v num" id="revenue">0</div></div>
            <div class="tile"><div class="k">{{relists}}</div><div class="v num" id="relists">0</div></div>
          </div>

          <section id="sec-sales">
            <div class="caption"><span>{{recentSales}}</span></div>
            <div id="sales"><div class="empty">{{waiting}}</div></div>
          </section>

          <section id="sec-top">
            <div class="caption"><span>{{topItems}}</span></div>
            <div class="top" id="top"><div class="empty">{{waiting}}</div></div>
          </section>

          <section id="sec-orders">
            <div class="caption"><span>{{orderBook}}</span></div>
            <div class="book">
              <div><div class="k">{{sellOrders}}</div><div class="v num" id="sellOrders">0</div></div>
              <div><div class="k">{{buyOrders}}</div><div class="v num" id="buyOrders">0</div></div>
              <div><div class="k">{{beaten}}</div><div class="v num" id="beaten">0</div></div>
              <div><div class="k">{{iskInOrders}}</div><div class="v num" id="iskInOrders">0</div></div>
              <div><div class="k">{{expectedProfit}}</div><div class="v num" id="expectedProfit">0</div></div>
              <div><div class="k">{{relistFees}}</div><div class="v num" id="relistFees">0</div></div>
            </div>
          </section>
        </div>
        <script>
          var params = new URLSearchParams(location.search);
          var accent = params.get('accent');
          if (accent) document.documentElement.style.setProperty('--accent', '#' + accent.replace('#', ''));
          var hidden = (params.get('hide') || '').split(',');
          ['chart', 'sales', 'top', 'orders'].forEach(function (s) { if (hidden.indexOf(s) >= 0) hide(s); });
          function hide(s) {
            if (s === 'chart') {
              document.getElementById('chart').classList.add('hidden');
              document.getElementById('sec-pnl').classList.add('nochart');
            } else {
              document.getElementById('sec-' + s).classList.add('hidden');
            }
          }

          function $(id) { return document.getElementById(id); }
          function pad(n) { return n < 10 ? '0' + n : '' + n; }
          function abbr(n, signed) {
            var sign = n < 0 ? '-' : (signed && n > 0 ? '+' : '');
            var a = Math.abs(n);
            var t = a >= 1e12 ? (a / 1e12).toFixed(2) + 'T'
              : a >= 1e9 ? (a / 1e9).toFixed(2) + 'B'
              : a >= 1e6 ? (a / 1e6).toFixed(2) + 'M'
              : a >= 1e3 ? (a / 1e3).toFixed(1) + 'K'
              : a >= 100 || a === 0 ? Math.round(a).toString() : a.toFixed(2);
            return sign + t;
          }
          function cls(el, v) { el.classList.toggle('pos', v > 0); el.classList.toggle('neg', v < 0); }
          function esc(s) { return String(s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }
          function icon(typeId) {
            return '<div class="icon"><img src="https://images.evetech.net/types/' + typeId + '/icon?size=64" onerror="this.remove()"></div>';
          }

          // Numbers tween to their new value and flash green/red; [invert] = growing is bad.
          var shown = {};
          function stat(id, value, fmt, invert) {
            var el = $(id), from = shown[id];
            shown[id] = value;
            if (from === undefined || from === value) { el.firstChild.nodeValue = fmt(value); return; }
            el.classList.remove('flash-up', 'flash-down'); void el.offsetWidth;
            el.classList.add((value > from) !== !!invert ? 'flash-up' : 'flash-down');
            var start = performance.now();
            (function step(now) {
              var k = Math.min(1, (now - start) / 700), e = 1 - Math.pow(1 - k, 3);
              el.firstChild.nodeValue = fmt(from + (value - from) * e);
              if (k < 1) requestAnimationFrame(step);
            })(start);
          }
          function int(v) { return Math.round(v).toString(); }

          // N-day periods show their label instead of a running clock and chart the whole window.
          var elapsedBase = 0, elapsedAt = Date.now(), series = [], periodLabel = null, span = 0;
          function tick() {
            if (periodLabel) {
              $('timer').textContent = periodLabel;
              chart(series, elapsedBase, span);
              return;
            }
            var s = elapsedBase + Math.floor((Date.now() - elapsedAt) / 1000);
            $('timer').textContent = pad(Math.floor(s / 3600)) + ':' + pad(Math.floor(s % 3600 / 60)) + ':' + pad(s % 60);
            // The chart moves on a one-minute tick (CHART_TICK), the clock every second.
            var m = Math.floor(s / 60) * 60;
            chart(series, m, Math.max(m, 60));
          }

          function chart(points, now, maxT) {
            var W = 368, H = 64, P = 5;
            if (!points.length) return;
            var pts = points.concat([{ t: now, v: points[points.length - 1].v }]);
            var vs = pts.map(function (p) { return p.v; });
            var lo = Math.min(0, Math.min.apply(null, vs)), hi = Math.max(0, Math.max.apply(null, vs));
            if (hi === lo) { hi += 1; lo -= 1; }
            function x(t) { return t / maxT * W; }
            function y(v) { return P + (hi - v) / (hi - lo) * (H - 2 * P); }
            // Step line: P&L jumps when a sale lands, it doesn't drift between them.
            var d = 'M' + x(pts[0].t) + ',' + y(pts[0].v);
            for (var i = 1; i < pts.length; i++) d += ' H' + x(pts[i].t) + ' V' + y(pts[i].v);
            $('line').setAttribute('d', d);
            $('area').setAttribute('d', d + ' V' + y(lo) + ' H' + x(pts[0].t) + ' Z');
            $('zero').setAttribute('y1', y(0)); $('zero').setAttribute('y2', y(0));
            var last = pts[pts.length - 1];
            $('dot').setAttribute('cx', x(last.t)); $('dot').setAttribute('cy', y(last.v));
          }

          var seenSales = null;
          function sales(list) {
            if (!list.length) return;
            var keys = list.map(function (s) { return s.at + s.typeId + ':' + s.qty + ':' + s.price; });
            $('sales').innerHTML = list.map(function (s, i) {
              var isNew = seenSales && seenSales.indexOf(keys[i]) < 0;
              return '<div class="sale' + (isNew ? ' new' : '') + '">' + icon(s.typeId) +
                '<div style="min-width:0"><div class="name">' + esc(s.name) + '</div>' +
                '<div class="meta num">' + s.at + ' · ' + (s.qty < 1000 ? s.qty : abbr(s.qty)) + ' × ' + abbr(s.price) + '</div></div>' +
                '<div class="p num ' + (s.profit >= 0 ? 'pos' : 'neg') + '">' + abbr(s.profit, true) + '</div></div>';
            }).join('');
            seenSales = keys;
          }

          function renderTop(list) {
            if (!list.length) return;
            var max = Math.max.apply(null, list.map(function (t) { return Math.abs(t.profit); })) || 1;
            $('top').innerHTML = list.map(function (t, i) {
              return '<div class="row"><span class="rank num">' + (i + 1) + '</span>' + icon(t.typeId) +
                '<span class="name">' + esc(t.name) + '</span>' +
                '<span class="num ' + (t.profit >= 0 ? 'pos' : 'neg') + '" style="font-weight:700">' + abbr(t.profit, true) + '</span></div>' +
                '<div class="row"><span></span><span></span><div class="bar"><i style="width:' + (Math.abs(t.profit) / max * 100) + '%"></i></div></div>';
            }).join('');
          }

          function poll() {
            fetch('/api/stats').then(function (r) { return r.json(); }).then(function (d) {
              elapsedBase = d.elapsedSeconds; elapsedAt = Date.now(); series = d.profitSeries || [];
              periodLabel = d.periodLabel; span = d.spanSeconds;
              $('pnlLabel').textContent = d.pnlLabel;
              $('rateLabel').textContent = 'ISK ' + d.rateLabel;
              stat('profit', d.profitSession, function (v) { return abbr(v, true); }); cls($('profit'), d.profitSession);
              var units = Math.max(d.elapsedSeconds, 600) / d.rateSeconds;
              stat('rate', d.profitSession / units, function (v) { return abbr(v, true); }); cls($('rate'), d.profitSession);
              stat('trades', d.tradesSession, int);
              $('winRate').textContent = d.tradesSession ? Math.round(d.winningTrades / d.tradesSession * 100) + '%' : '—';
              stat('revenue', d.revenueSession, abbr);
              stat('relists', d.relistsSession, int);
              stat('sellOrders', d.sellOrdersCount, int);
              stat('buyOrders', d.buyOrdersCount, int);
              stat('beaten', d.beatenCount, int, true);
              $('beaten').style.color = d.beatenCount > 0 ? 'var(--warn)' : '';
              stat('iskInOrders', d.iskInOrders, abbr);
              stat('expectedProfit', d.expectedProfit, function (v) { return abbr(v, true); }); cls($('expectedProfit'), d.expectedProfit);
              stat('relistFees', d.relistFeesPaid, abbr, true);
              tick();
              sales(d.recentSales || []);
              renderTop(d.topItems || []);
            }).catch(function () {});
          }

          setInterval(tick, 1000);
          // Data moves at most every MarketWatchService sweep (2 min; ESI order cache ~20 min, wallet
          // ~1 h) and each poll reruns FIFO for every character, so polling faster only burns CPU.
          // The timer and the chart's last segment still advance every second on the client.
          setInterval(poll, 15000);
          poll();
        </script>
        </body>
        </html>
        """.trimIndent()
}
