package at.sushi.handoff.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import at.sushi.handoff.HandoffState
import at.sushi.handoff.protocol.ChatMessage
import at.sushi.handoff.protocol.ControllersMessage

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
 *  for every pre-existing contact-me request/unread message instead of just new ones. */
class HandoffNotifier(private val context: Context) {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    private var seededControllers = false
    private var previousRequestingContactMe: Set<String> = emptySet()

    private var seededChat = false
    private var previousChatMessages: Set<at.sushi.handoff.protocol.ChatEntry> = emptySet()
    private var previousSelcalAlerts: Set<at.sushi.handoff.protocol.SelcalAlert> = emptySet()

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
            return
        }
        if (!HandoffState.appVisible.value) {
            message.controllers.forEach { controller ->
                if (controller.requestsContactMe && controller.callsign !in previousRequestingContactMe) {
                    notify(ChannelContactMe, "contact:${controller.callsign}", "Contact me", "${controller.callsign} is requesting contact")
                }
            }
        }
        previousRequestingContactMe = requestingNow
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
                when (entry.channel) {
                    "radio" -> notify(ChannelIncomingRadio, "radio:${entry.timestamp}", "Radio message", entry.text)
                    "private" -> notify(ChannelIncomingPrivate, "private:${entry.peer}:${entry.timestamp}", entry.peer ?: "Private message", entry.text)
                }
            }
            message.selcalAlerts.forEach { alert ->
                if (alert !in previousSelcalAlerts) {
                    notify(ChannelSelcal, "selcal:${alert.timestamp}", "SELCAL", "${alert.from} is calling")
                }
            }
        }
        previousChatMessages = messagesNow
        previousSelcalAlerts = selcalNow
    }

    private fun notify(channelId: String, idKey: String, title: String, text: String) {
        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        notificationManager.notify(idKey.hashCode(), notification)
    }

    companion object {
        const val ChannelContactMe = "contact_me"
        const val ChannelSelcal = "selcal_alert"
        const val ChannelIncomingRadio = "incoming_radio"
        const val ChannelIncomingPrivate = "incoming_private"
    }
}
