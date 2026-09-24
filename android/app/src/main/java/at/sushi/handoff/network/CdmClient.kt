package at.sushi.handoff.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** A-CDM milestone times for one flight, all bare "HHmm" UTC strings (e.g. "1725" for 17:25Z) --
 *  blank ("") rather than null/absent when a milestone hasn't happened yet, per issue #143's live
 *  capture of the real API. */
@Serializable
data class CdmFlightData(
    val tobt: String = "",
    val tsat: String = "",
    val ttot: String = "",
    val ctot: String = "",
    val reason: String = "",
    val confirmed: Boolean = false
)

/** One flight's A-CDM record from api.viffsys.com (issue #143) -- the same undocumented,
 *  reverse-engineered backend behind the EuroScope `rpuig2001/CDM` plugin and vats.im/vdgs.
 *  [cdmSts] is the network-wide compliance status (e.g. "COMPLY", "FLS-NRA") the real site's own
 *  color banner is presumably derived from -- deliberately NOT decoded into a color here (its
 *  exact vocabulary/mapping was never confirmed, see [at.sushi.handoff.util.CdmUrgency]'s doc).
 *  [atot] (actual takeoff time) is the signal that this flight has already departed -- once set,
 *  TOBT/CDM tracking is over for it, see HandoffConnectionService's polling loop. */
@Serializable
data class CdmFlight(
    val callsign: String,
    val departure: String? = null,
    val arrival: String? = null,
    val eobt: String? = null,
    val atot: String? = null,
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
    val tobtReady: Boolean = false
)

private val cdmJson = Json { ignoreUnknownKeys = true }
private val cdmHttp = OkHttpClient()

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
     *  [fetchDepartureAirportFlights] for them. */
    suspend fun fetchCadAirports(): List<CadAirport>? = get("$BaseUrl/etfms/getCadAirports")

    /** `/ifps/depAirport?airport=XXXX` -- every currently-tracked flight departing [icao]; the
     *  caller finds its own callsign's row. The issue's own traffic trace also found a narrower
     *  `/ifps/callsign?callsign=X` endpoint, but its exact response shape (single object vs. a
     *  one-element array) was never confirmed against a live flight -- this sticks to the
     *  endpoint whose shape the issue's live test fully verified. */
    suspend fun fetchDepartureAirportFlights(icao: String): List<CdmFlight>? =
        get("$BaseUrl/ifps/depAirport?airport=$icao")

    private suspend inline fun <reified T> get(url: String): T? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).header("User-Agent", "Handoff-Android").build()
            cdmHttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                runCatching { cdmJson.decodeFromString<T>(body) }.getOrNull()
            }
        } catch (e: Exception) {
            null
        }
    }
}
