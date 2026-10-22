# OB Terminal

Live board of NSE F&O stocks ranked by intraday strength, with order blocks and liquidity drawn on 5-minute charts. Kotlin + Ktor server; plain HTML/JS front end with TradingView lightweight-charts.

## Run

```bash
./gradlew run
```

Open http://localhost:8080. Without Upstox keys it runs on a simulated market (`SIM_START`, `SIM_SPEED` in `.env`).

## Connect Upstox

1. Create an app at https://account.upstox.com/developer/apps with redirect URL `http://localhost:8080/upstox/callback`.
2. `cp .env.example .env` and fill `UPSTOX_API_KEY`, `UPSTOX_API_SECRET`.
3. Restart, click **Connect Upstox**. The token is saved in `data/` and lasts until 3:30 AM IST.

On the first start each day the server loads 50 sessions of daily history (cached in `data/`) and the session's 1-minute candles, about 30 s each. During market hours it then streams the Upstox v3 feed.

## How it works

| Piece | File |
|---|---|
| Ticks → 1-min candles → 5-min candles | `market/Series.kt` |
| Strength = today's % move ÷ median 50-session true range % | `engine/Strength.kt` |
| Order blocks, breaks of structure, liquidity | `engine/OrderBlocks.kt` |
| Z (entry), stop, two targets, levels behind, R:R | `engine/Levels.kt` |
| The strategy's dials (picks, filters, stop width, exit), conviction, BUY/SELL/WAIT | `engine/Rules.kt` |
| Trade plans frozen at suggestion time, replayed minute by minute | `engine/Plans.kt` |
| Minute loop: rank, breadth, sectors, OI build-up, volume profiles | `engine/Engine.kt` |
| Upstox REST + websocket (protobuf), futures OI | `upstox/` |
| Simulated market | `sim/SimMarket.kt` |
| API + SSE stream (`/api/stream`) | `web/Server.kt` |
| Replays past sessions through the engine and sizes the trades for an account | `backtest/Backtest.kt` |
| UI: Home (picks + plans), Terminal (flow, funnel, decks), Plans, Learn (F&O guide, `learn.html`), stock card | `resources/static/` |

## The strategy

A plan is made when the #1 pick on a side has a live order block, R:R ≥ 1:1.5, price on the right side of VWAP, within half a normal day of Z, at least 60% of F&O stocks moving the same way, before 2:30 PM. Entry is a limit at Z; the stop is just past the block and at least 1% from the entry; everything exits at T2 (T1 is only a checkpoint); anything still open closes at 3:15 PM. Position size is a % of capital at risk to the stop, capped by intraday margin; a trade that can't risk ₹250 is skipped.

Every rule is a dial in `engine/Rules.kt` (`PICKS`, `MIN_MARKET`, `MIN_STOP_PCT`, `TARGET`, `MIN_RR`, `FIRST_ENTRY_MIN`, `LAST_ENTRY_MIN`, `EXIT_MIN`, …) and can be overridden with the same names in `.env` or the environment.

These defaults are what survived a backtest of 40 sessions (24 Aug – 21 Oct 2026): tuned on the first 30, checked on the last 10. For ₹10,000 at 4% risk per trade it made +31% over the 40 days after charges, with a worst drop of 9%. That is 38 trades, and 3 of them made most of the profit, so treat it as promising, not proven: paper-trade it on the Plans page before risking money.

## Backtest

```bash
./gradlew backtest --args="--days 40 --check 10 --capital 10000 --risk 4 --minrisk 250"
./gradlew backtest --args="--days 40 --check 10 --capital 10000 --risk 4 --set MIN_STOP_PCT=0.75,TARGET=half"
```

Needs the Upstox login the server saved. Candles are cached under `data/cache` and each replayed day under `data/replays/`, so after the first download a new idea runs in seconds. Every run is appended to `data/experiments.txt`. The sizing assumes 20% intraday margin, fills exactly at Z and no slippage, and Upstox-style charges (₹20 or 0.1% per order, STT, exchange, SEBI, stamp duty, GST).

Selling buy/sell calls to the public in India needs SEBI Research Analyst registration. Sort that out before charging for the plans.

Rules, on finished 5-minute candles: a swing high/low needs 2 lower highs (higher lows) each side; a break of structure is a *close* beyond the latest swing; the order block is the last opposite candle where that leg began; it counts only if the move away leaves a fair value gap or closes one average candle beyond it; it dies on a 5-minute close through its far edge. Liquidity is untaken swing highs/lows (merged into equal highs/lows within a small tolerance) plus the previous day's high and low.

Sectors come from `src/main/resources/sectors.txt`; add new F&O names there.
