package com.matteomagloire.aimobile.update

/** Numéro de version "1.2.3" (le "v" des tags est accepté). */
data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {

    override fun compareTo(other: Version): Int =
        compareValuesBy(this, other, Version::major, Version::minor, Version::patch)

    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("""^[vV]?(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:[-+].*)?$""")

        fun parse(text: String): Version? {
            val m = PATTERN.matchEntire(text.trim()) ?: return null
            val (a, b, c) = m.destructured
            return Version(a.toInt(), b.toIntOrNull() ?: 0, c.toIntOrNull() ?: 0)
        }
    }
}
