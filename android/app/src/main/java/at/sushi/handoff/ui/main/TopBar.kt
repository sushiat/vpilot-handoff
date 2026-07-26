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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import at.sushi.handoff.R
import at.sushi.handoff.protocol.RadioFrequency
import at.sushi.handoff.protocol.RadioStateMessage
import at.sushi.handoff.ui.theme.LocalHandoffColors

/** The main screen's top bar: the app's own header ("Handover" wordmark + "by sushi.at" +
 *  version, per issue #13's Assets section) above a 3x2 grid (COM1/COM2/XPDR active row,
 *  COM1/COM2/MSG standby row) per screen 1. Tapping a COM active button swaps it with standby
 *  immediately (no dialog); tapping a standby button opens that COM's tuning dialog. */
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
            .background(colors.panel)
    ) {
        AppHeaderRow()
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
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
        androidx.compose.material3.HorizontalDivider(color = colors.border)
    }
}

@Composable
private fun AppHeaderRow() {
    val colors = LocalHandoffColors.current
    val context = LocalContext.current
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 14.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            painterResource(R.drawable.ic_handover_mark),
            contentDescription = null,
            tint = colors.textMuted,
            modifier = Modifier.size(18.dp)
        )
        Text("Handover", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.textMuted)
        Text("by sushi.at", fontSize = 10.sp, color = colors.textMuted.copy(alpha = 0.85f))
        Box(Modifier.weight(1f))
        Text(
            "v$versionName",
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            color = colors.textMuted.copy(alpha = 0.6f)
        )
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
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier
            .background(colors.panelAlt, shape)
            .border(1.dp, colors.border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.06f.em,
            color = colors.textMuted.copy(alpha = if (large) 0.7f else 0.6f)
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
    val shape = RoundedCornerShape(10.dp)
    // Reference structure is a single outer row (vertically centered across the *whole* button
    // height): a label+value column, then the badge -- not two stacked rows with the badge only
    // centered against the value line, which is what left it looking vertically off.
    Row(
        modifier
            .background(colors.panelAlt, shape)
            .border(1.dp, colors.border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "XPDR",
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.06f.em,
                color = colors.textMuted.copy(alpha = 0.7f)
            )
            Text(
                radioState.transponderCode?.toString()?.padStart(4, '0') ?: "----",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = colors.text
            )
        }
        ModeCBadge(radioState.modeCEnabled)
    }
}

/** Matches the reference's `modeCBadgeStyle` exactly: min-width 26dp, height 24dp, radius 6dp,
 *  solid fill (accent when on, otherwise a solid `t.border` fill -- not an outline), white text
 *  always, 14sp/700. */
@Composable
private fun ModeCBadge(modeCEnabled: Boolean) {
    val colors = LocalHandoffColors.current
    Box(
        Modifier
            .widthIn(min = 26.dp)
            .size(width = 26.dp, height = 24.dp)
            .background(if (modeCEnabled) colors.accent else colors.border, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "C",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = androidx.compose.ui.graphics.Color.White
        )
    }
}

@Composable
private fun RowScope.MsgButton(modifier: Modifier = Modifier, lastMessageLabel: String?, unreadCount: Int, onClick: () -> Unit) {
    val colors = LocalHandoffColors.current
    val shape = RoundedCornerShape(10.dp)
    // Same fix as XpdrButton: one outer row (vertically centered across the whole button), a
    // label+value column, then the badge -- not the badge centered only against the value line.
    Row(
        modifier
            .background(colors.panelAlt, shape)
            .border(1.dp, colors.border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "MSG",
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.06f.em,
                color = colors.textMuted.copy(alpha = 0.7f)
            )
            Text(
                lastMessageLabel ?: "--",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
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
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = androidx.compose.ui.graphics.Color.White
                )
            }
        }
    }
}
