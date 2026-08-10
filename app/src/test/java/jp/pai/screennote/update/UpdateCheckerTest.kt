package jp.pai.screennote.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun `higher patch version is newer`() {
        assertTrue(UpdateChecker.isNewer("1.0.1", "1.0.0"))
        assertFalse(UpdateChecker.isNewer("1.0.0", "1.0.1"))
    }

    @Test
    fun `leading v is ignored`() {
        assertEquals(0, UpdateChecker.compareVersions("v1.2.3", "1.2.3"))
    }

    @Test
    fun `missing components count as zero`() {
        assertEquals(0, UpdateChecker.compareVersions("1.2", "1.2.0"))
        assertTrue(UpdateChecker.isNewer("1.3", "1.2.9"))
    }

    @Test
    fun `numeric ordering is not lexicographic`() {
        assertTrue(UpdateChecker.isNewer("1.10.0", "1.9.0"))
    }

    @Test
    fun `prerelease sorts below the plain release`() {
        assertTrue(UpdateChecker.isNewer("1.0.0", "1.0.0-rc1"))
        assertFalse(UpdateChecker.isNewer("1.0.0-rc1", "1.0.0"))
    }

    @Test
    fun `local dev build is older than any release`() {
        assertTrue(UpdateChecker.isNewer("0.0.1", "0.0.0-dev"))
    }
}
