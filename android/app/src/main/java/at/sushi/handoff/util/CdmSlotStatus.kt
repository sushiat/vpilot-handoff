package at.sushi.handoff.util

import at.sushi.handoff.network.CdmFlight
import at.sushi.handoff.network.CdmFlightData
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** Where the pilot stands relative to the start-up window (issue #165), mirroring the banner on the
 *  vdgs site: yellow ("prepare") before it, green ("request start-up") inside it, red (missed) after.
 *  [UNKNOWN] is the grey state: nothing to anchor a window to yet. */
enum class CdmWindowPhase { UNKNOWN, BEFORE, OPEN, MISSED }

// The site documents the window as roughly -5 to +6 minutes around the off-block reference time.
private const val WindowOpensMinutesBefore = 5
private const val WindowClosesMinutesAfter = 6

/** Phase and the one number the chip shows for it, given [minutesUntilReference] (negative once the
 *  reference has passed): minutes until the window opens (BEFORE), minutes left in it (OPEN) or minutes
 *  since it closed (MISSED). With an ASRT on the flight the window never counts as missed -- the
 *  pilot already asked for start-up -- and OPEN then carries no number. */
fun cdmWindow(minutesUntilReference: Int, asrtSet: Boolean): Pair<CdmWindowPhase, Int?> = when {
    minutesUntilReference > WindowOpensMinutesBefore ->
        CdmWindowPhase.BEFORE to (minutesUntilReference - WindowOpensMinutesBefore)
    minutesUntilReference >= -WindowClosesMinutesAfter ->
        CdmWindowPhase.OPEN to (minutesUntilReference + WindowClosesMinutesAfter)
    asrtSet -> CdmWindowPhase.OPEN to null
    else -> CdmWindowPhase.MISSED to (-WindowClosesMinutesAfter - minutesUntilReference)
}

/** Everything the CDM tile needs to render, derived from a raw [CdmFlight] once per poll. The
 *  time-dependent parts ([phase], [minutes]) go stale between polls, so the UI re-derives them
 *  every second with [at]. */
data class CdmSlotDisplay(
    /** The airport supports CDM but the backend isn't tracking the flight yet (placeholder). */
    val waiting: Boolean,
    val eobt: String?,
    /** The accepted T/OBT ("tobt" on the wire). */
    val tobt: String?,
    val tsat: String?,
    val ctot: String?,
    /** Expected taxi-out time in minutes, from the airport record. */
    val exot: Int?,
    /** T/OBT the pilot has asked for but the backend hasn't accepted yet. */
    val requestedTobt: String?,
    /** Why the controller/flow system is holding the flight (cdmData.reason), when it is. */
    val reason: String?,
    val atfcmStatus: String?,
    /** Assigned departure runway and SID, split out of cdmData.depInfo ("29/LANUX2C"). */
    val depRunway: String?,
    val sid: String?,
    /** Which time the window is anchored on: "TSAT", "CTOT-EXOT" or "T/OBT"; null when none exists
     *  (a filed EOBT alone deliberately doesn't anchor a window). */
    val referenceLabel: String?,
    /** The reference's bare "HHmm" UTC time, kept so [at] can re-derive [phase]/[minutes]. */
    val referenceHhmm: String?,
    val asrtSet: Boolean,
    val phase: CdmWindowPhase,
    val minutes: Int?
) {
    /** Same display with [phase]/[minutes] re-derived for [nowEpochMillis]. */
    fun at(nowEpochMillis: Long): CdmSlotDisplay {
        val untilReference = referenceHhmm?.let { minutesUntilUtcHhmm(it, nowEpochMillis) }
            ?: return copy(phase = CdmWindowPhase.UNKNOWN, minutes = null)
        val (newPhase, newMinutes) = cdmWindow(untilReference, asrtSet)
        return copy(phase = newPhase, minutes = newMinutes)
    }
}

private fun formatMinutes(minutes: Int): String =
    if (minutes >= 60) "${minutes / 60}h${minutes % 60}m" else "${minutes}m"

/** The chip's text. [compact] is the narrowest layout, which has to drop the "STUP" prefix. */
fun CdmSlotDisplay.chipText(compact: Boolean): String {
    fun withPrefix(text: String) = if (compact) text else "STUP $text"
    return when (phase) {
        CdmWindowPhase.UNKNOWN -> withPrefix("T-?")
        CdmWindowPhase.BEFORE -> withPrefix("T-${formatMinutes(minutes ?: 0)}")
        CdmWindowPhase.OPEN -> when (minutes) {
            null -> withPrefix("ok")
            else -> if (compact) formatMinutes(minutes) else "STUP ${formatMinutes(minutes)} left"
        }
        // "MISS" has to stay readable as "start-up is gone" even when the number doesn't fit.
        CdmWindowPhase.MISSED -> if (compact) "MISS" else "MISS +${formatMinutes(minutes ?: 0)}"
    }
}

/** The drawer's one-line explanation of [phase] -- the roomy version of [chipText]. */
fun CdmSlotDisplay.windowSummary(): String {
    val source = referenceLabel?.let { " ($it)" }.orEmpty()
    return when (phase) {
        CdmWindowPhase.UNKNOWN -> if (waiting) "waiting for flight" else "no T/OBT or TSAT yet"
        CdmWindowPhase.BEFORE -> "opens in ${formatMinutes(minutes ?: 0)}$source"
        CdmWindowPhase.OPEN ->
            if (minutes == null) "open, start-up requested (ASRT)$source"
            else "open, ${formatMinutes(minutes)} left$source"
        CdmWindowPhase.MISSED -> "missed ${formatMinutes(minutes ?: 0)} ago$source - update T/OBT, coordinate"
    }
}

/** Null only when [flight] is null, or has no milestone time and no filed EOBT at all.
 *
 *  The start-up window is anchored on the site's own off-block reference order: TSAT, then
 *  CTOT minus the airport's expected taxi-out time, then the accepted T/OBT. A filed EOBT (last in
 *  the site's order) and a merely requested T/OBT deliberately anchor nothing: the chip stays grey
 *  until something is accepted, so a pilot who just hasn't set a T/OBT yet doesn't see "MISS". */
fun deriveCdmSlotDisplay(flight: CdmFlight?, nowEpochMillis: Long, taxiMinutes: Int? = null): CdmSlotDisplay? {
    flight ?: return null
    val data = flight.cdmData ?: CdmFlightData()
    val eobt = hhmm(flight.eobt).takeIf { it.isNotBlank() }
    // The backend's top-level tobt is the accepted one; cdmData.tobt can stay blank even then.
    val tobt = hhmm(data.tobt.ifBlank { flight.tobt.orEmpty() })
    val ctot = hhmm(data.ctot.ifBlank { flight.ctot.orEmpty() })
    val tsat = hhmm(data.tsat)
    val reqTobt = hhmm(data.reqTobt)
    if (listOf(tobt, ctot, tsat, reqTobt).all { it.isBlank() } && eobt == null) return null
    val depParts = data.depInfo.split("/", limit = 2)
    val ctotMinusExot = taxiMinutes?.let { subtractMinutesHhmm(ctot, it) }
    val (referenceLabel, referenceHhmm) = when {
        tsat.isNotBlank() -> "TSAT" to tsat
        ctotMinusExot != null -> "CTOT-EXOT" to ctotMinusExot
        tobt.isNotBlank() -> "T/OBT" to tobt
        else -> null to null
    }
    return CdmSlotDisplay(
        waiting = false,
        eobt = eobt,
        tobt = tobt.takeIf { it.isNotBlank() },
        tsat = tsat.takeIf { it.isNotBlank() },
        ctot = ctot.takeIf { it.isNotBlank() },
        exot = taxiMinutes,
        requestedTobt = reqTobt.takeIf { it.isNotBlank() && it != tobt },
        reason = data.reason.takeIf { it.isNotBlank() },
        atfcmStatus = flight.atfcmStatus?.takeIf { it.isNotBlank() },
        depRunway = depParts[0].trim().ifEmpty { null },
        sid = depParts.getOrNull(1)?.trim()?.ifEmpty { null },
        referenceLabel = referenceLabel,
        referenceHhmm = referenceHhmm,
        asrtSet = data.asrt.isNotBlank(),
        phase = CdmWindowPhase.UNKNOWN,
        minutes = null
    ).at(nowEpochMillis)
}

/** [hhmm] minus [minutes], wrapping past midnight; null for a blank/malformed time. */
fun subtractMinutesHhmm(hhmm: String, minutes: Int): String? {
    val t = hhmm.trim()
    if (t.length != 4 || t.any { !it.isDigit() }) return null
    val hour = t.substring(0, 2).toInt()
    val minute = t.substring(2, 4).toInt()
    if (hour !in 0..23 || minute !in 0..59) return null
    val total = Math.floorMod(hour * 60 + minute - minutes, 24 * 60)
    return "%02d%02d".format(total / 60, total % 60)
}

/** The backend sends the same time as "HHmm" (flight-level fields) or "HHmmss" (the CDM sequencer's
 *  cdmData tobt/tsat/ttot, seen live once a TOBT is processed) -- normalized to "HHmm", since the
 *  UI works in whole minutes. Blank/unrecognized strings pass through unchanged. */
fun hhmm(raw: String?): String {
    val t = raw.orEmpty().trim()
    return if (t.length == 6 && t.all { it.isDigit() }) t.substring(0, 4) else t
}

/** Placeholder for "this airport supports CDM but the backend isn't tracking the flight yet": a
 *  grey "T-?" chip whose drawer row reads "waiting for flight". */
fun cdmWaitingDisplay() = CdmSlotDisplay(
    waiting = true,
    eobt = null, tobt = null, tsat = null, ctot = null, exot = null, requestedTobt = null,
    reason = null, atfcmStatus = null, depRunway = null, sid = null,
    referenceLabel = null, referenceHhmm = null, asrtSet = false,
    phase = CdmWindowPhase.UNKNOWN, minutes = null
)

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
