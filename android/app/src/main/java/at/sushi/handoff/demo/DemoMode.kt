package at.sushi.handoff.demo

import at.sushi.handoff.ConnectionStatus
import at.sushi.handoff.HandoffState
import at.sushi.handoff.protocol.ChatEntry
import at.sushi.handoff.protocol.ChatMessage
import at.sushi.handoff.protocol.ChatReadStateMessage
import at.sushi.handoff.protocol.ClearPinnedControllerCommand
import at.sushi.handoff.protocol.ClientCommand
import at.sushi.handoff.protocol.Controller
import at.sushi.handoff.protocol.DismissSelcalCommand
import at.sushi.handoff.protocol.MarkChatReadCommand
import at.sushi.handoff.protocol.PinControllerCommand
import at.sushi.handoff.protocol.RadioFrequency
import at.sushi.handoff.protocol.RadioStateMessage
import at.sushi.handoff.protocol.SelcalAlert
import at.sushi.handoff.protocol.SelectCom1TransmitterCommand
import at.sushi.handoff.protocol.SelectCom2TransmitterCommand
import at.sushi.handoff.protocol.SendPrivateMessageCommand
import at.sushi.handoff.protocol.SendRadioMessageCommand
import at.sushi.handoff.protocol.SetCom1ActiveAndStandbyFrequencyCommand
import at.sushi.handoff.protocol.SetCom1FrequencyCommand
import at.sushi.handoff.protocol.SetCom1ReceiveEnabledCommand
import at.sushi.handoff.protocol.SetCom1StandbyFrequencyCommand
import at.sushi.handoff.protocol.SetCom2ActiveAndStandbyFrequencyCommand
import at.sushi.handoff.protocol.SetCom2FrequencyCommand
import at.sushi.handoff.protocol.SetCom2ReceiveEnabledCommand
import at.sushi.handoff.protocol.SetCom2StandbyFrequencyCommand
import at.sushi.handoff.protocol.SetTransponderCodeCommand
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Demo mode (issue #169): fills [HandoffState] with [DemoData]'s made-up sample data so the app
 *  can be looked at without the Windows plugin, and answers interactive taps locally instead of
 *  letting them reach a plugin. One door in ([enable]/[disable]); never persisted --
 *  [HandoffState.demoModeEnabled] always starts false on a fresh launch, see its own doc comment.
 *
 *  [HandoffConnectionService.sendCommand] is the actual hard stop -- a single chokepoint that
 *  checks [HandoffState.demoModeEnabled] and, while it's on, calls [handleCommand] here instead
 *  of ever touching the real WebSocket client, so a command added later can't reach a real plugin
 *  by being forgotten (same reasoning as the referenced iOS client's own single `send` gate). This
 *  object is deliberately plugin/Android-Service-free (no Context, no networking) so it and its
 *  effects on [HandoffState] can be unit-tested directly. */
object DemoMode {

    /** Switches into demo mode. [stopRealConnection] is the caller's own teardown of whatever
     *  real connection/reconnect attempt might be running (kept as a callback rather than a
     *  direct HandoffConnectionService reference, so this object doesn't need an Android Service
     *  or Context) -- run first, before any sample data is written, so a genuinely-connected
     *  plugin's live state is torn down rather than overwritten mid-update. */
    fun enable(stopRealConnection: () -> Unit, scope: CoroutineScope? = null) {
        stopRealConnection()
        // DISCONNECTED is also the truthful connectionStatus while demo mode is on -- there is no
        // real plugin link -- and clears whatever live plugin state might still be sitting in
        // HandoffState (controllers/chat/radioState/flightPlan/...) before the sample data below
        // is written, so a real row can never linger next to a demo one.
        HandoffState.setConnectionStatus(ConnectionStatus.DISCONNECTED)
        HandoffState.setDemoModeEnabled(true)
        populate()
        // Issue #186 -- one station opens a few seconds in, blinking, to show the new-station cue.
        // Skipped without a scope (the unit tests that only look at the static sample data).
        newStationJob?.cancel()
        newStationJob = scope?.let { scriptScope ->
            // Hidden synchronously, so the list never shows either before its scripted moment.
            val station = hideNewStation()
            val selcalAlert = hideSelcal()
            scriptScope.launch {
                if (station != null) launch { runNewStationScript(station) }
                if (selcalAlert != null) launch { runSelcalScript(selcalAlert) }
            }
        }
    }

    /** Issue #186 -- demo timeline: [NewStationCallsign] is hidden at first, appears after
     *  [NewStationAppearDelayMillis] (a var only so tests can shorten it) with `isNewStation` set, and stops blinking
     *  [NewStationFlashMillis] later (the plugin's real window is the same length). The sample
     *  SELCAL alert follows the same hidden-then-raised pattern, see [SelcalDelayMillis]. */
    internal const val NewStationCallsign = "LOWW_TWR"
    internal var NewStationAppearDelayMillis = 10_000L
    internal var NewStationFlashMillis = 5_000L
    private var newStationJob: Job? = null

    /** The sample SELCAL alert (on [SelcalCallsign]) is held back and raised [SelcalDelayMillis] in. */
    internal const val SelcalCallsign = "LOVV_CTR"
    internal var SelcalDelayMillis = 30_000L

    private fun hideSelcal(): SelcalAlert? {
        val chat = HandoffState.chat.value
        val alert = chat.selcalAlerts.firstOrNull { it.from == SelcalCallsign } ?: return null
        HandoffState.update(chat.copy(selcalAlerts = chat.selcalAlerts.filterNot { it === alert }))
        setSelcalFlag(false)
        return alert
    }

    private suspend fun runSelcalScript(alert: SelcalAlert) {
        delay(SelcalDelayMillis)
        val chat = HandoffState.chat.value
        HandoffState.update(chat.copy(selcalAlerts = chat.selcalAlerts + alert.copy(timestamp = Instant.now().toString())))
        setSelcalFlag(true)
        refreshControllerFlags()
    }

    private fun setSelcalFlag(flag: Boolean) {
        val current = HandoffState.controllers.value
        HandoffState.update(current.copy(controllers = current.controllers.map {
            if (it.callsign == SelcalCallsign) it.copy(isSelcalActive = flag) else it
        }))
    }

    private fun hideNewStation(): Controller? {
        val current = HandoffState.controllers.value
        val station = current.controllers.firstOrNull { it.callsign == NewStationCallsign } ?: return null
        HandoffState.update(current.copy(controllers = current.controllers.filterNot { it === station }))
        return station
    }

    private suspend fun runNewStationScript(station: Controller) {
        delay(NewStationAppearDelayMillis)
        addNewStation(station.copy(isNewStation = true))
        delay(NewStationFlashMillis)
        setNewStationFlag(false)
    }

    private fun addNewStation(station: Controller) {
        val current = HandoffState.controllers.value
        HandoffState.update(current.copy(controllers = current.controllers + station))
        refreshControllerFlags()
    }

    private fun setNewStationFlag(flag: Boolean) {
        val current = HandoffState.controllers.value
        HandoffState.update(current.copy(controllers = current.controllers.map {
            if (it.callsign == NewStationCallsign) it.copy(isNewStation = flag) else it
        }))
    }

    /** Leaves demo mode: clears the sample data (so it can never linger once a real connection
     *  starts coming back in) and lets [resumeRealConnection] (the caller's reconnect loop) take
     *  over from there. setConnectionStatus(DISCONNECTED) clears everything in
     *  HandoffState.clearLiveServerState() -- controllers/radioState/flightPlan/... -- except
     *  chat, which that function deliberately leaves alone (a real chat log is meant to survive
     *  an ordinary reconnect); the sample chat/selcal/read-state written by [populate] are not
     *  that kind of history, so they're cleared explicitly here instead. */
    fun disable(resumeRealConnection: () -> Unit) {
        newStationJob?.cancel()
        newStationJob = null
        HandoffState.setDemoModeEnabled(false)
        HandoffState.setConnectionStatus(ConnectionStatus.DISCONNECTED)
        HandoffState.update(ChatMessage(messages = emptyList(), selcalAlerts = emptyList()))
        HandoffState.update(ChatReadStateMessage())
        resumeRealConnection()
    }

    private fun populate(nowEpochMillis: Long = System.currentTimeMillis()) {
        HandoffState.update(DemoData.controllers(nowEpochMillis))
        HandoffState.update(DemoData.chat(nowEpochMillis))
        HandoffState.update(DemoData.chatReadState())
        HandoffState.update(DemoData.radioState())
        HandoffState.update(DemoData.flightPlan())
        HandoffState.update(DemoData.subsystemStatus())
        HandoffState.update(DemoData.nearbyAircraft())
        HandoffState.setCdmSlot(DemoData.cdmSlot(nowEpochMillis))
        refreshControllerFlags()
    }

    /** Stand-in for the plugin's ranking (docs/controller-ranking.md) so a tap in demo mode moves
     *  the list the way a real session would: the flags derive from the current radio/pin/selcal
     *  state, contact-me clears once the station is tuned, and the rows are re-sorted into the
     *  bucket order (current, standby, contact-me, selcal, pinned, rest). Only the parts of the
     *  ranking the sample data exercises -- NEXT is just the next facility in the DEL->GND->TWR->
     *  APP->CTR chain after whatever COM1 is tuned to. */
    private fun refreshControllerFlags() {
        val radio = HandoffState.radioState.value
        val current = HandoffState.controllers.value
        val chain = listOf("DEL", "GND", "TWR", "APP", "CTR")
        fun tier(callsign: String) = chain.indexOf(callsign.substringAfterLast('_'))
        val com1Tier = current.controllers.firstOrNull { it.frequency == radio.com1Frequency }
            ?.let { tier(it.callsign) } ?: -1
        val nextCallsign = if (com1Tier < 0) null else current.controllers
            .filter { tier(it.callsign) > com1Tier }
            .minByOrNull { tier(it.callsign) }?.callsign

        val updated = current.controllers.map {
            val tuned = it.frequency == radio.com1Frequency || it.frequency == radio.com2Frequency
            it.copy(
                isCurrent = tuned,
                isStandbyTuned = it.frequency == radio.com1StandbyFrequency || it.frequency == radio.com2StandbyFrequency,
                isContactMe = it.isContactMe && !tuned,
                contactMeText = if (tuned) null else it.contactMeText,
                isNext = it.callsign == nextCallsign
            )
        }
        val demoOrder = DemoData.callsignOrder
        val sorted = updated.sortedWith(
            compareBy<Controller>(
                { if (it.isCurrent) (if (it.frequency == radio.com1Frequency) 0 else 1) else 2 },
                { if (it.isStandbyTuned) 0 else 1 },
                { if (it.isContactMe) 0 else 1 },
                { if (it.isSelcalActive) 0 else 1 },
                { if (it.isPinned) 0 else 1 },
                { demoOrder.indexOf(it.callsign) }
            )
        )
        HandoffState.update(current.copy(controllers = sorted))
    }

    /** Called only while [HandoffState.demoModeEnabled] is true (see
     *  HandoffConnectionService.sendCommand) -- applies whatever local-only equivalent [command]
     *  has to the sample state so the UI still reacts to a tap, per issue #169's "taps answer
     *  locally" requirement. Anything with no local equivalent (debug snapshots, SimBrief
     *  credentials, refreshFlightPlan, setUpdateInterval, confirmDiversion/dismissDiversion,
     *  authenticate/ping) is simply swallowed here -- never forwarded, which is the entire point
     *  of this being the one place real sends are gated. */
    fun handleCommand(command: ClientCommand) {
        when (command) {
            is SetCom1FrequencyCommand -> updateRadio { it.copy(com1Frequency = compress(command.megahertz)) }
            is SetCom2FrequencyCommand -> updateRadio { it.copy(com2Frequency = compress(command.megahertz)) }
            is SetCom1StandbyFrequencyCommand -> updateRadio { it.copy(com1StandbyFrequency = compress(command.megahertz)) }
            is SetCom2StandbyFrequencyCommand -> updateRadio { it.copy(com2StandbyFrequency = compress(command.megahertz)) }
            is SetCom1ActiveAndStandbyFrequencyCommand -> updateRadio {
                it.copy(com1Frequency = compress(command.megahertz), com1StandbyFrequency = compress(command.standbyMegahertz))
            }
            is SetCom2ActiveAndStandbyFrequencyCommand -> updateRadio {
                it.copy(com2Frequency = compress(command.megahertz), com2StandbyFrequency = compress(command.standbyMegahertz))
            }
            is SetTransponderCodeCommand -> updateRadio { it.copy(transponderCode = command.transponderCode) }
            // Mirrors the real SimConnect *_TRANSMIT_SELECT event this models -- a single
            // selector, not an independent flag, so selecting one always clears the other.
            is SelectCom1TransmitterCommand -> updateRadio { it.copy(com1TransmitEnabled = true, com2TransmitEnabled = false) }
            is SelectCom2TransmitterCommand -> updateRadio { it.copy(com2TransmitEnabled = true, com1TransmitEnabled = false) }
            is SetCom1ReceiveEnabledCommand -> updateRadio { it.copy(com1ReceiveEnabled = command.enabled) }
            is SetCom2ReceiveEnabledCommand -> updateRadio { it.copy(com2ReceiveEnabled = command.enabled) }
            is PinControllerCommand -> setPinned(command.callsign, true)
            is ClearPinnedControllerCommand -> setPinned(command.callsign, false)
            is DismissSelcalCommand -> dismissSelcal(command.callsign)
            is SendRadioMessageCommand -> appendChat(channel = "radio", peer = null, text = command.message)
            is SendPrivateMessageCommand -> appendChat(channel = "private", peer = command.to, text = command.message)
            is MarkChatReadCommand -> markRead(command.tab, command.throughId)
            else -> Unit
        }
    }

    private fun compress(megahertz: Double) = RadioFrequency.fromMegahertz(megahertz)

    private fun updateRadio(transform: (RadioStateMessage) -> RadioStateMessage) {
        HandoffState.update(transform(HandoffState.radioState.value))
        refreshControllerFlags()
    }

    private fun setPinned(callsign: String, pinned: Boolean) {
        val current = HandoffState.controllers.value
        HandoffState.update(
            current.copy(controllers = current.controllers.map {
                if (it.callsign == callsign) it.copy(isPinned = pinned) else it
            })
        )
        refreshControllerFlags()
    }

    private fun dismissSelcal(callsign: String) {
        val controllers = HandoffState.controllers.value
        HandoffState.update(
            controllers.copy(controllers = controllers.controllers.map {
                if (it.callsign == callsign) it.copy(isSelcalActive = false) else it
            })
        )
        refreshControllerFlags()
        val chat = HandoffState.chat.value
        HandoffState.update(chat.copy(selcalAlerts = chat.selcalAlerts.filterNot { it.from == callsign }))
    }

    private fun appendChat(channel: String, peer: String?, text: String) {
        val current = HandoffState.chat.value
        val nextId = (current.messages.maxOfOrNull { it.id ?: 0L } ?: 0L) + 1
        val entry = ChatEntry(
            id = nextId,
            channel = channel,
            direction = "outgoing",
            peer = peer,
            from = HandoffState.flightPlan.value.vatsimCallsign ?: DemoData.OwnCallsign,
            text = text,
            timestamp = Instant.now().toString()
        )
        HandoffState.update(current.copy(messages = current.messages + entry))
    }

    private fun markRead(tab: String, throughId: Long) {
        val current = HandoffState.chatReadState.value
        val advanced = maxOf(current.readThrough[tab] ?: 0L, throughId)
        HandoffState.update(current.copy(readThrough = current.readThrough + (tab to advanced)))
    }
}
