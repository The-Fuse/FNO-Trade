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
| Picks (top 3 per side, max 2 per sector), conviction, BUY/SELL/WAIT | `engine/Rules.kt` |
| Trade plans frozen at suggestion time, replayed minute by minute | `engine/Plans.kt` |
| Minute loop: rank, breadth, sectors, OI build-up, volume profiles | `engine/Engine.kt` |
| Upstox REST + websocket (protobuf), futures OI | `upstox/` |
| Simulated market | `sim/SimMarket.kt` |
| API + SSE stream (`/api/stream`) | `web/Server.kt` |
| UI: Home (picks + plans), Terminal (flow, funnel, decks), Plans, Learn (F&O guide, `learn.html`), stock card | `resources/static/` |

A plan is made when a top-3 pick has a live order block, R:R ≥ 1:1.5, price on the right side of VWAP, within half a normal day of Z, before 2:30 PM. Entry is a limit at Z; stop just past the block; half off at T1 with the stop moved to entry, the rest at T2; everything out by 3:15 PM.

Selling buy/sell calls to the public in India needs SEBI Research Analyst registration. Sort that out before charging for the plans.

Rules, on finished 5-minute candles: a swing high/low needs 2 lower highs (higher lows) each side; a break of structure is a *close* beyond the latest swing; the order block is the last opposite candle where that leg began; it counts only if the move away leaves a fair value gap or closes one average candle beyond it; it dies on a 5-minute close through its far edge. Liquidity is untaken swing highs/lows (merged into equal highs/lows within a small tolerance) plus the previous day's high and low.

Sectors come from `src/main/resources/sectors.txt`; add new F&O names there.
