package at.sushi.handoff.util

/** Issue #216 -- decides whether saving the settings dialog needs to touch SimBrief at all. The
 *  plugin persists the credentials itself, so resending unchanged ones only costs a plan fetch,
 *  which can replace the plan the pilot is flying with one they happened to have prepared since. */
object SimbriefCredentials {
    /** True when the user ID or username differs from what was stored, ignoring surrounding spaces
     *  and treating "no value" (null) the same as an empty field. */
    fun changed(oldUserId: String?, oldUsername: String?, newUserId: String?, newUsername: String?): Boolean =
        oldUserId.orEmpty().trim() != newUserId.orEmpty().trim() ||
            oldUsername.orEmpty().trim() != newUsername.orEmpty().trim()
}
