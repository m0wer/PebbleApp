package coredevices.pebble.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class BatterySettingsScreenTest {
    @Test
    fun csvEscapesCarriageReturns() {
        assertEquals("\"Pebble\rTime\"", "Pebble\rTime".csvEscape())
    }
}
