package ch.snapper.android.features.live

import ch.snapper.android.data.*
import java.time.Instant

/** Caller captures generation before each snapshot and socket callback; equality of selection alone is insufficient. */
data class LiveMarketState(
    val generation: Long = 0,
    val selection: LiveSelection? = null,
    val loading: Boolean = false,
    val snapshotFailed: Boolean = false,
    val candles: List<CandleDto> = emptyList(),
    val pending: Map<Instant, LiveMarketFrame.Candle> = emptyMap(),
    val live: Map<Instant, LiveMarketFrame.Candle> = emptyMap(),
    val tick: LiveMarketFrame.Tick? = null,
    val watermarks: Map<Pair<String, String>, Long> = emptyMap(),
)

object LiveMarketReducer {
    const val MAX_CANDLES = 100
    const val MAX_PENDING = 100
    const val MAX_SNAPSHOT_ROWS = 1_000

    fun select(state: LiveMarketState, selection: LiveSelection): LiveMarketState =
        if (LiveFrameDecoder.validSelection(selection)) LiveMarketState(state.generation + 1, selection, loading = true)
        else reset(state)

    fun reset(state: LiveMarketState): LiveMarketState = LiveMarketState(generation = state.generation + 1)

    fun receive(state: LiveMarketState, generation: Long, frame: LiveMarketFrame): LiveMarketState {
        if (generation != state.generation || frame.selection != state.selection) return state
        val topic = if (frame is LiveMarketFrame.Candle) frame.selection.candleTopic else frame.selection.tickTopic
        val key = frame.stamp.sessionId to topic
        if (frame.stamp.sequenceId <= (state.watermarks[key] ?: -1)) return state
        val result = when (frame) {
            is LiveMarketFrame.Tick -> if (newer(frame.stamp, state.tick?.stamp)) state.copy(tick = frame) else state
            is LiveMarketFrame.Candle -> receiveCandle(state, frame)
        }
        if (result == state) return state
        val marks = (state.watermarks - key) + (key to frame.stamp.sequenceId)
        return result.copy(watermarks = marks.entries.toList().takeLast(100).associate { it.toPair() })
    }

    fun snapshot(state: LiveMarketState, generation: Long, rows: List<CandleDto>, now: Instant): LiveMarketState {
        if (generation != state.generation || !state.loading) return state
        val selection = state.selection ?: return state
        val valid = rows.takeLast(MAX_SNAPSHOT_ROWS).mapNotNull { row -> snapshotRow(row, selection, now) }.toMap()
        val accepted = state.pending.filter { (time, frame) -> replacesSnapshot(frame, valid[time], now) }
        val merged = (valid + accepted.mapValues { stampedValue(it.value) }).toSortedMap().entries.toList().takeLast(MAX_CANDLES)
        val retained = merged.map { it.key }.toSet()
        return state.copy(loading = false, snapshotFailed = false, candles = merged.map { it.value },
            live = accepted.filterKeys { it in retained }, pending = emptyMap())
    }

    /** Streaming continues if REST fails; preserve the failure independently of usable live candles. */
    fun snapshotFailed(state: LiveMarketState, generation: Long, now: Instant): LiveMarketState {
        if (generation != state.generation || !state.loading) return state
        return snapshot(state, generation, emptyList(), now).copy(snapshotFailed = true)
    }

    private fun snapshotRow(row: CandleDto, selection: LiveSelection, now: Instant): Pair<Instant, CandleDto>? {
        if (row.exchange != selection.exchange || row.instrument != selection.instrument || row.timeframe != selection.timeframe) return null
        if (!LiveFrameDecoder.validCandle(row)) return null
        val openAt = LiveFrameDecoder.validTime(row.openAt, now) ?: return null
        return openAt to row.copy(openAt = openAt.toString())
    }

    private fun receiveCandle(state: LiveMarketState, frame: LiveMarketFrame.Candle): LiveMarketState {
        val previous = state.pending[frame.openAt] ?: state.live[frame.openAt]
        if (!newer(frame.stamp, previous?.stamp)) return state
        if (previous?.complete == true && !frame.complete) return state
        if (state.loading) return state.copy(pending = bounded(state.pending + (frame.openAt to frame), MAX_PENDING))
        val rows = state.candles.associateBy { Instant.parse(it.openAt) }
        if (previous == null && !replacesSnapshot(frame, rows[frame.openAt], frame.stamp.timestamp)) return state
        if (rows.isNotEmpty() && frame.openAt !in rows && frame.openAt < rows.keys.max()) return state
        val merged = (rows + (frame.openAt to stampedValue(frame))).toSortedMap().entries.toList().takeLast(MAX_CANDLES)
        val retained = merged.map { it.key }.toSet()
        return state.copy(candles = merged.map { it.value }, live = (state.live + (frame.openAt to frame)).filterKeys { it in retained })
    }

    private fun stampedValue(frame: LiveMarketFrame.Candle): CandleDto = frame.value.copy(
        complete = frame.complete, timestamp = frame.stamp.timestamp.toString(),
        sessionId = frame.stamp.sessionId, sequenceId = frame.stamp.sequenceId,
    )

    /** Unknown REST finality is protected conservatively; synthetic cache provenance is domain time. */
    private fun replacesSnapshot(frame: LiveMarketFrame.Candle, row: CandleDto?, now: Instant): Boolean {
        if (row == null) return true
        if (row.complete != false && !frame.complete) return false
        val timestamp = row.timestamp?.let { LiveFrameDecoder.validTime(it, now) } ?: return false
        val session = row.sessionId ?: return false
        val sequence = row.sequenceId ?: return false
        return newer(frame.stamp, LiveStamp(session, sequence, row.publicId, timestamp))
    }

    private fun bounded(rows: Map<Instant, LiveMarketFrame.Candle>, limit: Int): Map<Instant, LiveMarketFrame.Candle> =
        rows.toSortedMap().entries.toList().takeLast(limit).associate { it.key to it.value }

    private fun newer(candidate: LiveStamp, previous: LiveStamp?): Boolean {
        if (previous == null) return true
        if (candidate.timestamp < previous.timestamp) return false
        return candidate.sessionId != previous.sessionId || candidate.sequenceId > previous.sequenceId
    }
}
