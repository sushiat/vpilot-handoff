package at.sushi.handoff.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.sushi.handoff.protocol.ChatEntry
import at.sushi.handoff.protocol.ChatMessage
import at.sushi.handoff.protocol.Controller
import at.sushi.handoff.ui.theme.LocalHandoffColors
import at.sushi.handoff.ui.theme.facilityColor

private const val RADIO_TAB = "radio"

/** The chat panel's actual content -- tab strip, bottom-anchored message list, compose bar. Used
 *  both as a fullscreen side panel and inside the split-screen overlay window (see
 *  ChatOverlayWindow.kt); this composable itself doesn't know which host it's in. Per issue #13
 *  screen 5. */
@Composable
fun ChatPanelContent(
    chat: ChatMessage,
    controllers: List<Controller>,
    openTabs: List<String>,
    activeTab: String?,
    unreadByTab: Map<String, Int>,
    selcalActive: Boolean,
    onSelectTab: (String?) -> Unit,
    onCloseTab: (String) -> Unit,
    onOpenNearbyDialog: () -> Unit,
    onCollapse: (() -> Unit)?,
    onSend: (String) -> Unit
) {
    val colors = LocalHandoffColors.current
    var draft by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(colors.panel)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ChatTab(
                    label = "RADIO",
                    selected = activeTab == null,
                    unread = unreadByTab[RADIO_TAB] ?: 0,
                    closable = false,
                    onSelect = { onSelectTab(null) },
                    onClose = {}
                )
                openTabs.forEach { peer ->
                    ChatTab(
                        label = peer,
                        selected = activeTab == peer,
                        unread = unreadByTab[peer] ?: 0,
                        closable = true,
                        onSelect = { onSelectTab(peer) },
                        onClose = { onCloseTab(peer) }
                    )
                }
            }
            IconButton(onClick = onOpenNearbyDialog) {
                Icon(Icons.Filled.Flight, contentDescription = "Start chat with nearby aircraft", tint = colors.textMuted)
            }
            if (onCollapse != null) {
                IconButton(onClick = onCollapse) {
                    Icon(Icons.Filled.Close, contentDescription = "Collapse chat", tint = colors.textMuted)
                }
            }
        }

        val entries = if (activeTab == null) {
            chat.messages.filter { it.channel == "radio" || it.channel == "broadcast" }
        } else {
            chat.messages.filter { it.channel == "private" && it.peer == activeTab }
        }.sortedBy { it.timestamp }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            reverseLayout = true
        ) {
            if (activeTab == null) {
                val latestAlert = chat.selcalAlerts.maxByOrNull { it.timestamp }
                if (latestAlert != null) {
                    item {
                        val callerColor = controllers.firstOrNull { it.callsign == latestAlert.from }
                            ?.let(::facilityColor) ?: colors.accent
                        SelcalEntry(latestAlert, callerColor, selcalActive)
                    }
                }
            }
            items(entries.reversed()) { entry -> MessageRow(entry) }
        }

        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text(if (activeTab == null) "Transmit…" else "Message…") }
            )
            val send = {
                if (draft.isNotBlank()) {
                    onSend(draft)
                    draft = ""
                }
            }
            if (activeTab == null) {
                ComposeButton("TRANSMIT", Icons.Filled.Mic, onClick = send)
            } else {
                ComposeButton("SEND", Icons.AutoMirrored.Filled.Send, onClick = send)
            }
        }
    }
}

@Composable
private fun ChatTab(label: String, selected: Boolean, unread: Int, closable: Boolean, onSelect: () -> Unit, onClose: () -> Unit) {
    val colors = LocalHandoffColors.current
    Row(
        Modifier
            .background(if (selected) colors.accentBg else colors.panelAlt, RoundedCornerShape(8.dp))
            .clickable(onClick = onSelect)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (selected) colors.accent else colors.text)
        if (unread > 0) {
            Box(Modifier.size(6.dp).background(colors.attention, CircleShape))
        }
        if (closable) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Close $label",
                tint = colors.textMuted,
                modifier = Modifier.size(14.dp).clickable(onClick = onClose)
            )
        }
    }
}

@Composable
private fun MessageRow(entry: ChatEntry) {
    val colors = LocalHandoffColors.current
    val label = when {
        entry.channel == "private" -> "${entry.direction} · ${entry.peer}"
        else -> entry.direction
    }
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, fontSize = 10.sp, color = colors.textMuted)
        Text(entry.text, fontSize = 13.sp, color = colors.text)
    }
}

@Composable
private fun ComposeButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    val colors = LocalHandoffColors.current
    Row(
        Modifier
            .background(colors.accent, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(16.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color.White)
    }
}
