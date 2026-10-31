package at.sushi.handoff.demo

import at.sushi.handoff.ConnectionStatus
import at.sushi.handoff.HandoffState
import at.sushi.handoff.protocol.ControllersMessage
import at.sushi.handoff.protocol.PinControllerCommand
import at.sushi.handoff.protocol.PingCommand
import at.sushi.handoff.protocol.RefreshFlightPlanCommand
import at.sushi.handoff.protocol.SendRadioMessageCommand
import at.sushi.handoff.protocol.SetCom1FrequencyCommand
import at.sushi.handoff.protocol.SetTransponderCodeCommand
import at.sushi.handoff.protocol.SquawkIdentCommand
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Issue #169's required unit-test coverage: the outbound guard, enable/disable clearing live
 *  state, and demo mode not persisting. HandoffState is a process-wide singleton, so every test
 *  resets it back to a known-disconnected, demo-off state first/last rather than relying on
 *  ordering between test methods. */
class DemoModeTest {

    @BeforeTest
    @AfterTest
    fun resetState() {
        HandoffState.setDemoModeEnabled(false)
        HandoffState.setConnectionStatus(ConnectionStatus.DISCONNECTED)
    }

    @Test
    fun enable_clearsAnyLiveStateBeforeWritingSampleData() {
        // Simulates state left over from a real (now-dropped) plugin connection.
        HandoffState.update(ControllersMessage(controllers = listOf(
            at.sushi.handoff.protocol.Controller(callsign = "REAL_TWR", frequency = 1, latitude = 0.0, longitude = 0.0)
        )))

        var stopped = false
        DemoMode.enable(stopRealConnection = { stopped = true })

        assertTrue(stopped, "enable() must tear down the real connection before writing sample data")
        assertTrue(HandoffState.demoModeEnabled.value)
        assertEquals(ConnectionStatus.DISCONNECTED, HandoffState.connectionStatus.value)
        // The real controller is gone -- replaced by (fictional) sample data, never left sitting
        // alongside it.
        assertTrue(HandoffState.controllers.value.controllers.none { it.callsign == "REAL_TWR" })
        assertTrue(HandoffState.controllers.value.controllers.isNotEmpty())
        assertTrue(HandoffState.chat.value.messages.isNotEmpty())
    }

    @Test
    fun disable_clearsSampleDataAndResumesRealConnection() {
        DemoMode.enable(stopRealConnection = {})
        assertTrue(HandoffState.controllers.value.controllers.isNotEmpty())

        var resumed = false
        DemoMode.disable(resumeRealConnection = { resumed = true })

        assertTrue(resumed, "disable() must hand back to the real reconnect loop")
        assertFalse(HandoffState.demoModeEnabled.value)
        // Sample rows must never linger once demo mode is off.
        assertTrue(HandoffState.controllers.value.controllers.isEmpty())
        assertTrue(HandoffState.chat.value.messages.isEmpty())
    }

    @Test
    fun demoModeEnabled_neverPersisted_startsOffEveryTime() {
        // Issue #169: every launch starts in the real mode. There is no SharedPreferences-backed
        // loader for this flag anywhere (unlike theme/channel-spacing/etc, which
        // HandoffConnectionService.loadPersistedUiSettings reads back on every launch) -- the
        // only way this is ever true is an explicit setDemoModeEnabled(true)/DemoMode.enable()
        // call within the current process, which is what this asserts here and is exactly what a
        // fresh process (where no such call has happened yet) would see.
        assertFalse(HandoffState.demoModeEnabled.value)
        DemoMode.enable(stopRealConnection = {})
        assertTrue(HandoffState.demoModeEnabled.value)
        DemoMode.disable(resumeRealConnection = {})
        assertFalse(HandoffState.demoModeEnabled.value)
    }

    @Test
    fun handleCommand_pinController_pinsOnlyTheMatchingSampleRow() {
        DemoMode.enable(stopRealConnection = {})
        val before = HandoffState.controllers.value.controllers
        // The sample data already has one controller pinned (flag-variety coverage) -- pick a
        // different, not-yet-pinned one so this actually exercises a change.
        val target = before.first { !it.isPinned }.callsign
        val alreadyPinned = before.filter { it.isPinned }.map { it.callsign }.toSet()

        DemoMode.handleCommand(PinControllerCommand(callsign = target))

        val updated = HandoffState.controllers.value.controllers
        assertTrue(updated.first { it.callsign == target }.isPinned)
        // Every other row's pinned state is exactly what it was before -- no other callsign's
        // flag moved.
        val nowPinned = updated.filter { it.isPinned }.map { it.callsign }.toSet()
        assertEquals(alreadyPinned + target, nowPinned)
    }

