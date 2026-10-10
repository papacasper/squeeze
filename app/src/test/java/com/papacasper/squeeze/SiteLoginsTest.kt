package com.papacasper.squeeze

import org.junit.Assert.assertEquals
import org.junit.Test

class SiteLoginsTest {
    @Test fun parsesCookieManagerHeader() {
        assertEquals(listOf("auth_token" to "abc", "ct0" to "x=y"), SiteLogins.parse("auth_token=abc; ct0=x=y"))
        assertEquals(emptyList<Pair<String, String>>(), SiteLogins.parse(null))
        assertEquals(emptyList<Pair<String, String>>(), SiteLogins.parse("; =bad;"))
    }

    @Test fun writesNetscapeLinesForHostAndSubdomains() {
        assertEquals(
            listOf(".x.com\tTRUE\t/\tTRUE\t1800000000\tauth_token\tabc"),
            SiteLogins.netscapeLines("x.com", listOf("auth_token" to "abc"), 1_800_000_000L)
        )
    }
}
