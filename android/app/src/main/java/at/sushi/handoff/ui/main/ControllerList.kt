package at.sushi.handoff.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.sushi.handoff.protocol.Controller
import at.sushi.handoff.protocol.RadioFrequency
import at.sushi.handoff.ui.theme.ControllerBadge
import at.sushi.handoff.ui.theme.LocalHandoffColors
import at.sushi.handoff.ui.theme.controllerBadges
import at.sushi.handoff.ui.theme.controllerRowColors
import at.sushi.handoff.ui.theme.facilitySuffixName

/** Ratings VATSIM defines, per issue #13's "Color & badge logic" table -- display-only, never
 *  used in ranking. */
private val ratingLabels = mapOf(
    1 to "OBS", 2 to "S1", 3 to "S2", 4 to "S3", 5 to "C1", 6 to "C2", 7 to "C3",
    8 to "I1", 9 to "I2", 10 to "I3", 11 to "SUP", 12 to "ADM"
)

private val badgeLabels = mapOf(
    ControllerBadge.TUNED to "TUNED",
    ControllerBadge.CONTACT_ME to "CONTACT ME",
    ControllerBadge.NEXT to "NEXT",
    ControllerBadge.APPROACHING to "APPROACHING",
    ControllerBadge.PINNED to "PINNED",
    ControllerBadge.SELCAL to "SELCAL"
)

@Composable
fun ControllerList(
    controllers: List<Controller>,
    com1Active: Int?,
    com2Active: Int?,
    pinnedCallsign: String?,
    selcalActiveCallsigns: Set<String>,
    onTogglePin: (String) -> Unit,
    onOpenChatWith: (String) -> Unit,
    onTuneCom1Active: (Int) -> Unit,
    onTuneCom2Active: (Int) -> Unit,
    onTuneCom1Standby: (Int) -> Unit,
    onTuneCom2Standby: (Int) -> Unit,
    onDismissSelcal: (String) -> Unit
) {
    val colors = LocalHandoffColors.current
    Column(Modifier.fillMaxWidth()) {
        Text(
            "CONTROLLERS · ${controllers.size}",
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.textMuted,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            // Rendered in exactly the order the server sent it -- never re-sorted client-side.
            items(controllers, key = { it.callsign }) { controller ->
                ControllerRow(
                    controller = controller,
                    com1Active = com1Active,
                    com2Active = com2Active,
                    isPinned = controller.callsign == pinnedCallsign,
                    selcalActive = controller.callsign in selcalActiveCallsigns,
                    onTogglePin = { onTogglePin(controller.callsign) },
                    onOpenChat = { onOpenChatWith(controller.callsign) },
                    onTuneCom1Active = { onTuneCom1Active(controller.frequency) },
                    onTuneCom2Active = { onTuneCom2Active(controller.frequency) },
                    onTuneCom1Standby = { onTuneCom1Standby(controller.frequency) },
                    onTuneCom2Standby = { onTuneCom2Standby(controller.frequency) },
                    onDismissSelcal = { onDismissSelcal(controller.callsign) }
                )
                HorizontalDivider(color = colors.border)
            }
        }
    }
}