    @Test
    fun handleCommand_tuneCom1_updatesRadioStateLocally() {
        DemoMode.enable(stopRealConnection = {})

        DemoMode.handleCommand(SetCom1FrequencyCommand(megahertz = 118.1))

        assertEquals(18_100, HandoffState.radioState.value.com1Frequency)
    }

    @Test
    fun handleCommand_sendRadioMessage_appendsOutgoingEntryWithoutSendingAnything() {
        DemoMode.enable(stopRealConnection = {})
        val before = HandoffState.chat.value.messages.size

        DemoMode.handleCommand(SendRadioMessageCommand(message = "Handoff 123 ready for pushback"))

        val messages = HandoffState.chat.value.messages
        assertEquals(before + 1, messages.size)
        val appended = messages.last()
        assertEquals("outgoing", appended.direction)
        assertEquals("Handoff 123 ready for pushback", appended.text)
    }

    @Test
    fun handleCommand_setTransponderCode_updatesRadioStateLocally() {
        DemoMode.enable(stopRealConnection = {})

        DemoMode.handleCommand(SetTransponderCodeCommand(transponderCode = 7000))

        assertEquals(7000, HandoffState.radioState.value.transponderCode)
    }

    @Test
    fun handleCommand_squawkIdent_postsAFinishedAckLocally() {
        DemoMode.enable(stopRealConnection = {})

        DemoMode.handleCommand(SquawkIdentCommand())

        val ack = HandoffState.operationProgress.value.values
            .map { it.message }
            .single { it.operationId.startsWith("squawkIdent-") }
        assertEquals("Ident sent.", ack.status)
        assertTrue(ack.finished)
        assertTrue(ack.success)
    }

    @Test
    fun handleCommand_withNoLocalEquivalent_isSwallowedWithoutThrowing() {
        // The hard-stop's whole point (issue #169): a command with nothing meaningful to answer
        // locally -- refreshFlightPlan here -- must never throw and must never leave any trace,
        // since HandoffConnectionService.sendCommand's only other branch is the real
        // client.send(), which this call must not reach either way.
        DemoMode.enable(stopRealConnection = {})
        val before = HandoffState.flightPlan.value

        DemoMode.handleCommand(RefreshFlightPlanCommand())
        DemoMode.handleCommand(PingCommand(clientTimestamp = 0L))

        assertEquals(before, HandoffState.flightPlan.value)
    }

    @Test
    fun newStationScript_hidesThenShowsBlinkingThenStops() {
        val oldAppear = DemoMode.NewStationAppearDelayMillis
        val oldFlash = DemoMode.NewStationFlashMillis
        // Issue #207 -- the timings are staggered and the blink window is wide: with every delay at
        // 300 ms both scripts fired together, and the 300 ms blink was only caught by 10 ms polling,
        // which a stalled CI runner could miss.
        DemoMode.NewStationAppearDelayMillis = 600
        DemoMode.NewStationFlashMillis = 1_000
        val oldSelcal = DemoMode.SelcalDelayMillis
        DemoMode.SelcalDelayMillis = 300
        // One thread for both scripts: each of their controller updates reads the list, copies it and
        // writes it back, so on Dispatchers.Default two scripts could interleave and one would
        // overwrite the other's change (the station or the SELCAL flag silently lost).
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + executor.asCoroutineDispatcher()
        )
        try {
            DemoMode.enable(stopRealConnection = {}, scope = scope)
            fun station() = HandoffState.controllers.value.controllers.firstOrNull { it.callsign == DemoMode.NewStationCallsign }
            fun waitFor(condition: () -> Boolean) {
                val deadline = System.currentTimeMillis() + 5_000
                while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
                assertTrue(condition())
            }

            fun selcal() = HandoffState.controllers.value.controllers.first { it.callsign == DemoMode.SelcalCallsign }.isSelcalActive
            assertEquals(null, station(), "hidden until the scripted opening")
            assertFalse(selcal(), "SELCAL held back until its scripted moment")
            assertTrue(HandoffState.chat.value.selcalAlerts.isEmpty())
            waitFor { selcal() }
            assertEquals(1, HandoffState.chat.value.selcalAlerts.size)
            waitFor { station()?.isNewStation == true }
            waitFor { station() != null && station()?.isNewStation == false }
        } finally {
            scope.cancel()
            executor.shutdownNow()
            DemoMode.NewStationAppearDelayMillis = oldAppear
            DemoMode.NewStationFlashMillis = oldFlash
            DemoMode.SelcalDelayMillis = oldSelcal
        }
    }
}
