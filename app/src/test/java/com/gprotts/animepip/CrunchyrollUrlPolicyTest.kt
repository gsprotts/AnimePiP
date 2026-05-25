package com.gprotts.animepip

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrunchyrollUrlPolicyTest {
    @Test
    fun allowsRootCrunchyrollHttpsHost() {
        assertTrue(CrunchyrollUrlPolicy.isAllowedCrunchyrollUrl("https://crunchyroll.com"))
    }

    @Test
    fun allowsWwwCrunchyrollHttpsHost() {
        assertTrue(CrunchyrollUrlPolicy.isAllowedCrunchyrollUrl("https://www.crunchyroll.com"))
    }

    @Test
    fun allowsCrunchyrollSubdomain() {
        assertTrue(CrunchyrollUrlPolicy.isAllowedCrunchyrollUrl("https://beta.crunchyroll.com/watch/123"))
    }

    @Test
    fun rejectsHttp() {
        assertFalse(CrunchyrollUrlPolicy.isAllowedCrunchyrollUrl("http://crunchyroll.com"))
    }

    @Test
    fun rejectsCrunchyrollOnlyInQuery() {
        assertFalse(CrunchyrollUrlPolicy.isAllowedCrunchyrollUrl("https://evil.com/?next=crunchyroll.com"))
    }

    @Test
    fun rejectsDeceptiveHost() {
        assertFalse(CrunchyrollUrlPolicy.isAllowedCrunchyrollUrl("https://crunchyroll.com.evil.com"))
    }

    @Test
    fun rejectsMalformedUrl() {
        assertFalse(CrunchyrollUrlPolicy.isAllowedCrunchyrollUrl("not a url"))
    }

    @Test
    fun rejectsEmptyUrl() {
        assertFalse(CrunchyrollUrlPolicy.isAllowedCrunchyrollUrl(""))
    }
}
