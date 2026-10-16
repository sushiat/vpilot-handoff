package at.sushi.handoff.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import at.sushi.handoff.HandoffState
import at.sushi.handoff.protocol.ChatMessage
import at.sushi.handoff.protocol.ChatReadStateMessage
import at.sushi.handoff.protocol.ControllersMessage
import at.sushi.handoff.ui.chat.RADIO_TAB
import at.sushi.handoff.ui.chat.readThroughByTab

/** Per-category background alerts: contact-me requests, SELCAL alerts, incoming radio messages,
 *  incoming private messages -- each its own [NotificationChannel] so the user can toggle them
 *  independently in Android's own notification settings. Only fires while
 *  [HandoffState.appVisible] is false (the user doesn't want notifications competing with
 *  anything already visible on screen, fullscreen or split-screen) -- this is a deliberate
 *  product decision, not a missing feature: while the app is on screen at all, the UI itself is
 *  the notification.
 *
 *  Diffs each incoming message against the previous one to find only *new* events (a rising edge
 *  on `requestsContactMe`, a chat/SELCAL entry not seen before) -- the protocol always resends
 *  full state (docs/protocol.md), so without diffing, every resend would re-notify for
 *  already-known state. The very first message of each kind is used only to seed that baseline
 *  (never notifies) -- otherwise connecting while already backgrounded would dump a notification
 *  for every pre-existing contact-me request/unread message instead of just new ones.
 *
 *  Issue #151 -- `setAutoCancel(true)` alone only dismisses a notification when it's tapped on
 *  *that* device, so with two paired devices a message read on one leaves a stale notification on
 *  the other. [onChatReadStateUpdate] cancels a chat notification once the plugin's own read
 *  watermark advances past the message id behind it; [onControllersUpdate] cancels a contact-me
 *  or SELCAL notification once that controller's own `isContactMe`/`isSelcalActive` flag drops --
 *  both already plugin-side, cross-device state, so this works with no protocol change and with
 *  plugins that predate issue #151 too. A chat notification from such an older plugin (no `id` on
 *  the entry) simply never gets tracked for cancellation -- it keeps today's behavior, left
 *  showing until tapped. */
class HandoffNotifier(private val context: Context) {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    private var seededControllers = false
    private var previousRequestingContactMe: Set<String> = emptySet()

    private var seededChat = false
    private var previousChatMessages: Set<at.sushi.handoff.protocol.ChatEntry> = emptySet()
    private var previousSelcalAlerts: Set<at.sushi.handoff.protocol.SelcalAlert> = emptySet()

    // Issue #151 -- tab ("radio" or a private peer's callsign) -> (message id -> notification id)
    // for every chat notification currently showing that could still be cancelled on read.
    private val activeChatNotificationIds = mutableMapOf<String, MutableMap<Long, Int>>()

    // Issue #151 -- callsign -> notification id, for contact-me/SELCAL notifications currently
    // showing, cancelled once that controller's own flag drops.
    private val activeContactMeNotificationIds = mutableMapOf<String, Int>()
    private val activeSelcalNotificationIds = mutableMapOf<String, Int>()

