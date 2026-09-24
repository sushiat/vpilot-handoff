package at.sushi.handoff.util

import at.sushi.handoff.network.CdmFlight
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs

/** Coarse urgency bucket for [CdmSlotDisplay]'s traffic-light dot -- derived purely from how soon
 *  [CdmSlotDisplay.minutesUntil] is, NOT a decode of the backend's own [CdmFlight.cdmSts] string
 *  (issue #143: cdmSts's exact status vocabulary and its relationship to the real vats.im/vdgs
 *  banner's own red/yellow/green were never confirmed, only guessed at as "presumably" related --
 *  [CdmSlotDisplay.statusText] carries that raw string alongside this so nothing is hidden, this
 *  just doesn't pretend to decode something that was never actually verified). */
enum class CdmUrgency { NEUTRAL, GOOD, ATTENTION, URGENT }

/** Everything the CDM tile needs to render, computed once per poll (not live-ticking between
 *  polls -- see HandoffConnectionService's CDM polling loop) from a raw [CdmFlight]. */
data class CdmSlotDisplay(
    val statusText: String?,
    val tobt: String?,
    val tsat: String?,
    val ctot: String?,
    val targetLabel: String?,
    val minutesUntil: Int?,
    val urgency: CdmUrgency
)

// Issue #143 lists "decide the poll interval"/color mapping as open questions -- these thresholds
// are a first guess for the MVP, not derived from anything the real site does.
private const val AttentionThresholdMinutes = 15
private const val UrgentThresholdMinutes = 5

/** Null when [flight] has nothing worth showing (no cdmData at all, or every milestone time still
 *  blank) -- the caller should treat that the same as "no CDM data for this flight," not render a
 *  half-empty tile. */
fun deriveCdmSlotDisplay(flight: CdmFlight?, nowEpochMillis: Long): CdmSlotDisplay? {
    val data = flight?.cdmData ?: return null
    // Prefer whichever milestone is most actionable right now: CTOT (an assigned slot) outranks
    // TSAT (a startup-approval target) outranks TOBT (the pilot's own target off-block time) --
    // each is only ever set once its predecessor's phase is reached, so this is effectively "the
    // latest milestone the backend has assigned," not an arbitrary priority.
    val (targetLabel, targetHhmm) = when {
        data.ctot.isNotBlank() -> "CTOT" to data.ctot
        data.tsat.isNotBlank() -> "TSAT" to data.tsat
        data.tobt.isNotBlank() -> "TOBT" to data.tobt
        else -> null to null
    }
    val minutesUntil = targetHhmm?.let { minutesUntilUtcHhmm(it, nowEpochMillis) }
    val urgency = when {
        minutesUntil == null -> CdmUrgency.NEUTRAL
        minutesUntil <= UrgentThresholdMinutes -> CdmUrgency.URGENT
        minutesUntil <= AttentionThresholdMinutes -> CdmUrgency.ATTENTION
        else -> CdmUrgency.GOOD
    }
    return CdmSlotDisplay(
        statusText = flight.cdmSts?.takeIf { it.isNotBlank() },
        tobt = data.tobt.takeIf { it.isNotBlank() },
        tsat = data.tsat.takeIf { it.isNotBlank() },
        ctot = data.ctot.takeIf { it.isNotBlank() },
        targetLabel = targetLabel,
        minutesUntil = minutesUntil,
        urgency = urgency
    )
}

/** Parses a bare "HHmm" UTC time-of-day (A-CDM's wire format, e.g. "1725" for 17:25Z -- issue
 *  #143's live-captured examples) and returns whole minutes from [nowEpochMillis] to the nearest
 *  occurrence of that time-of-day (negative if it's already passed). Picks whichever of
 *  yesterday/today/tomorrow (UTC calendar date) lands closest to now: A-CDM milestones are always
 *  within a few hours of "now" in practice and the wire format carries no separate date field to
 *  anchor to instead, so "nearest" is always the intended occurrence. Null for a blank/malformed
 *  string. */
fun minutesUntilUtcHhmm(hhmm: String, nowEpochMillis: Long): Int? {
    val digits = hhmm.trim()
    if (digits.length != 4 || digits.any { !it.isDigit() }) return null
    val hour = digits.substring(0, 2).toIntOrNull() ?: return null
    val minute = digits.substring(2, 4).toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null

    val now = Instant.ofEpochMilli(nowEpochMillis).atZone(ZoneOffset.UTC)
    val nearest = (-1L..1L)
        .map { dayOffset -> now.toLocalDate().plusDays(dayOffset).atTime(hour, minute).atZone(ZoneOffset.UTC) }
        .minByOrNull { abs(Duration.between(now, it).toMinutes()) }
        ?: return null
    return Duration.between(now, nearest).toMinutes().toInt()
}
