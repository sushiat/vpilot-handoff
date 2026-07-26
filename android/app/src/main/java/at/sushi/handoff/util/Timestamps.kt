package at.sushi.handoff.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

/** Protocol timestamps are ISO 8601 UTC (docs/protocol.md); chat bubbles/SELCAL entries only
 *  ever show local HH:MM, matching the design reference's plain "10:16"-style timestamps --
 *  never the full ISO string. Falls back to the raw value on a malformed timestamp rather than
 *  crashing the row. */
fun formatLocalTime(isoTimestamp: String): String =
    runCatching { timeFormatter.format(Instant.parse(isoTimestamp)) }.getOrDefault(isoTimestamp)
