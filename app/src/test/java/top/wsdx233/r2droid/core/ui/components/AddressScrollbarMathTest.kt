package top.wsdx233.r2droid.core.ui.components

import org.junit.Assert.*
import org.junit.Test

class AddressScrollbarMathTest {
    @Test fun addressMappingClampsToTheEndExclusiveRange() {
        assertEquals(100L, scrollbarAddressAtRatio(100, 200, -1f))
        assertEquals(149L, scrollbarAddressAtRatio(100, 200, .5f))
        assertEquals(199L, scrollbarAddressAtRatio(100, 200, 1f))
        assertEquals(199L, scrollbarAddressAtRatio(100, 200, 2f))
        assertEquals(100L, scrollbarAddressAtRatio(100, 101, 1f))
        assertEquals(100L, scrollbarAddressAtRatio(100, 100, .5f))
        assertEquals(100L, scrollbarAddressAtRatio(100, 200, Float.NaN))
    }

    @Test fun smallRangesAtHighAddressesRetainTheirPrecision() {
        val start = Long.MAX_VALUE - 1000
        assertEquals(start + 499, scrollbarAddressAtRatio(start, Long.MAX_VALUE, .5f))
        assertEquals(Long.MAX_VALUE - 1, scrollbarAddressAtRatio(start, Long.MAX_VALUE, 1f))
        assertEquals(Long.MAX_VALUE - 1, scrollbarAddressAtRatio(0, Long.MAX_VALUE, 1f))
    }

    @Test fun geometryAccountsForThumbHeightAndGrabOffset() {
        assertEquals(0f, scrollbarRatioAtPosition(20f, 240f, 40f, 20f))
        assertEquals(.5f, scrollbarRatioAtPosition(120f, 240f, 40f, 20f))
        assertEquals(1f, scrollbarRatioAtPosition(220f, 240f, 40f, 20f))
        assertEquals(.5f, scrollbarRatioAtPosition(103f, 240f, 40f, 3f))
        assertEquals(0f, scrollbarRatioAtPosition(-100f, 240f, 40f, 20f))
        assertEquals(1f, scrollbarRatioAtPosition(400f, 240f, 40f, 20f))
        assertEquals(0f, scrollbarRatioAtPosition(20f, 40f, 40f, 20f))
    }
}
