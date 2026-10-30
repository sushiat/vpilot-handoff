package at.sushi.handoff.ui.dialogs

import at.sushi.handoff.util.CdmSlotDisplay
import at.sushi.handoff.util.CdmWindowPhase
import at.sushi.handoff.util.minutesUntilUtcHhmm
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Issue #169 -- what the TOBT dialog shows in demo mode instead of the live site: the real VDGS needs
 *  a VATSIM login (which a Play reviewer or a pilot just trying the app doesn't have), so this is
 *  a static, clearly-labelled mock of the page for the sample flight, laid out like the real one:
 *  header with the UTC clock, the banner for the current start-up window phase, the read-only
 *  flight-data grid and the TOBT entry form beside it, and the footer. Built from the same demo slot
 *  the status bar chip shows. The clock and the banner tick live through one small inline script that
 *  only reads the device clock (it also moves the banner on at the window's edges), so the page stays
 *  in step with the chip while it is open. No network, no links, no logo -- nothing here can be
 *  interacted with or lead anywhere. */
private const val BeforeText = "Not yet time for start-up. Wait for your window or update TOBT. Window can change at any time."
private const val OpenText = "You are in your start-up window. Request start-up from ATC if online."
private const val MissedText = "Start-up window missed. Update TOBT or coordinate with ATC."

// The site documents the window as roughly -5 to +6 minutes around the reference time (see
// CdmSlotStatus.cdmWindow, which the status bar chip follows).
private const val OpensBeforeReferenceMillis = 5 * 60_000L
private const val ClosesAfterReferenceMillis = 6 * 60_000L

internal fun demoVdgsHtml(slot: CdmSlotDisplay?, nowEpochMillis: Long): String {
    val live = slot?.at(nowEpochMillis)
    val secondOfMinute = (nowEpochMillis / 1000 % 60).toInt()
    // The slot's minutes are whole minutes counted from the start of the current minute.
    fun countdown(minutes: Int): String {
        val total = (minutes * 60 - secondOfMinute).coerceAtLeast(0)
        return String.format(Locale.ROOT, "%02d:%02d", total / 60, total % 60)
    }
    val banner = when (live?.phase) {
        CdmWindowPhase.BEFORE -> demoBanner(
            "warn", "&#8987;",
            BeforeText,
            live.minutes?.let { countdown(it) + " to window" }
        )
        CdmWindowPhase.OPEN -> demoBanner(
            "ok", "&#10003;",
            OpenText,
            live.minutes?.let { countdown(it) + " left" }
        )
        CdmWindowPhase.MISSED -> demoBanner(
            "bad", "&#9888;",
            MissedText,
            null
        )
        else -> ""
    }
    val clock = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(nowEpochMillis))
    // The reference time as an epoch instant, so the script can tick without the slot.
    val referenceMillis = slot?.referenceHhmm?.let { minutesUntilUtcHhmm(it, nowEpochMillis) }
        ?.let { nowEpochMillis - nowEpochMillis % 60_000L + it * 60_000L }
    val script = referenceMillis?.let { tickScript(it - OpensBeforeReferenceMillis, it + ClosesAfterReferenceMillis) } ?: ""
    fun cell(label: String, value: String?) =
        """<div class="cell"><div class="k">$label</div><div class="v">${value ?: "- - - -"}</div></div>"""

    return """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
*{box-sizing:border-box}
body{margin:0;padding:16px;background:#0B0F1A;color:#E2E8F0;font-family:sans-serif}
.demo{background:#92400E;color:#FEF3C7;padding:12px 16px;border-radius:10px;font-size:14px;margin-bottom:14px}
.card{background:#141A27;border:1px solid #232B3B;border-radius:12px}
.header{display:flex;flex-wrap:wrap;align-items:center;gap:10px;padding:12px 14px;margin-bottom:14px}
.id{font-weight:bold;font-size:15px;color:#CBD5E1}.id span{color:#64748B;font-weight:normal;margin:0 8px}
.id b{color:#FACC15}
.spacer{flex:1}
.chip{display:inline-block;padding:7px 11px;border-radius:8px;font-size:13px;font-weight:bold;border:1px solid #1E3A4C;background:#112230;color:#7DD3FC;opacity:.75}
.clock{border-color:#5C4A12;background:#1F1A0B;color:#FBBF24;opacity:1;font-family:monospace;font-size:15px}
.clock small{font-size:10px;margin-left:4px;letter-spacing:.08em}
.msg{display:flex;align-items:center;gap:12px;padding:14px 16px;border-radius:12px;font-size:15px;font-weight:bold;margin-bottom:14px}
.msg i{flex:none;font-style:normal;font-size:22px;line-height:24px}
.msg .t{flex:1}.msg .n{font-size:18px;white-space:nowrap}
@media (max-width:600px){
.msg{display:grid;grid-template-columns:auto 1fr;column-gap:12px;row-gap:6px;align-items:start;padding:12px 14px;font-size:14px}
.msg .n{grid-column:2;font-size:22px}
}
.msg.warn{background:#1C1405;border:1px solid #B45309;color:#FDE68A}
.msg.ok{background:#06180E;border:1px solid #15803D;color:#86EFAC}
.msg.bad{background:#240808;border:1px solid #DC2626;color:#FCA5A5}
.row{display:flex;flex-wrap:wrap;gap:14px;align-items:flex-start}
.data{flex:2 1 340px}.form{flex:1 1 240px}
.title{display:flex;align-items:center;padding:11px 14px;border-bottom:1px solid #232B3B;font-size:11px;letter-spacing:.1em;font-weight:bold;color:#64748B}
.pill{margin-left:auto;padding:3px 8px;border:1px solid #334155;border-radius:6px;font-size:9px;letter-spacing:.04em;color:#94A3B8}
.grid{display:grid;grid-template-columns:1fr 1fr}
.cell{padding:12px 14px;border-bottom:1px solid #1B2230}
.cell:nth-child(odd){border-right:1px solid #1B2230}
.cell:nth-last-child(-n+2){border-bottom:0}
.k{font-size:10px;letter-spacing:.08em;font-weight:bold;color:#64748B}
.v{font-family:monospace;font-size:22px;font-weight:bold;color:#FACC15;margin-top:4px}
.formbody{padding:14px}
.flabel{margin:0 0 8px;font-size:11px;letter-spacing:.06em;font-weight:bold;color:#CBD5E1}
.frow{display:flex;gap:10px}
.input{flex:1;padding:13px 12px;border-radius:8px;background:#0B0F1A;border:1px solid #232B3B;font-family:monospace;font-size:17px;color:#475569}
.send{flex:none;width:48px;border-radius:8px;background:#FACC15;color:#1A1203;text-align:center;line-height:48px;font-size:18px;opacity:.55}
.hint{margin-top:10px;font-size:12px;color:#64748B}
.footer{display:flex;flex-wrap:wrap;align-items:center;gap:12px;margin-top:18px;padding-top:14px;border-top:1px solid #1B2230;font-size:13px;color:#94A3B8}
.toggle{display:inline-block;width:38px;height:20px;border-radius:10px;background:#334155;vertical-align:middle;margin-left:8px;position:relative}
.toggle:after{content:"";position:absolute;left:3px;top:3px;width:14px;height:14px;border-radius:7px;background:#94A3B8}
</style></head><body>
<div class="demo">Demo mode: static sample page. The real VDGS site needs a VATSIM login and is shown when you are connected to the plugin.</div>
<div class="card header">
<div class="id">1234567<span>&middot;</span><b>AUA81</b></div>
<div class="spacer"></div>
<span class="chip clock" id="clock">$clock<small>UTC</small></span>
<span class="chip">Flt Details</span><span class="chip">Predict/RRP</span><span class="chip">Live ACDM</span>
</div>
$banner
<div class="row">
<div class="card data">
<div class="title">FLIGHT DATA<span class="pill">A-CDM DISCONNECTED</span></div>
<div class="grid">
${cell("CALLSIGN", "AUA81")}
${cell("EOBT", live?.eobt)}
${cell("TOBT", live?.tobt)}
${cell("TSAT", live?.tsat)}
${cell("TAXI TIME", live?.exot?.toString())}
${cell("CTOT", live?.ctot)}
${cell("REASON", live?.reason)}
${cell("ATFCM", live?.atfcmStatus)}
${cell("SID", live?.sid)}
</div>
</div>
<div class="card form">
<div class="title">UPDATE TOBT</div>
<div class="formbody">
<div class="flabel">UTC TIME (HHMM FORMAT)</div>
<div class="frow"><div class="input">e.g. 1725</div><div class="send">&#10148;</div></div>
<div class="hint">Disabled in demo mode.</div>
</div>
</div>
</div>
<div class="footer">
<span>Browser notifications<span class="toggle"></span></span>
<span class="spacer"></span>
<span class="chip">First time? Get started</span>
<span class="chip">Documentation</span>
</div>
$script</body></html>"""
}

private fun demoBanner(kind: String, icon: String, text: String, countdown: String?): String {
    val count = countdown?.let { """<span class="n">$it</span>""" } ?: ""
    return """<div id="msg" class="msg $kind"><i>$icon</i><span class="t">$text</span>$count</div>"""
}

/** Ticks the page's UTC clock and moves the banner through its three phases off the device clock. */
private fun tickScript(opensAtMillis: Long, closesAtMillis: Long): String = """<script>
(function(){
var OPEN=$opensAtMillis,CLOSE=$closesAtMillis;
function pad(n){return (n<10?"0":"")+n}
function mmss(ms){var t=Math.max(0,Math.floor(ms/1000));return pad(Math.floor(t/60))+":"+pad(t%60)}
var msg=document.getElementById("msg"),clock=document.getElementById("clock");
function show(kind,icon,text,count){
msg.className="msg "+kind;
msg.innerHTML="<i>"+icon+"</i><span class=\"t\">"+text+"</span>"+(count?"<span class=\"n\">"+count+"</span>":"");
}
function tick(){
var now=Date.now(),d=new Date(now);
clock.firstChild.nodeValue=pad(d.getUTCHours())+":"+pad(d.getUTCMinutes())+":"+pad(d.getUTCSeconds());
if(!msg)return;
if(now<OPEN)show("warn","&#8987;","$BeforeText",mmss(OPEN-now)+" to window");
else if(now<=CLOSE)show("ok","&#10003;","$OpenText",mmss(CLOSE-now)+" left");
else show("bad","&#9888;","$MissedText","");
}
tick();setInterval(tick,1000);
})();
</script>
"""