    fun createChannels() {
        listOf(
            NotificationChannel(ChannelContactMe, "Contact me requests", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(ChannelSelcal, "SELCAL alerts", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(ChannelIncomingRadio, "Incoming radio messages", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(ChannelIncomingPrivate, "Incoming private messages", NotificationManager.IMPORTANCE_DEFAULT)
        ).forEach(notificationManager::createNotificationChannel)
    }

    fun onControllersUpdate(message: ControllersMessage) {
        val requestingNow = message.controllers.filter { it.requestsContactMe }.map { it.callsign }.toSet()
        if (!seededControllers) {
            seededControllers = true
            previousRequestingContactMe = requestingNow
            cancelResolvedContactMeAndSelcal(message)
            return
        }
        if (!HandoffState.appVisible.value) {
            message.controllers.forEach { controller ->
                if (controller.requestsContactMe && controller.callsign !in previousRequestingContactMe) {
                    activeContactMeNotificationIds[controller.callsign] =
                        notify(
                            ChannelContactMe, "contact:${controller.callsign}", "Contact me",
                            controller.contactMeText?.takeIf { it.isNotBlank() }?.let { "${controller.callsign}: $it" }
                                ?: "${controller.callsign} is requesting contact"
                        )
                }
            }
        }
        previousRequestingContactMe = requestingNow
        cancelResolvedContactMeAndSelcal(message)
    }

    /** Issue #151 -- cancels any currently-showing contact-me/SELCAL notification whose
     *  controller no longer has that flag set (resolved by tuning, dismissed, or expired) or has
     *  dropped off the controller list entirely. */
    private fun cancelResolvedContactMeAndSelcal(message: ControllersMessage) {
        val byCallsign = message.controllers.associateBy { it.callsign }

        val contactMeIterator = activeContactMeNotificationIds.iterator()
        while (contactMeIterator.hasNext()) {
            val (callsign, notificationId) = contactMeIterator.next()
            if (byCallsign[callsign]?.isContactMe != true) {
                notificationManager.cancel(notificationId)
                contactMeIterator.remove()
            }
        }

        val selcalIterator = activeSelcalNotificationIds.iterator()
        while (selcalIterator.hasNext()) {
            val (callsign, notificationId) = selcalIterator.next()
            if (byCallsign[callsign]?.isSelcalActive != true) {
                notificationManager.cancel(notificationId)
                selcalIterator.remove()
            }
        }
    }

    fun onChatUpdate(message: ChatMessage) {
        val messagesNow = message.messages.toSet()
        val selcalNow = message.selcalAlerts.toSet()
        if (!seededChat) {
            seededChat = true
            previousChatMessages = messagesNow
            previousSelcalAlerts = selcalNow
            return
        }
        if (!HandoffState.appVisible.value) {
            message.messages.forEach { entry ->
                if (entry.direction != "incoming" || entry in previousChatMessages) return@forEach
                // Issue #131 -- text can arrive null if a plugin-side encoding bug blanks it.
                val body = entry.text ?: "⚠ Message received without content"
                val tab = if (entry.channel == "private") entry.peer else RADIO_TAB
                val notificationId = when (entry.channel) {
                    "radio" -> notify(ChannelIncomingRadio, "radio:${entry.timestamp}", "Radio message", body)
                    "private" -> notify(ChannelIncomingPrivate, "private:${entry.peer}:${entry.timestamp}", entry.peer ?: "Private message", body)
                    else -> null
                }
                // Issue #151 -- only trackable for cancellation when the plugin actually assigned
                // this entry an id (a plugin new enough to also send chatReadState).
                if (notificationId != null && tab != null && entry.id != null) {
                    activeChatNotificationIds.getOrPut(tab) { mutableMapOf() }[entry.id] = notificationId
                }
            }
            message.selcalAlerts.forEach { alert ->
                if (alert !in previousSelcalAlerts) {
                    activeSelcalNotificationIds[alert.from] =
                        notify(ChannelSelcal, "selcal:${alert.timestamp}", "SELCAL", "${alert.from} is calling")
                }
            }
        }
        previousChatMessages = messagesNow
        previousSelcalAlerts = selcalNow
    }

    /** Issue #151 -- cancels any chat notification whose message id is now covered by the
     *  advanced watermark for its tab. Ignores a [message] whose sessionId doesn't match the
     *  latest `chat`'s own -- same rule as the client's unread derivation (MainScreen.kt) -- so a
     *  stale read-state from just before a plugin restart can't cancel notifications for a
     *  same-named tab in the new session. */
    fun onChatReadStateUpdate(message: ChatReadStateMessage) {
        if (message.sessionId != HandoffState.chat.value.sessionId) return

        message.readThroughByTab().forEach { (tab, throughId) ->
            val ids = activeChatNotificationIds[tab] ?: return@forEach
            val iterator = ids.iterator()
            while (iterator.hasNext()) {
                val (messageId, notificationId) = iterator.next()
                if (messageId <= throughId) {
                    notificationManager.cancel(notificationId)
                    iterator.remove()
                }
            }
        }
    }

    private fun notify(channelId: String, idKey: String, title: String, text: String): Int {
        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val notificationId = idKey.hashCode()
        notificationManager.notify(notificationId, notification)
        return notificationId
    }

    companion object {
        const val ChannelContactMe = "contact_me"
        const val ChannelSelcal = "selcal_alert"
        const val ChannelIncomingRadio = "incoming_radio"
        const val ChannelIncomingPrivate = "incoming_private"
    }
}
