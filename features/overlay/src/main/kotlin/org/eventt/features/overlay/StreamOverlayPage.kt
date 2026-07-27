package org.eventt.features.overlay

// The actual OBS Browser Source page: transparent background, all styling/animation self-contained
// (no CDN — OBS's embedded Chromium shouldn't depend on outbound network to render a local overlay).
// Visual config (accent color) comes from the page's own query string, e.g.
// http://127.0.0.1:8001/?accent=00e5ff — read client-side in JS, so the server stays a static file.
object StreamOverlayPage {
    val HTML =
        """
        <!doctype html>
        <html>
        <head>
        <meta charset="utf-8">
        <title>eventt stream overlay</title>
        <style>
          :root { --accent: #00e5ff; }
          * { box-sizing: border-box; }
          html, body {
            margin: 0; padding: 0; background: transparent;
            font-family: 'Segoe UI', system-ui, sans-serif;
            overflow: hidden;
          }
          #panel {
            display: inline-flex;
            flex-direction: column;
            gap: 12px;
            margin: 14px;
            padding: 16px 22px;
            border-radius: 14px;
            background: linear-gradient(135deg, rgba(10,14,20,0.78), rgba(10,14,20,0.55));
            border: 1px solid color-mix(in srgb, var(--accent) 55%, transparent);
            box-shadow: 0 0 18px color-mix(in srgb, var(--accent) 35%, transparent), inset 0 0 20px rgba(0,0,0,0.35);
            backdrop-filter: blur(4px);
            animation: glow 3s ease-in-out infinite;
          }
          @keyframes glow {
            0%, 100% { box-shadow: 0 0 14px color-mix(in srgb, var(--accent) 25%, transparent), inset 0 0 20px rgba(0,0,0,0.35); }
            50% { box-shadow: 0 0 26px color-mix(in srgb, var(--accent) 55%, transparent), inset 0 0 20px rgba(0,0,0,0.35); }
          }
          .row { display: flex; gap: 22px; }
          .row + .row { padding-top: 12px; border-top: 1px solid color-mix(in srgb, var(--accent) 25%, transparent); }
          .stat { display: flex; flex-direction: column; align-items: center; min-width: 80px; }
          .stat .icon { font-size: 18px; line-height: 1; margin-bottom: 4px; filter: drop-shadow(0 0 4px var(--accent)); }
          .stat .label { font-size: 10px; letter-spacing: 0.12em; text-transform: uppercase; color: #9fb3c0; margin-bottom: 2px; }
          .stat .value {
            font-size: 20px; font-weight: 700; color: #f2f8fb;
            font-variant-numeric: tabular-nums;
            text-shadow: 0 0 8px color-mix(in srgb, var(--accent) 60%, transparent);
            transition: color 0.25s ease, transform 0.25s ease;
          }
          .stat .value.up { color: #4ade80; transform: scale(1.12); }
          .stat .value.down { color: #f87171; transform: scale(1.12); }
        </style>
        </head>
        <body>
        <div id="panel">
          <div class="row">
            <div class="stat"><div class="icon">&#9201;</div><div class="label">Session</div><div class="value" id="timer">00:00:00</div></div>
            <div class="stat"><div class="icon">&#128200;</div><div class="label">Trades</div><div class="value" id="trades">0</div></div>
            <div class="stat"><div class="icon">&#128176;</div><div class="label">Profit</div><div class="value" id="profit">0 ISK</div></div>
            <div class="stat"><div class="icon">&#128260;</div><div class="label">Relists</div><div class="value" id="relists">0</div></div>
          </div>
          <div class="row">
            <div class="stat"><div class="icon">&#128228;</div><div class="label">Sell Orders</div><div class="value" id="sellOrders">0</div></div>
            <div class="stat"><div class="icon">&#128229;</div><div class="label">Buy Orders</div><div class="value" id="buyOrders">0</div></div>
            <div class="stat"><div class="icon">&#128230;</div><div class="label">ISK in Orders</div><div class="value" id="iskInOrders">0 ISK</div></div>
            <div class="stat"><div class="icon">&#127919;</div><div class="label">Expected Profit</div><div class="value" id="expectedProfit">0 ISK</div></div>
            <div class="stat"><div class="icon">&#129534;</div><div class="label">Relist Fees</div><div class="value" id="relistFees">0 ISK</div></div>
          </div>
        </div>
        <script>
          var params = new URLSearchParams(location.search);
          var accent = params.get('accent');
          if (accent) document.documentElement.style.setProperty('--accent', '#' + accent.replace('#', ''));

          var elapsedBase = 0, elapsedTickedAt = Date.now();

          function pad(n) { return n < 10 ? '0' + n : '' + n; }

          function fmtIsk(n) {
            var sign = n < 0 ? '-' : '';
            var abs = Math.abs(n);
            var text = abs >= 1e9 ? (abs / 1e9).toFixed(2) + 'B'
              : abs >= 1e6 ? (abs / 1e6).toFixed(2) + 'M'
              : abs >= 1e3 ? (abs / 1e3).toFixed(1) + 'K'
              : Math.round(abs).toString();
            return sign + text + ' ISK';
          }

          function flash(el, dir) {
            el.classList.remove('up', 'down');
            void el.offsetWidth; // restart the CSS transition
            el.classList.add(dir);
            setTimeout(function () { el.classList.remove('up', 'down'); }, 600);
          }

          function setStat(id, value, prevRef, isIsk) {
            var el = document.getElementById(id);
            var prev = prevRef.value;
            el.textContent = isIsk ? fmtIsk(value) : value;
            if (prev !== null && value !== prev) flash(el, value > prev ? 'up' : 'down');
            prevRef.value = value;
          }

          var refs = {
            trades: { value: null }, profit: { value: null }, relists: { value: null },
            sellOrders: { value: null }, buyOrders: { value: null }, iskInOrders: { value: null },
            expectedProfit: { value: null }, relistFees: { value: null }
          };

          function tickTimer() {
            var secs = elapsedBase + Math.floor((Date.now() - elapsedTickedAt) / 1000);
            var h = Math.floor(secs / 3600), m = Math.floor((secs % 3600) / 60), s = secs % 60;
            document.getElementById('timer').textContent = pad(h) + ':' + pad(m) + ':' + pad(s);
          }

          function poll() {
            fetch('/api/stats').then(function (r) { return r.json(); }).then(function (d) {
              elapsedBase = d.elapsedSeconds;
              elapsedTickedAt = Date.now();
              setStat('trades', d.tradesSession, refs.trades, false);
              setStat('profit', d.profitSession, refs.profit, true);
              setStat('relists', d.relistsSession, refs.relists, false);
              setStat('sellOrders', d.sellOrdersCount, refs.sellOrders, false);
              setStat('buyOrders', d.buyOrdersCount, refs.buyOrders, false);
              setStat('iskInOrders', d.iskInOrders, refs.iskInOrders, true);
              setStat('expectedProfit', d.expectedProfit, refs.expectedProfit, true);
              setStat('relistFees', d.relistFeesPaid, refs.relistFees, true);
            }).catch(function () {});
          }

          setInterval(tickTimer, 1000);
          setInterval(poll, 2000);
          poll();
        </script>
        </body>
        </html>
        """.trimIndent()
}
