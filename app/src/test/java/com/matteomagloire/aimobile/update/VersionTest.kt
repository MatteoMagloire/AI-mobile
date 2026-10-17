package com.matteomagloire.aimobile.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {
    @Test
    fun parsesTagsWithOrWithoutV() {
        assertEquals(Version(1, 2, 3), Version.parse("v1.2.3"))
        assertEquals(Version(1, 2, 3), Version.parse("1.2.3"))
        assertEquals(Version(2, 0, 0), Version.parse("v2"))
        assertEquals(Version(1, 4, 0), Version.parse("1.4"))
        assertEquals(Version(1, 0, 1), Version.parse("v1.0.1-beta"))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(Version.parse("latest"))
        assertNull(Version.parse(""))
    }

    @Test
    fun comparesNumericallyNotAlphabetically() {
        assertTrue(Version.parse("v1.2.0")!! > Version.parse("v1.1.3")!!)
        assertTrue(Version.parse("1.10.0")!! > Version.parse("1.9.9")!!)
        assertTrue(Version.parse("2.0.0")!! > Version.parse("1.99.99")!!)
    }
}
