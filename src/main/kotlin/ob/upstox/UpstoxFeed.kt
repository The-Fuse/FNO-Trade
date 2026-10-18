package ob.upstox

import com.upstox.marketdatafeederv3udapi.rpc.proto.MarketDataFeedV3.Feed
import com.upstox.marketdatafeederv3udapi.rpc.proto.MarketDataFeedV3.FeedResponse
import com.upstox.marketdatafeederv3udapi.rpc.proto.MarketDataFeedV3.LTPC
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import ob.Candle
import ob.bucket
import ob.market.Series
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Upstox market data feed v3: subscribes every F&O stock and its near-month future in "full"
 * mode. Stock messages become ticks (last price + volume) and exchange 1-minute bars ("I1");
 * future messages update the stock's open interest.
 * Reconnects after drops while [keepRunning] says the market is still worth listening to.
 */
class UpstoxFeed(
    private val api: Upstox,
    private val byKey: Map<String, Series>,
    private val byFut: Map<String, Series>,
) {
    private val log = LoggerFactory.getLogger("feed")
    private val ws = HttpClient(CIO) {
        install(WebSockets)
        engine { requestTimeout = 0 }
    }

    suspend fun run(keepRunning: () -> Boolean) {
        while (currentCoroutineContext().isActive && keepRunning()) {
            try {
                connect()
            } catch (e: CancellationException) {
                throw e
            } catch (e: UpstoxAuthException) {
                throw e
            } catch (e: Exception) {
                log.warn("Feed dropped: {}", e.message)
            }
            delay(3_000)
        }
        log.info("Feed stopped")
    }

    private suspend fun connect() {
        val url = api.feedUrl()
        ws.webSocket(urlString = url) {
            val sub = buildJsonObject {
                put("guid", UUID.randomUUID().toString())
                put("method", "sub")
                putJsonObject("data") {
                    put("mode", "full")
                    putJsonArray("instrumentKeys") { (byKey.keys + byFut.keys).forEach { add(it) } }
                }
            }
            send(Frame.Binary(true, sub.toString().encodeToByteArray()))
            log.info("Feed connected, {} stocks + {} futures", byKey.size, byFut.size)
            for (frame in incoming) {
                if (frame is Frame.Binary) handle(FeedResponse.parseFrom(frame.data))
            }
        }
    }

    private fun handle(r: FeedResponse) {
        val nowSec = System.currentTimeMillis() / 1000
        for ((key, f) in r.feedsMap) {
            val fut = byFut[key]
            if (fut != null) {
                if (f.feedUnionCase == Feed.FeedUnionCase.FULLFEED && f.fullFeed.hasMarketFF() && f.fullFeed.marketFF.oi > 0) {
                    fut.oi = f.fullFeed.marketFF.oi.toLong()
                }
                continue
            }
            val s = byKey[key] ?: continue
            when (f.feedUnionCase) {
                Feed.FeedUnionCase.FULLFEED -> if (f.fullFeed.hasMarketFF()) {
                    val m = f.fullFeed.marketFF
                    var bars = false
                    for (o in m.marketOHLC.ohlcList) {
                        if (o.interval != "I1" || o.ts <= 0) continue
                        s.upsert(Candle(bucket(toSec(o.ts), 60), o.open, o.high, o.low, o.close, o.vol), nowSec)
                        bars = true
                    }
                    // exchange minute bars carry the volume; count it from vtt only when they're absent
                    tick(s, m.ltpc, if (bars) -1 else m.vtt, r.currentTs)
                }
                Feed.FeedUnionCase.LTPC -> tick(s, f.ltpc, -1, r.currentTs)
                else -> {}
            }
        }
    }

    private fun tick(s: Series, p: LTPC, vtt: Long, fallbackMs: Long) {
        val ms = if (p.ltt > 0) p.ltt else fallbackMs
        s.tick(ms, p.ltp, vtt)
    }

    private fun toSec(ts: Long) = if (ts > 10_000_000_000L) ts / 1000 else ts
}
