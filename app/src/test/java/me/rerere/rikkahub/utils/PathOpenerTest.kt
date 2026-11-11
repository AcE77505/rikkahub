package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PathOpenerTest {
    @Test
    fun `no mt manager installed means system fallback`() {
        assertNull(chooseMtPackage(emptyList(), remembered = null))
    }

    @Test
    fun `single installed package is used directly`() {
        assertEquals("bin.mt.plus", chooseMtPackage(listOf("bin.mt.plus"), remembered = null))
        assertEquals(
            "bin.mt.plus.canary",
            chooseMtPackage(listOf("bin.mt.plus.canary"), remembered = "bin.mt.plus"),
        )
    }

    @Test
    fun `two installed and nothing remembered asks the user`() {
        assertNull(chooseMtPackage(listOf("bin.mt.plus", "bin.mt.plus.canary"), remembered = null))
    }

    @Test
    fun `remembered package wins while still installed`() {
        assertEquals(
            "bin.mt.plus.canary",
            chooseMtPackage(listOf("bin.mt.plus", "bin.mt.plus.canary"), remembered = "bin.mt.plus.canary"),
        )
    }

    @Test
    fun `remembered package that is gone asks again`() {
        assertNull(
            chooseMtPackage(listOf("bin.mt.plus", "bin.mt.plus.canary"), remembered = "com.other.manager")
        )
    }
}
