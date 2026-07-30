package at.sushi.handoff.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import at.sushi.handoff.ThemeMode
import at.sushi.handoff.protocol.RadioStateMessage
import at.sushi.handoff.ui.theme.HandoffTheme
import com.android.resources.Density
import org.junit.Rule
import org.junit.Test

/** Headless (no device/emulator) renders of [TopBar] at a range of widths and content lengths --
 *  for iterating on issue #29's dynamic sizing without an adb screenshot-and-tap round trip per
 *  change. Not a substitute for a final on-tablet check (Paparazzi renders via LayoutLib, which
 *  can differ slightly from a real device), just a much faster way to eyeball layout changes.
 *
 *  The *device config itself* has to be the narrow/wide one per test, not an inner
 *  Modifier.width() -- Paparazzi's root composition measures with Constraints.fixed(deviceWidthPx,
 *  deviceHeightPx), so a smaller Modifier.width() on a child just gets clamped back up to the
 *  device's forced minWidth instead of actually shrinking anything (confirmed by a throwaway
 *  colored-Box test before writing this one -- three different Box widths against one fixed
 *  device config produced three byte-identical images). [snapshotAtWidth] calls
 *  paparazzi.unsafeUpdateConfig(...) per test instead. XHIGH (2x, 320dpi) is arbitrary but keeps
 *  the dp->px math trivial.
 *
 *  Second gotcha found the same way: PIXEL_C's preset orientation is landscape, and the renderer
 *  silently swaps width/height back to a landscape shape whenever the configured height exceeds
 *  the configured width -- discovered when bumping height to fix a squashed-content render
 *  produced a *different* fixed width (always exactly the height value) regardless of the
 *  intended narrow width. Keeping [heightDp] comfortably at or below the narrowest [widthDp] this
 *  class ever uses avoids it; there's no known escape hatch to just force portrait explicitly
 *  through the fields this file otherwise touches. */
class TopBarScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_C)

    private fun snapshotAtWidth(
        name: String,
        widthDp: Int,
        heightDp: Int = 260,
        radioState: RadioStateMessage,
        com1Callsign: String? = null,
        com2Callsign: String? = null,
        com1StandbyCallsign: String? = null,
        com2StandbyCallsign: String? = null,
        lastMessageLabel: String? = null,
        unreadCount: Int = 0
    ) {
        paparazzi.unsafeUpdateConfig(
            deviceConfig = DeviceConfig.PIXEL_C.copy(
                screenWidth = widthDp * 2,
                screenHeight = heightDp * 2,
                xdpi = 320,
                ydpi = 320,
                density = Density.XHIGH
            )
        )
        paparazzi.snapshot(name = name) {
            HandoffTheme(themeMode = ThemeMode.LIGHT) {
                Box(Modifier.width(widthDp.dp)) {
                    TopBar(
                        radioState = radioState,
                        lastMessageLabel = lastMessageLabel,
                        unreadCount = unreadCount,
                        com1Callsign = com1Callsign,
                        com2Callsign = com2Callsign,
                        com1StandbyCallsign = com1StandbyCallsign,
                        com2StandbyCallsign = com2StandbyCallsign,
                        onSwapCom1 = {},
                        onSwapCom2 = {},
                        onOpenCom1Dialog = {},
                        onOpenCom2Dialog = {},
                        onOpenXpdrDialog = {},
                        onToggleMic = {},
                        onToggleMon = {},
                        onToggleChat = {}
                    )
                }
            }
        }
    }

    private val tunedState = RadioStateMessage(
        com1Frequency = 24850,
        com2Frequency = 24850,
        com1StandbyFrequency = 22800,
        com2StandbyFrequency = 21500,
        modeCEnabled = true,
        transponderCode = 2000,
        com1TransmitEnabled = true,
        com2TransmitEnabled = false,
        com1ReceiveEnabled = true,
        com2ReceiveEnabled = true
    )

    private val placeholderState = RadioStateMessage(modeCEnabled = false)

    @Test
    fun placeholders_wide() = snapshotAtWidth("placeholders_wide", 700, radioState = placeholderState)

    @Test
    fun placeholders_narrow() = snapshotAtWidth("placeholders_narrow", 340, radioState = placeholderState)

    @Test
    fun placeholders_veryNarrow() = snapshotAtWidth("placeholders_veryNarrow", 280, radioState = placeholderState)

    // Measured directly off the tablet: the narrowest split-screen width the user considers
    // actually usable (screenshot pixel width / device density). The real floor to defend, not
    // an arbitrary guess.
    @Test
    fun tuned_measuredMinimum() = snapshotAtWidth("tuned_measuredMinimum", 266, radioState = tunedState)

    @Test
    fun placeholders_measuredMinimum() = snapshotAtWidth("placeholders_measuredMinimum", 266, radioState = placeholderState)

    @Test
    fun tuned_wide() = snapshotAtWidth("tuned_wide", 700, radioState = tunedState)

    @Test
    fun tuned_narrow() = snapshotAtWidth("tuned_narrow", 340, radioState = tunedState)

    @Test
    fun tuned_veryNarrow() = snapshotAtWidth("tuned_veryNarrow", 280, radioState = tunedState)

    @Test
    fun tuned_threshold() = snapshotAtWidth("tuned_threshold", 375, radioState = tunedState)

    @Test
    fun withCallsignAndMessage() = snapshotAtWidth(
        "withCallsignAndMessage",
        480,
        radioState = tunedState,
        com1Callsign = "LDZO_CTR",
        com1StandbyCallsign = "LDZO_CTR",
        com2StandbyCallsign = "EDMM_ALB_CTR",
        lastMessageLabel = "EGLL_TWR",
        unreadCount = 3
    )
}
