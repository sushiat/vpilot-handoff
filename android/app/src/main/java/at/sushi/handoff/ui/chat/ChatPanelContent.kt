package at.sushi.handoff.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.sushi.handoff.protocol.ChatEntry
import at.sushi.handoff.protocol.ChatMessage
import at.sushi.handoff.protocol.Controller
import at.sushi.handoff.ui.theme.HandoffTextField
import at.sushi.handoff.ui.theme.LocalHandoffColors

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

    // The split-screen overlay is its own separate WindowManager window (see
    // ChatOverlayWindow.kt), which doesn't get system-bar insets applied the way the main
    // Activity's window does -- without this, the header's icons/close button rendered under
    // the status bar. Safe to apply unconditionally: in fullscreen mode this content is nested
    // inside MainScreen's own root Row, which already consumed these same insets once, so this
    // second application sees (and adds) zero extra padding there.
    // .imePadding() reads the live keyboard inset (Modifier.imePadding tracks WindowInsets.ime,
    // which reflects the IME's actual current height including any accessory bars it draws --
    // e.g. a suggestion strip or the cut/copy/paste toolbar) and pushes this content up by
    // exactly that much while the keyboard is shown, animating smoothly as it changes. This only
    // works correctly because the overlay window is now configured with
    // SOFT_INPUT_ADJUST_RESIZE (see ChatOverlayWindow.kt) -- without that, the window doesn't
    // report a live/accurate IME inset to begin with, regardless of this modifier.
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.panel)
            .windowInsetsPadding(WindowInsets.systemBars)
            .imePadding()
    ) {
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
                    item { SelcalEntry(latestAlert, selcalActive) }
                }
            }
            items(entries.reversed()) { entry -> MessageRow(entry) }
        }

        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HandoffTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                fontSize = 12.5.sp,
                horizontalPadding = 12.dp,
                placeholder = if (activeTab == null) "Transmit on current frequency…" else "Message ${activeTab}…"
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

/** Matches the reference's `tb.btnStyle` exactly: selected = `t.panelAlt` background + normal
 *  text; unselected = transparent + muted text -- no accent/blue color at all (that was a real
 *  divergence: this previously used the theme's blue `accent`, which read as a Material-style
 *  selected pill rather than a plain tab). Top-rounded corners only, like a tab under a strip. */
@Composable
private fun ChatTab(label: String, selected: Boolean, unread: Int, closable: Boolean, onSelect: () -> Unit, onClose: () -> Unit) {
    val colors = LocalHandoffColors.current
    Row(
        Modifier
            .background(
                if (selected) colors.panelAlt else Color.Transparent,
                RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
            )
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (selected) colors.text else colors.textMuted)
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
            .background(colors.accent, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}
