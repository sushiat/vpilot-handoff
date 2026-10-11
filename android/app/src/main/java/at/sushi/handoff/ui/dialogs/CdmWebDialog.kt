package at.sushi.handoff.ui.dialogs

import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import at.sushi.handoff.ui.theme.LocalHandoffColors

/** The A-CDM site pilots set their TOBT on -- also what vats.im/vdgs redirects to. */
const val VdgsUrl = "https://vdgs.vatsimspain.es/vdgs/index.php"

// Hosts the dialog may navigate to (the site itself, VATSIM's login, and Cloudflare's bot check
// that fronts both); any other top-level navigation is blocked, so a stray link can't take the
// JavaScript-enabled WebView somewhere else. Subdomains are included.
private val AllowedHostDomains = listOf("vatsimspain.es", "vatsim.net", "cloudflare.com")

/** True for an https URL on (a subdomain of) one of [AllowedHostDomains]. */
internal fun isAllowedVdgsNavigation(url: String): Boolean {
    val uri = try { java.net.URI(url) } catch (e: java.net.URISyntaxException) { return false }
    if (!"https".equals(uri.scheme, ignoreCase = true)) return false
    val host = uri.host?.lowercase() ?: return false
    return AllowedHostDomains.any { host == it || host.endsWith(".$it") }
}

/** Issue #143 -- hosts the real VDGS site in an embedded WebView so the pilot can log in with
 *  VATSIM and set/edit their TOBT without leaving the app. WebView (not a Custom Tab) so the
 *  session cookie lives in this app's own cookie jar, which a native TOBT entry could later reuse;
 *  the WebView keeps cookies between openings, so the VATSIM login is only needed once per
 *  session lifetime. */
@Composable
fun CdmWebDialog(onDismiss: () -> Unit) {
    val colors = LocalHandoffColors.current
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
                Text("✕", fontSize = 18.sp, color = colors.text.copy(alpha = 0.7f), modifier = Modifier.clickable(onClick = onDismiss))
            }
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        // Explicit match-parent params: a WebView created without any is laid out as
                        // wrap-content, and Chromium then resolves every vh unit to 0, so pages built
                        // on min-h-screen/h-screen (the VATSIM login) collapse to zero height and
                        // clip all their content.
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        // Same slate the dark login page uses, so there's no white flash while loading.
                        setBackgroundColor(android.graphics.Color.parseColor("#0F172A"))
                        // JavaScript is required by the VATSIM login and the VDGS panel; there is no
                        // addJavascriptInterface bridge, and navigation is limited to the allowlist
                        // above. Content/file access and mixed content are never needed here.
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowContentAccess = false
                        settings.allowFileAccess = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                !isAllowedVdgsNavigation(request.url.toString())
                        }
                        loadUrl(VdgsUrl)
                    }
                },
                onRelease = { it.destroy() }
            )
        }
    }
}
