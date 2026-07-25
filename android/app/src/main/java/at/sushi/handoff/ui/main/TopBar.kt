package at.sushi.handoff.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import at.sushi.handoff.protocol.RadioFrequency
import at.sushi.handoff.protocol.RadioStateMessage
import at.sushi.handoff.ui.theme.LocalHandoffColors

/** The main screen's top bar: a 3x2 grid (COM1/COM2/XPDR active row, COM1/COM2/MSG standby row)
 *  per issue #13 screen 1. Tapping a COM active button swaps it with standby immediately (no
 *  dialog); tapping a standby button opens that COM's tuning dialog. */
@Composable
fun TopBar(
    radioState: RadioStateMessage,
    lastMessageLabel: String?,
    unreadCount: Int,
    onSwapCom1: () -> Unit,
    onSwapCom2: () -> Unit,
    onOpenCom1Dialog: () -> Unit,
    onOpenCom2Dialog: () -> Unit,
    onOpenXpdrDialog: () -> Unit,
    onToggleChat: () -> Unit
) {
    val colors = LocalHandoffColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FrequencyButton(
                Modifier.weight(1f),
                label = "COM1",
                value = radioState.com1Frequency?.let(RadioFrequency::format) ?: "---.---",
                large = true,
                onClick = onSwapCom1
            )
            FrequencyButton(
                Modifier.weight(1f),
                label = "COM2",
                value = radioState.com2Frequency?.let(RadioFrequency::format) ?: "---.---",
                large = true,
                onClick = onSwapCom2
            )
            XpdrButton(Modifier.weight(1f), radioState, onClick = onOpenXpdrDialog)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FrequencyButton(
                Modifier.weight(1f),
                label = "COM1",
                value = radioState.com1StandbyFrequency?.let(RadioFrequency::format) ?: "---.---",
                large = false,
                onClick = onOpenCom1Dialog
            )
            FrequencyButton(
                Modifier.weight(1f),
                label = "COM2",
                value = radioState.com2StandbyFrequency?.let(RadioFrequency::format) ?: "---.---",
                large = false,
                onClick = onOpenCom2Dialog
            )
            MsgButton(Modifier.weight(1f), lastMessageLabel, unreadCount, onClick = onToggleChat)
        }
    }
}

@Composable
private fun RowScope.FrequencyButton(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    large: Boolean,
    onClick: () -> Unit
) {
    val colors = LocalHandoffColors.current
    Column(
        modifier
            .background(colors.panelAlt, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.06f.em,
            color = colors.textMuted.copy(alpha = if (large) 0.7f else 0.75f)
        )
        Text(
            value,
            fontSize = if (large) 20.sp else 15.sp,
            fontWeight = if (large) FontWeight.Bold else FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            color = colors.text.copy(alpha = if (large) 1f else 0.75f)
        )
    }
}

@Composable
private fun RowScope.XpdrButton(modifier: Modifier = Modifier, radioState: RadioStateMessage, onClick: () -> Unit) {
    val colors = LocalHandoffColors.current
    Column(
        modifier
            .background(colors.panelAlt, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "XPDR",
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.06f.em,
                color = colors.textMuted.copy(alpha = 0.7f)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(
                radioState.transponderCode?.toString()?.padStart(4, '0') ?: "----",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = colors.text
            )
            ModeCBadge(radioState.modeCEnabled)
        }
    }
}

@Composable
private fun ModeCBadge(modeCEnabled: Boolean) {
    val colors = LocalHandoffColors.current
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .widthIn(min = 26.dp)
            .size(width = 26.dp, height = 24.dp)
            .then(
                if (modeCEnabled) {
                    Modifier.background(colors.accent, shape)
                } else {
                    Modifier.border(1.dp, colors.border, shape)
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "C",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (modeCEnabled) androidx.compose.ui.graphics.Color.White else colors.textMuted
        )
    }
}

@Composable
private fun RowScope.MsgButton(modifier: Modifier = Modifier, lastMessageLabel: String?, unreadCount: Int, onClick: () -> Unit) {
    val colors = LocalHandoffColors.current
    Column(
        modifier
            .background(colors.panelAlt, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            "MSG",
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.06f.em,
            color = colors.textMuted.copy(alpha = 0.7f)
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(
                lastMessageLabel ?: "--",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (unreadCount > 0) {
                Box(
                    Modifier
                        .widthIn(min = 26.dp)
                        .size(width = 26.dp, height = 24.dp)
                        .background(colors.attention, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        unreadCount.toString(),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = androidx.compose.ui.graphics.Color.White
                    )
                }
            }
        }
    }
}
