package at.sushi.handoff.util

import at.sushi.handoff.ChannelSpacing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChannelSpacingTest {

    @Test
    fun khz25_validDecimalValues_areEveryMultipleOf25() {
        val values = ChannelGrid.validDecimalValues(ChannelSpacing.KHZ_25)
        assertEquals(40, values.size)
        assertTrue(values.contains(0))
        assertTrue(values.contains(725))
        assertTrue(values.contains(975))
        assertFalse(values.contains(710))
    }

    @Test
    fun khz833_validDecimalValues_has160EntriesOnBlockStartPlus0_5_10_15() {
        val values = ChannelGrid.validDecimalValues(ChannelSpacing.KHZ_8_33)
        assertEquals(160, values.size)
        // 123.725 is a real 8.33-spaced channel (block start 725, +0).
        assertTrue(values.contains(725))
        // 123.710 is also real (block start 700, +10) -- 8.33 channels are denser than 25kHz.
        assertTrue(values.contains(710))
        // 123.712 falls between grid points on every block (no +0/+5/+10/+15 lands there).
        assertFalse(values.contains(712))
        // Every block-start-plus-offset combination should be present.
        assertTrue(values.contains(0))
        assertTrue(values.contains(5))
        assertTrue(values.contains(10))
        assertTrue(values.contains(15))
        assertTrue(values.contains(990))
    }

    @Test
    fun isInBand_boundaries() {
        assertTrue(ChannelGrid.isInBand(118_000))
        assertTrue(ChannelGrid.isInBand(136_990))
        assertFalse(ChannelGrid.isInBand(117_999))
        assertFalse(ChannelGrid.isInBand(136_991))
    }

    @Test
    fun nearestValid_snapsToClosestGridValue_25khz() {
        // 123.710 isn't on the 25kHz grid -- nearest multiples of 25 are 700 and 725.
        assertEquals(123_700, ChannelGrid.nearestValid(123_710, ChannelSpacing.KHZ_25))
        assertEquals(123_725, ChannelGrid.nearestValid(123_713, ChannelSpacing.KHZ_25))
    }

    @Test
    fun nearestValid_snapsToClosestGridValue_833khz() {
        assertEquals(123_725, ChannelGrid.nearestValid(123_724, ChannelSpacing.KHZ_8_33))
    }

    @Test
    fun nearestValid_clampsIntoCivilBand() {
        assertEquals(ChannelGrid.BAND_MIN, ChannelGrid.nearestValid(100_000, ChannelSpacing.KHZ_25))
        assertEquals(ChannelGrid.BAND_MAX, ChannelGrid.nearestValid(999_000, ChannelSpacing.KHZ_25))
    }

    @Test
    fun isValidPrefix_emptyPrefixIsAlwaysValid() {
        assertTrue(ChannelGrid.isValidPrefix("", ChannelSpacing.KHZ_25))
        assertTrue(ChannelGrid.isValidPrefix("", ChannelSpacing.KHZ_8_33))
    }

    @Test
    fun isValidPrefix_firstDigitMustBe1_bandStartsAt118() {
        assertTrue(ChannelGrid.isValidPrefix("1", ChannelSpacing.KHZ_25))
        assertFalse(ChannelGrid.isValidPrefix("2", ChannelSpacing.KHZ_25))
        assertFalse(ChannelGrid.isValidPrefix("0", ChannelSpacing.KHZ_25))
    }

    @Test
    fun isValidPrefix_rejectsWholeMhzAbove136() {
        assertTrue(ChannelGrid.isValidPrefix("136", ChannelSpacing.KHZ_25))
        assertFalse(ChannelGrid.isValidPrefix("137", ChannelSpacing.KHZ_25))
    }

    @Test
    fun isValidPrefix_bandEdgeInteractsWithGrid() {
        // The band's upper edge (136.990) is deliberately an 8.33 grid point (975 block start +
        // 15), but the 25kHz grid tops out at .975 -- .990 isn't a 25kHz channel at all.
        assertTrue(ChannelGrid.isValidPrefix("136990", ChannelSpacing.KHZ_8_33))
        assertFalse(ChannelGrid.isValidPrefix("136990", ChannelSpacing.KHZ_25))
        assertTrue(ChannelGrid.isValidPrefix("136975", ChannelSpacing.KHZ_25))
    }

    @Test
    fun isValidPrefix_rejectsDecimalNotOnGrid() {
        // 710 isn't a multiple of 25, so it's invalid on the 25kHz grid but fine on 8.33's
        // denser one (700 + 10).
        assertFalse(ChannelGrid.isValidPrefix("123710", ChannelSpacing.KHZ_25))
        assertTrue(ChannelGrid.isValidPrefix("123710", ChannelSpacing.KHZ_8_33))
        // 712 is on neither grid.
        assertFalse(ChannelGrid.isValidPrefix("123712", ChannelSpacing.KHZ_25))
        assertFalse(ChannelGrid.isValidPrefix("123712", ChannelSpacing.KHZ_8_33))
        assertTrue(ChannelGrid.isValidPrefix("123725", ChannelSpacing.KHZ_25))
        assertTrue(ChannelGrid.isValidPrefix("123725", ChannelSpacing.KHZ_8_33))
    }

    @Test
    fun completePrefix_fillsSmallestValidCompletion() {
        assertEquals(118_000, ChannelGrid.completePrefix("", ChannelSpacing.KHZ_25))
        assertEquals(123_000, ChannelGrid.completePrefix("123", ChannelSpacing.KHZ_25))
    }

    @Test
    fun completePrefix_nullWhenNoCompletionExists() {
        assertEquals(null, ChannelGrid.completePrefix("2", ChannelSpacing.KHZ_25))
    }
}
