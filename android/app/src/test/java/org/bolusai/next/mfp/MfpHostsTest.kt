package org.bolusai.next.mfp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR 0032: the link reaches MyFitnessPal's own hosts over HTTPS and nothing else. */
class MfpHostsTest {
    @Test fun diaryAddressIsTheReadOnlyPageOfOneDay() {
        assertEquals("https://www.myfitnesspal.com/food/diary?date=2026-01-15", MfpHosts.diaryUrl(MfpTestData.date))
        assertTrue(MfpHosts.mainFrameAllowed(MfpHosts.diaryUrl(MfpTestData.date)))
        assertTrue(MfpHosts.mainFrameAllowed(MfpHosts.DIARY_PAGE))
    }

    @Test fun onlyTheDiaryHostMayBeShown() {
        assertTrue(MfpHosts.mainFrameAllowed("https://www.myfitnesspal.com/account/login"))
        assertTrue(MfpHosts.mainFrameAllowed("HTTPS://WWW.MyFitnessPal.com/es/food/diary"))
        listOf(
            "http://www.myfitnesspal.com/food/diary",
            "https://api.myfitnesspal.com/v2/users",
            "https://myfitnesspal.com/",
            "https://www.myfitnesspal.com.example.org/",
            "https://example.org/www.myfitnesspal.com",
            "https://www.myfitnesspal.com@example.org/",
            "https://synthetic" + ":synthetic@www.myfitnesspal.com/", // user information; split for lint AuthLeak
            "https://www.myfitnesspal.com:8443/",
            "https://example.org\\@www.myfitnesspal.com/",
            "https://accounts.google.com/o/oauth2",
            "intent://www.myfitnesspal.com/",
            "file:///data/data/org.bolusai.next/",
            "javascript:alert(1)",
            "",
        ).forEach { assertFalse(it, MfpHosts.mainFrameAllowed(it)) }
    }

    @Test fun backslashEndsTheAuthorityAsBrowsersReadIt() {
        assertEquals("www.myfitnesspal.com", MfpHosts.host("https://www.myfitnesspal.com\\@example.org/"))
        assertEquals("example.org", MfpHosts.host("https://example.org\\@www.myfitnesspal.com/"))
    }

    @Test fun pagesMayLoadOnlyFromMyFitnessPalOrWithoutAConnection() {
        listOf(
            "https://www.myfitnesspal.com/_next/static/app.js",
            "https://api.myfitnesspal.com/v2/x",
            "https://myfitnesspal.com/favicon.ico",
            "data:image/png;base64,AAAA",
            "blob:https://www.myfitnesspal.com/1234",
            "about:blank",
        ).forEach { assertTrue(it, MfpHosts.resourceAllowed(it)) }
        listOf(
            "http://www.myfitnesspal.com/a.js",
            "https://notmyfitnesspal.com/a.js",
            "https://myfitnesspal.com.example.org/a.js",
            "https://example.org/a.js",
            "https://legacy.example.org/api/integrations/nutrition",
            "ws://www.myfitnesspal.com/socket",
            "content://org.bolusai.next/x",
        ).forEach { assertFalse(it, MfpHosts.resourceAllowed(it)) }
    }

    @Test fun hostIsNullForAnythingThatIsNotPlainHttps() {
        assertNull(MfpHosts.host("http://www.myfitnesspal.com/"))
        assertNull(MfpHosts.host("https:///path"))
        assertNull(MfpHosts.host("https://a b/"))
    }

    @Test fun blockedLabelShowsOnlyTheHost() {
        assertEquals("example.org", MfpHosts.blockedLabel("https://synthetic" + ":synthetic@Example.org/path?token=abc#x"))
        assertEquals("desconocido", MfpHosts.blockedLabel(""))
    }
}