@Composable
private fun ControllerRow(
    controller: Controller,
    com1Active: Int?,
    com2Active: Int?,
    isPinned: Boolean,
    selcalActive: Boolean,
    onTogglePin: () -> Unit,
    onOpenChat: () -> Unit,
    onTuneCom1Active: () -> Unit,
    onTuneCom2Active: () -> Unit,
    onTuneCom1Standby: () -> Unit,
    onTuneCom2Standby: () -> Unit,
    onDismissSelcal: () -> Unit
) {
    val colors = LocalHandoffColors.current
    val rowColors = controllerRowColors(controller, com1Active, com2Active, colors)
    val badges = controllerBadges(controller, com1Active, com2Active, isPinned, selcalActive)
    var menuOpen by remember { mutableStateOf(false) }

    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .background(rowColors.background)
                .clickable { menuOpen = true }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(Modifier.widthIn(min = 90.dp)) {
                Text(
                    controller.callsign,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = rowColors.text
                )
                if (badges.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        badges.forEach { badge ->
                            BadgePill(badgeLabels.getValue(badge), rowColors.text)
                        }
                    }
                }
            }

            Text(
                RadioFrequency.format(controller.frequency),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = rowColors.text.copy(alpha = 0.9f),
                modifier = Modifier.weight(1f)
            )

            Column(horizontalAlignment = Alignment.End) {
                val suffixName = facilitySuffixName(controller.callsign)
                if (suffixName != null) {
                    Text(suffixName, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = rowColors.text)
                }
                Text(
                    controller.name ?: controller.cid?.toString() ?: "",
                    fontSize = 10.sp,
                    color = rowColors.text.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            controller.rating?.let { rating ->
                ratingLabels[rating]?.let { label -> RatingBadge(label, rowColors.text) }
            }

            IconButton(onClick = onTogglePin) {
                Icon(Icons.Filled.PushPin, contentDescription = "Pin controller", tint = rowColors.text)
            }
            IconButton(onClick = onOpenChat) {
                Icon(Icons.AutoMirrored.Filled.Message, contentDescription = "Private chat", tint = rowColors.text)
            }
        }

        ControllerTuneMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            callsign = controller.callsign,
            frequencyLabel = RadioFrequency.format(controller.frequency),
            showDismissSelcal = selcalActive,
            onTuneCom1Active = { menuOpen = false; onTuneCom1Active() },
            onTuneCom2Active = { menuOpen = false; onTuneCom2Active() },
            onTuneCom1Standby = { menuOpen = false; onTuneCom1Standby() },
            onTuneCom2Standby = { menuOpen = false; onTuneCom2Standby() },
            onDismissSelcal = { menuOpen = false; onDismissSelcal() }
        )
    }
}

@Composable
private fun BadgePill(label: String, contentColor: androidx.compose.ui.graphics.Color) {
    Box(
        Modifier
            .background(contentColor.copy(alpha = 0.16f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    ) {
        Text(label, fontSize = 8.sp, fontWeight = FontWeight.Bold, color = contentColor)
    }
}

@Composable
private fun RatingBadge(label: String, rowTextColor: androidx.compose.ui.graphics.Color) {
    Box(
        Modifier
            .widthIn(min = 30.dp)
            .background(rowTextColor.copy(alpha = 0.13f), RoundedCornerShape(5.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = rowTextColor)
    }
}

/** The floating popover opened by tapping anywhere on a row except its icon buttons -- a 2x2
 *  COM1/COM2/STBY/STBY tune grid, plus a Dismiss SELCAL button when this row has an active,
 *  undismissed alert. Built on material3's DropdownMenu for free anchoring/outside-tap-dismiss;
 *  its content is fully custom-styled rather than using DropdownMenuItem's default look. */
@Composable
private fun ControllerTuneMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    callsign: String,
    frequencyLabel: String,
    showDismissSelcal: Boolean,
    onTuneCom1Active: () -> Unit,
    onTuneCom2Active: () -> Unit,
    onTuneCom1Standby: () -> Unit,
    onTuneCom2Standby: () -> Unit,
    onDismissSelcal: () -> Unit
) {
    val colors = LocalHandoffColors.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Column(Modifier.widthIn(min = 220.dp).padding(12.dp)) {
            Text(
                "$callsign · $frequencyLabel",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textMuted,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TuneMenuButton("COM1", Modifier.weight(1f), onTuneCom1Active)
                TuneMenuButton("COM2", Modifier.weight(1f), onTuneCom2Active)
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TuneMenuButton("STBY", Modifier.weight(1f), onTuneCom1Standby)
                TuneMenuButton("STBY", Modifier.weight(1f), onTuneCom2Standby)
            }
            if (showDismissSelcal) {
                TuneMenuButton(
                    "Dismiss SELCAL",
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    onDismissSelcal,
                    background = colors.attentionBg,
                    contentColor = colors.attention
                )
            }
        }
    }
}

@Composable
private fun TuneMenuButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    background: androidx.compose.ui.graphics.Color? = null,
    contentColor: androidx.compose.ui.graphics.Color? = null
) {
    val colors = LocalHandoffColors.current
    Box(
        modifier
            .background(background ?: colors.panelAlt, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = contentColor ?: colors.text
        )
    }
}
