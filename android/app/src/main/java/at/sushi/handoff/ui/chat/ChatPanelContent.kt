package at.sushi.handoff.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.sushi.handoff.protocol.ChatEntry
import at.sushi.handoff.protocol.ChatMessage
import at.sushi.handoff.protocol.Controller
import at.sushi.handoff.protocol.RadioFrequency
import at.sushi.handoff.ui.theme.HandoffTextField
import at.sushi.handoff.ui.theme.LocalHandoffColors
import at.sushi.handoff.util.formatLocalTime

private const val RADIO_TAB = "radio"

/** Which edge gets the reference's `border-left`/`border-right` (`chatPanelStyle`) -- always
 *  the edge facing the controller list/main app: fullscreen always uses START (chat sits to the
 *  right of the list), split/overlay mode uses whichever edge faces the app based on
 *  [at.sushi.handoff.SplitSide]. */
enum class ChatPanelBorderSide { START, END }

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
    onSend: (String) -> Unit,
    borderSide: ChatPanelBorderSide? = null
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
    // Mirrors MainScreen.kt's mainPanelShape: this panel is only a separate WindowManager window
    // (with its own OS-rounded corners) while hosted as the split-screen overlay (onCollapse !=
    // null is exactly that case -- fullscreen's persistent side panel never needs this). The edge
    // touching the main app pane stays straight (borderSide's line already draws there); the
    // opposite, screen-facing edge rounds to match.
    val outerCornerShape = if (onCollapse != null && borderSide != null) {
        if (borderSide == ChatPanelBorderSide.START) {
            RoundedCornerShape(topStart = 0.dp, topEnd = 16.dp, bottomStart = 0.dp, bottomEnd = 16.dp)
        } else {
            RoundedCornerShape(topStart = 16.dp, topEnd = 0.dp, bottomStart = 16.dp, bottomEnd = 0.dp)
        }
    } else {
        RectangleShape
    }

    Column(
        Modifier
            .fillMaxSize()
            .clip(outerCornerShape)
            // Reference's chatPanelStyle background is t.bg (oklch(97% 0.006 250), a pale
            // blue-gray), which was correct-per-value but reads as a visible off-white on the
            // real tablet display next to the panel-colored top bar/footer -- using colors.panel
            // instead, per the user's explicit call on the real device (see ControllerList.kt's
            // matching change).
            .background(colors.panel)
            .then(
                if (borderSide != null) {
                    Modifier.drawBehind {
                        val x = if (borderSide == ChatPanelBorderSide.START) 0f else size.width
                        drawLine(
                            color = colors.border,
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
                } else {
                    Modifier
                }
            )
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

        // Reference has `border-top:1px solid t.border` on this bar -- missing entirely before.
        // Height is pinned to 64dp (not just the content's natural size) to match the main
        // panel's footer face row exactly (14dp horizontal / 8dp vertical padding around a
        // 48dp-tall IconButton = 64dp total), so this border and the footer's own border-top
        // land at the same height on screen, reading as one continuous line across both panels.
        HorizontalDivider(color = colors.border)
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Both controls share this explicit height instead of sizing from their own
            // padding -- the earlier attempt (just growing HandoffTextField's internal padding)
            // left it nearly filling the full 64dp row with almost no margin top/bottom (looked
            // squeezed), and the button was never matched to it at all. 44dp inside a 64dp row
            // leaves a comfortable 10dp margin above and below both.
            val composeControlHeight = 44.dp
            HandoffTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f).height(composeControlHeight),
                fontSize = 12.5.sp,
                horizontalPadding = 12.dp,
                verticalPadding = 0.dp,
                placeholder = if (activeTab == null) "Transmit on current frequency…" else "Message ${activeTab}…"
            )
            val send = {
                if (draft.isNotBlank()) {
                    onSend(draft)
                    draft = ""
                }
            }
            if (activeTab == null) {
                ComposeButton("TRANSMIT", Icons.Filled.Mic, Modifier.height(composeControlHeight), onClick = send)
            } else {
                ComposeButton("SEND", Icons.AutoMirrored.Filled.Send, Modifier.height(composeControlHeight), onClick = send)
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

/** Matches the reference's message bubble exactly (`activeMessagesView` in the JS): a bordered,
 *  rounded bubble aligned left/right by [ChatEntry.direction], with a small muted mono meta line
 *  above the body -- peer callsign for private messages, tuned frequency for radio, then the
 *  timestamp. This previously rendered as plain unstyled text with no box/border/alignment and no
 *  frequency/timestamp at all -- a real gap, not a style tweak. */
@Composable
private fun MessageRow(entry: ChatEntry) {
    val colors = LocalHandoffColors.current
    val outgoing = entry.direction == "outgoing"
    val metaText = buildString {
        append(entry.peer ?: entry.frequencies?.firstOrNull()?.let { RadioFrequency.format(it) } ?: "")
        if (isNotEmpty()) append(" · ")
        append(formatLocalTime(entry.timestamp))
    }

    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        val bubbleMaxWidth = maxWidth * 0.8f
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start) {
            Column(
                Modifier
                    .widthIn(max = bubbleMaxWidth)
                    // Incoming bubbles are colors.panel (true white in light theme), not
                    // panelAlt -- panelAlt is a near-white oklch tint that reads as visibly grey
                    // on the real tablet display, same lesson as the controller list/chat panel's
                    // own background (see those files' matching notes).
                    .background(if (outgoing) colors.accentBg else colors.panel, RoundedCornerShape(10.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text(
                    metaText,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = colors.textMuted,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
                Text(entry.text, fontSize = 12.5.sp, color = colors.text)
            }
        }
    }
}

@Composable
private fun ComposeButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colors = LocalHandoffColors.current
    Row(
        modifier
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
