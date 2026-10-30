package at.sushi.handoff.ui.dialogs

import at.sushi.handoff.demo.DemoData
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class DemoVdgsPageTest {
    // Mid-minute, so the whole-minute countdown never lands on a boundary.
    private val now = 1_761_790_000_000L - (1_761_790_000_000L % 60_000L) + 30_000L

    private fun htmlFor(anchorOffsetMillis: Long): String =
        demoVdgsHtml(DemoData.cdmSlot(now + anchorOffsetMillis), now)

    @Test
    fun beforeWindow_showsYellowBannerWithCountdown() {
        val html = htmlFor(0)
        assertContains(html, "msg warn")
        assertContains(html, "Not yet time for start-up")
        assertContains(html, " to window")
    }

    @Test
    fun insideWindow_showsGreenBannerWithTimeLeft() {
        val html = htmlFor(-7 * 60_000L)
        assertContains(html, "msg ok")
        assertContains(html, "You are in your start-up window")
        assertContains(html, " left")
    }

    @Test
    fun afterWindow_showsRedBannerWithoutCountdown() {
        val html = htmlFor(-20 * 60_000L)
        assertContains(html, "msg bad")
        assertContains(html, "Start-up window missed")
        assertFalse(html.contains("class=\"n\""))
    }

    @Test
    fun noSlot_hasNoBanner() {
        assertFalse(demoVdgsHtml(null, now).contains("class=\"msg"))
    }

    @Test
    fun withSlot_ticksFromTheWindowEdges() {
        val html = htmlFor(0)
        // Reference is 7 minutes ahead of "now" (DemoData's anchor): opens 5 minutes before it, closes 6 after.
        val reference = now - now % 60_000L + 7 * 60_000L
        assertContains(html, "OPEN=${reference - 5 * 60_000L},CLOSE=${reference + 6 * 60_000L}")
    }

    @Test
    fun noSlot_hasNoScript() {
        assertFalse(demoVdgsHtml(null, now).contains("<script>"))
    }

    @Test
    fun taxiTime_hasNoUnit() {
        assertContains(htmlFor(0), "<div class=\"v\">12</div>")
    }
}
