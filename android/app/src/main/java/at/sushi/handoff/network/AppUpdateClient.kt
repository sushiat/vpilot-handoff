package at.sushi.handoff.network

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
data class GitHubReleaseInfo(val tag_name: String, val html_url: String)

data class AppUpdateInfo(val version: String, val releaseUrl: String)

private val appUpdateJson = Json { ignoreUnknownKeys = true }
private val appUpdateHttp = OkHttpClient()
private const val ObtainiumPackageName = "dev.imranr.obtainium"

/**
 * Android-side counterpart to the plugin's own auto-updater (issue #34) -- checks this repo's
 * latest GitHub release for a newer Android app version than the one installed. Most users get
 * updates through Obtainium instead (see [isInstalledViaObtainium], which callers should check
 * first and skip this entirely if true, to avoid a redundant/confusing second update prompt); this
 * only matters for someone who sideloaded the APK by hand outside Obtainium's management. Just
 * links out to the GitHub release page rather than downloading/installing in-app -- Android's
 * install-permission model makes an in-app silent install a lot more friction than the plugin's
 * own per-user Windows installer, not worth building for what's likely a small minority of users.
 */
object AppUpdateClient {
    private const val Endpoint = "https://api.github.com/repos/sushiat/vpilot-handoff/releases/latest"

    /** Returns null on any failure, or if already up to date -- never throws. */
    suspend fun checkForUpdate(currentVersion: String): AppUpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(Endpoint)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Handoff-Android")
                .build()
            appUpdateHttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val release = runCatching { appUpdateJson.decodeFromString<GitHubReleaseInfo>(body) }.getOrNull()
                    ?: return@withContext null

                val latestVersion = release.tag_name.removePrefix("v")
                if (!isNewer(latestVersion, currentVersion)) return@withContext null
                AppUpdateInfo(version = latestVersion, releaseUrl = release.html_url)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Simple dotted-numeric version compare (major.minor.patch) -- same shape both
     *  android/app/build.gradle.kts's appVersionName and release tags already use, no need for a
     *  general semver library. */
    fun isNewer(candidate: String, current: String): Boolean {
        val candidateParts = candidate.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(candidateParts.size, currentParts.size)) {
            val c = candidateParts.getOrElse(i) { 0 }
            val b = currentParts.getOrElse(i) { 0 }
            if (c != b) return c > b
        }
        return false
    }
}

/** True if this APK was installed by Obtainium, which already checks/prompts for updates on its
 *  own -- callers should skip their own update notice entirely in that case. `getInstallSourceInfo`
 *  needs API 30+; minSdk here is 26, so this falls back to the deprecated
 *  `getInstallerPackageName` below that. */
fun isInstalledViaObtainium(context: Context): Boolean {
    val pm = context.packageManager
    val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching { pm.getInstallSourceInfo(context.packageName).installingPackageName }.getOrNull()
    } else {
        @Suppress("DEPRECATION")
        runCatching { pm.getInstallerPackageName(context.packageName) }.getOrNull()
    }
    return installer == ObtainiumPackageName
}
