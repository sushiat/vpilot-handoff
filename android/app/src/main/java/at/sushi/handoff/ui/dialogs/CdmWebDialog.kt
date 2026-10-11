package at.sushi.handoff.ui.dialogs

import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import at.sushi.handoff.ui.theme.LocalHandoffColors

/** The A-CDM site pilots set their TOBT on -- also what vats.im/vdgs redirects to. */
const val VdgsUrl = "https://vdgs.vatsimspain.es/vdgs/index.php"

/** Issue #143 -- hosts the real VDGS site in an embedded WebView so the pilot can log in with
 *  VATSIM and set/edit their TOBT without leaving the app. WebView (not a Custom Tab) so the
 *  session cookie lives in this app's own cookie jar, which a native TOBT entry could later reuse;
 *  the WebView keeps cookies between openings, so the VATSIM login is only needed once per
 *  session lifetime. The "Browser" button hands the same page to the real browser as a fallback
 *  (e.g. if password-manager autofill or the login's bot check misbehaves in a WebView). */
@Composable
fun CdmWebDialog(onDismiss: () -> Unit) {
    val colors = LocalHandoffColors.current
    val context = LocalContext.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Column(Modifier.fillMaxSize().background(colors.panel).imePadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("TOBT / VDGS", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.text)
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Browser",
                        fontSize = 13.sp,
                        color = colors.text.copy(alpha = 0.7f),
                        modifier = Modifier.clickable {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(VdgsUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    )
                    Text("✕", fontSize = 18.sp, color = colors.text.copy(alpha = 0.7f), modifier = Modifier.clickable(onClick = onDismiss))
                }
            }
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = WebViewClient()
                        loadUrl(VdgsUrl)
                    }
                },
                onRelease = { it.destroy() }
            )
        }
    }
}
