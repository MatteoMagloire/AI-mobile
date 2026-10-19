package com.matteomagloire.aimobile.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class TemperatureTest {
    @Test
    fun mistralsAdviceIsTheDefault() {
        assertEquals(DEFAULT_TEMPERATURE, temperatureFrom(null, null))
        assertEquals(0.15f, DEFAULT_TEMPERATURE)
    }

    @Test
    fun theOldDefaultBecomesTheAdvice() {
        // Enregistrée jusqu'à la v0.4.1 dès qu'on touchait à un réglage, sans l'avoir choisie.
        assertEquals(DEFAULT_TEMPERATURE, temperatureFrom(OLD_DEFAULT_TEMPERATURE, null))
    }

    @Test
    fun aChosenValueIsKept() {
        assertEquals(0.4f, temperatureFrom(0.4f, null))
        // Choisie depuis la nouvelle version : 0,7 reste 0,7.
        assertEquals(OLD_DEFAULT_TEMPERATURE, temperatureFrom(OLD_DEFAULT_TEMPERATURE, 2))
    }
}
