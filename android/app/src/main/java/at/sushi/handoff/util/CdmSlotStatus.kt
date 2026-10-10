package at.sushi.handoff.util

import at.sushi.handoff.network.CdmFlight
import at.sushi.handoff.network.CdmFlightData
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** Coarse urgency bucket for [CdmSlotDisplay]'s traffic-light dot -- derived purely from the time
 *  to/since the target ([CdmSlotDisplay.minutesUntil]), NOT a decode of the backend's own
 *  [CdmFlight.cdmSts] string
 *  (issue #143: cdmSts's exact status vocabulary and its relationship to the real vats.im/vdgs
 *  banner's own red/yellow/green were never confirmed, only guessed at as "presumably" related --
 *  [CdmSlotDisplay.statusText] carries that raw string alongside this so nothing is hidden, this
 *  just doesn't pretend to decode something that was never actually verified). */
enum class CdmUrgency {
    NEUTRAL,
    /** Green. */
    GOOD,
    /** Yellow. */
    ATTENTION,
    /** Red. */
    URGENT,
    /** Alternating green/yellow -- the last minute of a TOBT/TSAT window. */
    FLASHING
}

/** Everything the CDM tile needs to render, derived from a raw [CdmFlight] once per poll. The
 *  time-dependent parts ([minutesUntil], [urgency]) go stale between polls, so the UI re-derives
 *  them every second with [at]. */
data class CdmSlotDisplay(
    val statusText: String?,
    val tobt: String?,
    val tsat: String?,
    val ctot: String?,
    /** TOBT the pilot has asked for but the backend hasn't accepted into [tobt] yet. */
    val requestedTobt: String?,
    /** Filed EOBT -- only ever the displayed target as a last resort, before any TOBT exists. */
    val eobt: String?,
    /** Why the controller/flow system is holding the flight (cdmData.reason), when it is. */
    val reason: String?,
    /** Assigned departure runway and SID, split out of cdmData.depInfo ("29/LANUX2C"). */
    val depRunway: String?,
    val sid: String?,
    val targetLabel: String?,
    /** The target's bare "HHmm" UTC time, kept so [at] can re-derive [minutesUntil]/[urgency]. */
    val targetHhmm: String?,
    val minutesUntil: Int?,
    /** True when the target is only a request or the filed EOBT, not an accepted CDM time. */
    val tentative: Boolean,
    /** True for an accepted TOBT/TSAT target, which follow the plugin's +-5 minute window rules. */
    val windowed: Boolean,
    /** The backend has an ASRT (ready for start-up) for this flight -- the TOBT/TSAT window then
     *  never expires, matching the CDM plugin's own "no ASRT" condition for invalidating times. */
    val asrtSet: Boolean,
    val urgency: CdmUrgency
) {
    /** Same display with [minutesUntil]/[urgency] re-derived for [nowEpochMillis]. */
    fun at(nowEpochMillis: Long): CdmSlotDisplay {
        val minutes = targetHhmm?.let { minutesUntilUtcHhmm(it, nowEpochMillis) }
        return copy(minutesUntil = minutes, urgency = cdmUrgency(minutes, tentative, windowed, asrtSet))
    }
}

// CTOT keeps the original first-guess thresholds (issue #143 lists the color mapping as an open
// question) -- only TOBT/TSAT follow the plugin's real window rules, see [cdmUrgency].
private const val AttentionThresholdMinutes = 15
private const val UrgentThresholdMinutes = 5

// The rpuig2001/CDM EuroScope plugin's TOBT/TSAT window, in whole minutes either side of the time:
// yellow before it opens, green inside it, flashing yellow/green as it runs out, and expired (red)
// after that -- unless the flight already has an ASRT. The plugin itself flashes only for the
// final minute; we start two minutes early (+4) because not every controller can take a flight
// right at the edge of the window.
private const val WindowMinutes = 5
private const val FlashFromMinutesAfter = 4

/** [minutes] is the time until the target (negative once it's passed). [windowed] is true for an
 *  accepted TOBT/TSAT; a CTOT (neither tentative nor windowed) keeps the old simple thresholds. */
fun cdmUrgency(minutes: Int?, tentative: Boolean, windowed: Boolean, asrtSet: Boolean): CdmUrgency = when {
    tentative || minutes == null -> CdmUrgency.NEUTRAL
    windowed -> when {
        minutes > WindowMinutes -> CdmUrgency.ATTENTION
        minutes > -FlashFromMinutesAfter || asrtSet -> CdmUrgency.GOOD
        minutes >= -WindowMinutes -> CdmUrgency.FLASHING
        else -> CdmUrgency.URGENT
    }
    minutes <= UrgentThresholdMinutes -> CdmUrgency.URGENT
    minutes <= AttentionThresholdMinutes -> CdmUrgency.ATTENTION
    else -> CdmUrgency.GOOD
}

/** Null only when [flight] is null, or has no milestone time and no filed EOBT at all. Falls back
 *  past the accepted CDM times to the pilot's requested TOBT, then the filed EOBT, so the tile is
 *  visible (marked [CdmSlotDisplay.tentative], shown with a trailing "?") as soon as the backend
 *  tracks the flight -- showing that the system is active even before a TOBT has been accepted. */
fun deriveCdmSlotDisplay(flight: CdmFlight?, nowEpochMillis: Long): CdmSlotDisplay? {
    flight ?: return null
    val data = flight.cdmData ?: CdmFlightData()
    val eobt = hhmm(flight.eobt).takeIf { it.isNotBlank() }
    // The backend's top-level tobt is the accepted one; cdmData.tobt can stay blank even then.
    val tobt = hhmm(data.tobt.ifBlank { flight.tobt.orEmpty() })
    val ctot = hhmm(data.ctot.ifBlank { flight.ctot.orEmpty() })
    val tsat = hhmm(data.tsat)
    val reqTobt = hhmm(data.reqTobt)
    val depParts = data.depInfo.split("/", limit = 2)
    val depRunway = depParts[0].trim().ifEmpty { null }
    val sid = depParts.getOrNull(1)?.trim()?.ifEmpty { null }
    // Prefer whichever milestone is most actionable right now: CTOT (an assigned slot) outranks
    // TSAT (a startup-approval target) outranks TOBT (the pilot's own target off-block time) --
    // each is only ever set once its predecessor's phase is reached, so this is effectively "the
    // latest milestone the backend has assigned," not an arbitrary priority. Below those, an
    // as-yet-unaccepted requested TOBT, then the filed EOBT, both tentative.
    val (targetLabel, targetHhmm, tentative) = when {
        ctot.isNotBlank() -> Triple("CTOT", ctot, false)
        tsat.isNotBlank() -> Triple("TSAT", tsat, false)
        tobt.isNotBlank() -> Triple("TOBT", tobt, false)
        reqTobt.isNotBlank() -> Triple("TOBT?", reqTobt, true)
        eobt != null -> Triple("EOBT?", eobt, true)
        else -> return null
    }
    return CdmSlotDisplay(
        statusText = flight.cdmSts?.takeIf { it.isNotBlank() },
        tobt = tobt.takeIf { it.isNotBlank() },
        tsat = tsat.takeIf { it.isNotBlank() },
        ctot = ctot.takeIf { it.isNotBlank() },
        requestedTobt = reqTobt.takeIf { it.isNotBlank() },
        eobt = eobt,
        reason = data.reason.takeIf { it.isNotBlank() },
        depRunway = depRunway,
        sid = sid,
        targetLabel = targetLabel,
        targetHhmm = targetHhmm,
        minutesUntil = null,
        tentative = tentative,
        windowed = targetLabel == "TOBT" || targetLabel == "TSAT",
        asrtSet = data.asrt.isNotBlank(),
        urgency = CdmUrgency.NEUTRAL
    ).at(nowEpochMillis)
}

/** The backend sends the same time as "HHmm" (flight-level fields) or "HHmmss" (the CDM sequencer's
 *  cdmData tobt/tsat/ttot, seen live once a TOBT is processed) -- normalized to "HHmm", since the
 *  UI works in whole minutes. Blank/unrecognized strings pass through unchanged. */
fun hhmm(raw: String?): String {
    val t = raw.orEmpty().trim()
    return if (t.length == 6 && t.all { it.isDigit() }) t.substring(0, 4) else t
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

    // Whole minutes only: the wire format has no seconds, so compare against the current minute.
    val now = Instant.ofEpochMilli(nowEpochMillis).truncatedTo(ChronoUnit.MINUTES).atZone(ZoneOffset.UTC)
    val nearest = (-1L..1L)
        .map { dayOffset -> now.toLocalDate().plusDays(dayOffset).atTime(hour, minute).atZone(ZoneOffset.UTC) }
        .minByOrNull { abs(Duration.between(now, it).toMinutes()) }
        ?: return null
    return Duration.between(now, nearest).toMinutes().toInt()
}
