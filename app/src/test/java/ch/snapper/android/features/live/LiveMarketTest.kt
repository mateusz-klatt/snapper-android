package ch.snapper.android.features.live

import ch.snapper.android.data.*
import java.math.BigDecimal
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class LiveMarketTest {
    private val now = Instant.parse("2026-10-16T12:00:00Z")
    private val selection = LiveSelection("kraken", "BTC-USD", "1m")
    private val decoder = LiveFrameDecoder()
    private fun wire(extra: String = "", price: String = "123.123456789012345678901234567890"): String = """
        {"type":"candle","sequence_id":1,"session_id":"source","public_id":"event",
        "timestamp":"2026-10-16T12:00:00Z","topic":"market.kraken.BTC-USD.candles.1m",
        "exchange":"kraken","instrument":"BTC-USD","timeframe":"1m","open_at":"2026-10-16T11:59:00Z",
        "open":$price,"high":$price,"low":$price,"close":$price,"volume":"0.00000000000000000001"$extra}
    """
    private fun candle(index: Long, price: String = "1", complete: Boolean = false): LiveMarketFrame.Candle {
        val time = now.minusSeconds(60 * (200 - index))
        val value = BigDecimal(price)
        val row = CandleDto("BTC-USD", "kraken", "1m", time.toString(), value, value, value, value, BigDecimal.ZERO)
        return LiveMarketFrame.Candle(selection, LiveStamp("source", index, "event-$index", now), row, time, complete)
    }

    @Test fun flatBackendFramePreservesEveryDecimalDigitAndOptionalCompletion() {
        val frame = decoder.decode(wire().toByteArray(), selection, now) as LiveMarketFrame.Candle
        assertEquals(BigDecimal("123.123456789012345678901234567890"), frame.value.close)
        assertEquals(BigDecimal("0.00000000000000000001"), frame.value.volume)
        assertTrue(frame.complete)
        assertEquals(frame.openAt.toString(), frame.value.openAt)
    }

    @Test fun rejectsMalformedOversizedDeepForeignAndFutureFrames() {
        val invalid = listOf("{", wire(price = "1e999999999"), wire().replace("candles.1m", "candles.5m"),
            wire().replace("2026-10-16T12:00:00Z", "2026-10-16T12:00:01Z"),
            wire().replace("2026-10-16T11:59:00Z", "invalid"), wire(",\"origin\":\"replay\""),
            wire(",\"unused\":" + "[".repeat(30) + "0" + "]".repeat(30)))
        invalid.forEach { assertNull(decoder.decode(it.toByteArray(), selection, now)) }
        assertNull(decoder.decode(ByteArray(LiveFrameDecoder.MAX_FRAME_BYTES + 1), selection, now))
        assertNull(decoder.decode(byteArrayOf(0xc3.toByte(), 0x28), selection, now))
        assertNull(decoder.decode(wire().toByteArray(), selection.copy(instrument = "ETH-USD"), now))
    }

    @Test fun nullableDelayedTickIsTypedAndNeverBecomesZero() {
        val text = """{"type":"tick","sequence_id":0,"session_id":"s","public_id":"p",
            "timestamp":"2026-10-16T12:00:00Z","topic":"market.kraken.BTC-USD.ticks",
            "exchange":"kraken","instrument":"BTC-USD","volume":0,"last":null,"bid":"1.000000000000000001","is_delayed":true}"""
        val tick = decoder.decode(text.toByteArray(), selection, now) as LiveMarketFrame.Tick
        assertNull(tick.last); assertTrue(tick.isDelayed)
        assertEquals(BigDecimal("1.000000000000000001"), tick.bid)
    }

    @Test fun bufferedLiveReplacesOlderExplicitProvisionalSnapshotAndEmptySnapshot() {
        var state = LiveMarketReducer.select(LiveMarketState(), selection)
        val live = candle(1, "2")
        state = LiveMarketReducer.receive(state, state.generation, live)
        val old = live.value.copy(close = BigDecimal.ONE, high = BigDecimal.ONE, low = BigDecimal.ONE, open = BigDecimal.ONE,
            openAt = live.openAt.toString().replace("Z", ".000Z"), complete = false,
            timestamp = now.minusSeconds(1).toString(), sessionId = "source", sequenceId = 0)
        state = LiveMarketReducer.snapshot(state, state.generation, listOf(old), now)
        assertEquals(live.value.close, state.candles.single().close)
        assertTrue(state.pending.isEmpty())
        var empty = LiveMarketReducer.select(state, selection)
        empty = LiveMarketReducer.receive(empty, empty.generation, live)
        empty = LiveMarketReducer.snapshot(empty, empty.generation, emptyList(), now)
        assertEquals(1, empty.candles.size)
    }

    @Test fun generationRejectsFirstAAfterAToBToAAndLogout() {
        val first = LiveMarketReducer.select(LiveMarketState(), selection)
        val second = LiveMarketReducer.select(first, selection.copy(instrument = "ETH-USD"))
        val third = LiveMarketReducer.select(second, selection)
        assertEquals(third, LiveMarketReducer.receive(third, first.generation, candle(1)))
        assertEquals(third, LiveMarketReducer.snapshot(third, first.generation, listOf(candle(1).value), now))
        val logout = LiveMarketReducer.reset(third)
        assertEquals(logout, LiveMarketReducer.receive(logout, third.generation, candle(1)))
    }

    @Test fun buffersAndRenderedCandlesStayBoundedUnderBurst() {
        var state = LiveMarketReducer.select(LiveMarketState(), selection)
        for (i in 0L..150L) state = LiveMarketReducer.receive(state, state.generation, candle(i))
        assertEquals(100, state.pending.size)
        state = LiveMarketReducer.snapshot(state, state.generation, emptyList(), now)
        for (i in 151L..199L) state = LiveMarketReducer.receive(state, state.generation, candle(i))
        assertEquals(100, state.candles.size); assertEquals(100, state.live.size)
        assertEquals(candle(100).value.openAt, state.candles.first().openAt)
    }

    @Test fun snapshotDropsForeignInvalidAndFutureRowsWhileTickOrderingIsIndependent() {
        var state = LiveMarketReducer.select(LiveMarketState(), selection)
        val row = candle(1).value
        val rows = listOf(row, row.copy(instrument = "ETH-USD"), row.copy(openAt = "bad"),
            row.copy(openAt = now.plusSeconds(1).toString()), row.copy(high = BigDecimal.ZERO))
        state = LiveMarketReducer.snapshot(state, state.generation, rows, now)
        assertEquals(listOf(row), state.candles)
        val stamp = LiveStamp("source", 3, "tick3", now)
        val tick = LiveMarketFrame.Tick(selection, stamp, BigDecimal("1.1"), null, null, BigDecimal.ZERO, false, null)
        state = LiveMarketReducer.receive(state, state.generation, tick)
        val older = tick.copy(stamp = stamp.copy(sequenceId = 2, publicId = "tick2"))
        assertEquals(state, LiveMarketReducer.receive(state, state.generation, older))
        assertEquals(listOf(row), state.candles)
    }

    @Test fun duplicateOutOfOrderAndProvisionalAfterCompleteDoNotRegress() {
        var state = LiveMarketReducer.select(LiveMarketState(), selection)
        val complete = candle(1, "2", true)
        state = LiveMarketReducer.receive(state, state.generation, complete)
        val provisional = complete.copy(complete = false, stamp = complete.stamp.copy(sequenceId = 2, publicId = "new"))
        assertEquals(state, LiveMarketReducer.receive(state, state.generation, provisional))
        assertEquals(state, LiveMarketReducer.receive(state, state.generation, complete))
        val older = complete.copy(stamp = complete.stamp.copy(sequenceId = 0, publicId = "old"))
        assertEquals(state, LiveMarketReducer.receive(state, state.generation, older))
        state = LiveMarketReducer.snapshotFailed(state, state.generation, now)
        assertTrue(state.snapshotFailed); assertEquals(1, state.candles.size)
        assertFalse(state.loading)
    }
    @Test fun stableCandleIdAllowsProvisionalUpdatesAndFinalization() {
        var state = LiveMarketReducer.select(LiveMarketState(), selection)
        val first = candle(1, "1")
        val second = first.copy(value = first.value.copy(close = BigDecimal("2"), high = BigDecimal("2")),
            stamp = first.stamp.copy(sequenceId = 2))
        val final = second.copy(complete = true, stamp = second.stamp.copy(sequenceId = 3))
        state = LiveMarketReducer.receive(state, state.generation, first)
        state = LiveMarketReducer.receive(state, state.generation, second)
        assertEquals(BigDecimal("2"), state.pending[first.openAt]?.value?.close)
        state = LiveMarketReducer.receive(state, state.generation, final)
        assertTrue(state.pending[first.openAt]?.complete == true)
        assertEquals(state, LiveMarketReducer.receive(state, state.generation, final))
        assertEquals(state, LiveMarketReducer.receive(state, state.generation, second))
    }

    @Test fun stableCandleIdAcrossPublisherRestartUsesTimestampAndNewSequenceDomain() {
        var state = LiveMarketReducer.select(LiveMarketState(), selection)
        val first = candle(1).copy(stamp = LiveStamp("old-publisher", 500, "stable-candle", now.minusSeconds(1)))
        val restarted = first.copy(complete = true, stamp = LiveStamp("new-publisher", 1, "stable-candle", now))
        state = LiveMarketReducer.receive(state, state.generation, first)
        state = LiveMarketReducer.receive(state, state.generation, restarted)
        assertEquals("new-publisher", state.pending[first.openAt]?.stamp?.sessionId)
        assertTrue(state.pending[first.openAt]?.complete == true)
        assertEquals(state, LiveMarketReducer.receive(state, state.generation, first))
    }

    @Test fun finalRestProtectsAgainstBufferedAndLateProvisionalUpdates() {
        val provisional = candle(1, "2")
        val finalRow = provisional.value.copy(complete = true, close = BigDecimal.ONE, low = BigDecimal.ONE, open = BigDecimal.ONE,
            timestamp = now.toString(), sessionId = "source", sequenceId = 2)
        var state = LiveMarketReducer.select(LiveMarketState(), selection)
        state = LiveMarketReducer.receive(state, state.generation, provisional)
        state = LiveMarketReducer.snapshot(state, state.generation, listOf(finalRow), now)
        assertEquals(finalRow, state.candles.single())
        assertTrue(state.live.isEmpty())
        val late = provisional.copy(stamp = provisional.stamp.copy(sequenceId = 3))
        assertEquals(state, LiveMarketReducer.receive(state, state.generation, late))
    }

    @Test fun unknownSnapshotFinalityIsConservativelyProtected() {
        val provisional = candle(1, "2")
        var state = LiveMarketReducer.select(LiveMarketState(), selection)
        state = LiveMarketReducer.snapshot(state, state.generation, listOf(provisional.value), now)
        assertEquals(state, LiveMarketReducer.receive(state, state.generation, provisional))
    }

    @Test fun interleavedPublisherCannotReplayEarlierSequenceAtSameTimestamp() {
        var state = LiveMarketReducer.select(LiveMarketState(), selection)
        val first = candle(1).copy(stamp = LiveStamp("a", 10, "bar", now))
        val second = first.copy(stamp = LiveStamp("b", 1, "bar", now))
        state = LiveMarketReducer.receive(state, state.generation, first)
        state = LiveMarketReducer.receive(state, state.generation, second)
        assertEquals(state, LiveMarketReducer.receive(state, state.generation,
            first.copy(stamp = first.stamp.copy(sequenceId = 9))))
        assertTrue(LiveMarketReducer.select(state, selection).watermarks.isEmpty())
    }

    @Test fun newerRestFinalWinsBufferedFinalAndWatermarksStayBounded() {
        val buffered = candle(1, "2", true).copy(
            stamp = LiveStamp("source", 1, "bar", now.minusSeconds(1)))
        val rest = buffered.value.copy(complete = true, timestamp = now.toString(),
            sessionId = "source", sequenceId = 2, close = BigDecimal.ONE, low = BigDecimal.ONE, open = BigDecimal.ONE)
        var state = LiveMarketReducer.select(LiveMarketState(), selection)
        state = LiveMarketReducer.receive(state, state.generation, buffered)
        state = LiveMarketReducer.snapshot(state, state.generation, listOf(rest), now)
        assertEquals(rest, state.candles.single())
        for (index in 0..110) {
            val tick = LiveMarketFrame.Tick(selection, LiveStamp("publisher-$index", 1, "tick", now),
                BigDecimal.ONE, null, null, BigDecimal.ZERO, false, null)
            state = LiveMarketReducer.receive(state, state.generation, tick)
        }
        assertEquals(100, state.watermarks.size)
        assertTrue(LiveMarketReducer.reset(state).watermarks.isEmpty())
    }

}
