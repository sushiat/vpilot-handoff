package at.sushi.handoff.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/** A-CDM milestone times for one flight, all bare "HHmm" UTC strings (e.g. "1725" for 17:25Z) --
 *  blank ("") rather than null/absent when a milestone hasn't happened yet, per issue #143's live
 *  capture of the real API. */
@Serializable
data class CdmFlightData(
    val tobt: String = "",
    val tsat: String = "",
    val ttot: String = "",
    val ctot: String = "",
    val reqTobt: String = "",
    val asrt: String = "",
    /** Runway and SID the controller assigned, as "29/LANUX2C"; blank until they do. */
    val depInfo: String = "",
    val reason: String = "",
    val confirmed: Boolean = false
)

/** One flight's A-CDM record from api.viffsys.com (issue #143) -- the same undocumented,
 *  reverse-engineered backend behind the EuroScope `rpuig2001/CDM` plugin and vats.im/vdgs.
 *  [cdmSts] is the network-wide compliance status (e.g. "COMPLY", "FLS-NRA"); it is decoded but
 *  unused -- the start-up-window colors come from the site's documented -5/+6 rule instead (see
 *  [at.sushi.handoff.util.CdmWindowPhase]).
 *  [atot] (actual takeoff time) is the signal that this flight has already departed -- once set,
 *  TOBT/CDM tracking is over for it, see HandoffConnectionService's polling loop. */
@Serializable
data class CdmFlight(
    val callsign: String,
    val departure: String? = null,
    val arrival: String? = null,
    val eobt: String? = null,
    // Top-level accepted TOBT -- the backend fills this when it accepts a pilot's request, while
    // cdmData.tobt stays blank until the CDM sequencer sets its own (seen live, issue #143).
    val tobt: String? = null,
    // Same top-level-vs-cdmData split as tobt: seen live with a CTOT only on the flight itself.
    val ctot: String? = null,
    val atot: String? = null,
    val atfcmStatus: String? = null,
    val cdmSts: String? = null,
    val cdmData: CdmFlightData? = null
)

/** One airport's CDM-capability flags from `/etfms/getCadAirports` -- [isCdm]/[tobtReady] are
 *  what gate whether Handoff should even attempt to poll per-flight data for it (issue #143's
 *  recommendation), so a non-CDM departure airport doesn't turn into a permanently-empty tile or
 *  needless polling traffic. */
@Serializable
data class CadAirport(
    val icao: String,
    val isCdm: Boolean = false,
    val tobtReady: Boolean = false,
    /** Expected taxi-out time (EXOT) in whole minutes -- verified live as a bare integer (2-20
     *  across all airports) for issue #159. Null/absent when the backend omits it. */
    val taxiTime: Int? = null
)

private val cdmJson = Json { ignoreUnknownKeys = true }
private val cdmHttp = OkHttpClient()

/** Outcome of a single-callsign lookup (issue #158). */
sealed interface CdmLookup {
    data class Found(val flight: CdmFlight) : CdmLookup
    /** Backend answered but doesn't track this callsign (404, or 200 with an empty body). */
    object NoEntry : CdmLookup
    /** Network error, server error or an unexpected body -- unknown, not "absent". */
    object Failed : CdmLookup
}

internal fun classifyCallsignResponse(code: Int, body: String?): CdmLookup = when {
    code == 404 -> CdmLookup.NoEntry
    code !in 200..299 -> CdmLookup.Failed
    body.isNullOrBlank() -> CdmLookup.NoEntry
    else -> decodeCdmBody<CdmFlight>(body)?.let { CdmLookup.Found(it) } ?: CdmLookup.Failed
}

/** Decodes a response body; a missing or blank one decodes to null. */
internal inline fun <reified T> decodeCdmBody(body: String?): T? {
    if (body.isNullOrBlank()) return null
    return runCatching { cdmJson.decodeFromString<T>(body) }.getOrNull()
}

/**
 * Read-only client for the undocumented api.viffsys.com A-CDM backend (issue #143). Confirmed
 * live and unauthenticated for reads (no `x-api-key` required) -- unlike the write side
 * (`/ifps/dpi`, properly key-gated), which this client deliberately never touches. The real TOBT
 * *write* path is a separate, not-yet-built feature: driving vdgs.vatsimspain.es's own
 * `tobtUpdate.php` via a captured pilot session cookie (see the issue), not this API directly.
 *
 * This is a reverse-engineered, undocumented endpoint, not a published API -- nothing guarantees
 * its shape is stable long-term. Every call here degrades to null on any failure (network,
 * non-2xx, unexpected JSON shape) rather than throwing, same convention as [AppUpdateClient].
 */
object CdmClient {
    private const val BaseUrl = "https://api.viffsys.com"

    /** `/etfms/getCadAirports` -- every airport the backend knows about. Used to gate polling to
     *  airports that are actually CDM-covered and TOBT-ready before ever calling
     *  [fetchFlightByCallsign] for them. */
    suspend fun fetchCadAirports(): List<CadAirport>? = get("$BaseUrl/etfms/getCadAirports")

    /** `/ifps/callsign?callsign=X&profile=false` -- the one flight tracked under [callsign]. Returns
     *  a single object (not an array), per production use confirmed in issue #158. An untracked
     *  callsign answers either 404 or HTTP 200 with an empty body; both are [CdmLookup.NoEntry], kept
     *  distinct from [CdmLookup.Failed] so the caller can hold the current display through a blip. */
    suspend fun fetchFlightByCallsign(callsign: String): CdmLookup = withContext(Dispatchers.IO) {
        try {
            val url = "$BaseUrl/ifps/callsign?callsign=${URLEncoder.encode(callsign, "UTF-8")}&profile=false"
            val request = Request.Builder().url(url).header("User-Agent", "Handoff-Android").build()
            cdmHttp.newCall(request).execute().use { response ->
                classifyCallsignResponse(response.code, response.body?.string())
            }
        } catch (e: Exception) {
            CdmLookup.Failed
        }
    }

    private suspend inline fun <reified T> get(url: String): T? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).header("User-Agent", "Handoff-Android").build()
            cdmHttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                decodeCdmBody<T>(response.body?.string())
            }
        } catch (e: Exception) {
            null
        }
    }
}
