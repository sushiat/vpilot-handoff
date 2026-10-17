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

/** Issue #169 -- what the dialog shows in demo mode instead of the live site: the real VDGS needs
 *  a VATSIM login (which a Play reviewer or a pilot just trying the app doesn't have), so this is
 *  a static, clearly-labelled mock of the TOBT panel for the sample flight. No scripts, no
 *  network, no links -- nothing here can be interacted with or lead anywhere. */
internal const val DemoVdgsHtml = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
body{margin:0;padding:20px;background:#0F172A;color:#E2E8F0;font-family:sans-serif}
.banner{background:#92400E;color:#FEF3C7;padding:10px 14px;border-radius:8px;font-size:14px;margin-bottom:18px}
h1{font-size:20px;margin:0 0 4px}.sub{color:#94A3B8;font-size:13px;margin-bottom:18px}
table{width:100%;max-width:520px;border-collapse:collapse}
td{padding:10px 12px;border-bottom:1px solid #1E293B;font-size:15px}
td:first-child{color:#94A3B8;width:45%}td:last-child{font-weight:bold}
.btn{display:inline-block;margin-top:18px;padding:10px 18px;border-radius:8px;background:#334155;color:#94A3B8;font-size:14px}
</style></head><body>
<div class="banner">Demo mode: static sample page. The real VDGS site needs a VATSIM login and is shown when you are connected to the plugin.</div>
<h1>AUA81 &middot; LOWW &rarr; EGLL</h1>
<div class="sub">Sample A-CDM departure data</div>
<table>
<tr><td>EOBT</td><td>sample</td></tr>
<tr><td>TOBT</td><td>sample</td></tr>
<tr><td>TSAT</td><td>sample</td></tr>
<tr><td>EXOT</td><td>12 min</td></tr>
<tr><td>Runway</td><td>29</td></tr>
<tr><td>SID</td><td>LNZ2C</td></tr>
</table>
<div class="btn">Set / edit TOBT (disabled in demo)</div>
</body></html>"""

/** Issue #143 -- hosts the real VDGS site in an embedded WebView so the pilot can log in with
 *  VATSIM and set/edit their TOBT without leaving the app. WebView (not a Custom Tab) so the
 *  session cookie lives in this app's own cookie jar, which a native TOBT entry could later reuse;
 *  the WebView keeps cookies between openings, so the VATSIM login is only needed once per
 *  session lifetime. */
@Composable
fun CdmWebDialog(demo: Boolean = false, onDismiss: () -> Unit) {
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
                    val webView = WebView(ctx)
                    // Explicit match-parent params: a WebView created without any is laid out as
                    // wrap-content, and Chromium then resolves every vh unit to 0, so pages built
                    // on min-h-screen/h-screen (the VATSIM login) collapse to zero height and
                    // clip all their content.
                    webView.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    // Same slate the dark login page uses, so there's no white flash while loading.
                    webView.setBackgroundColor(android.graphics.Color.parseColor("#0F172A"))
                    // JavaScript is required by the VATSIM login and the VDGS panel; there is no
                    // addJavascriptInterface bridge, and navigation is limited to the allowlist
                    // above. Content/file access and mixed content are never needed here. Plain
                    // setter calls on a named WebSettings (not property syntax inside apply) so
                    // static analysis can see that content access is disabled.
                    val webSettings = webView.settings
                    webSettings.setJavaScriptEnabled(!demo)
                    webSettings.setDomStorageEnabled(true)
                    webSettings.setAllowContentAccess(false)
                    webSettings.setAllowFileAccess(false)
                    webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW)
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                            demo || !isAllowedVdgsNavigation(request.url.toString())
                    }
                    if (demo) {
                        webView.loadDataWithBaseURL(null, DemoVdgsHtml, "text/html", "utf-8", null)
                    } else {
                        webView.loadUrl(VdgsUrl)
                    }
                    webView
                },
                onRelease = { it.destroy() }
            )
        }
    }
}
