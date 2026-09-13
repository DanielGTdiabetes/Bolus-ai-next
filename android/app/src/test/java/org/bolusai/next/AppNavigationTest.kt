package org.bolusai.next

import org.bolusai.next.navigation.AppNavigation
import org.bolusai.next.navigation.Destination
import org.junit.Assert.*
import org.junit.Test

class AppNavigationTest {
    @Test fun startsAtHomeAndRootBackDoesNotCreateAnotherScreen() {
        val navigation = AppNavigation()
        assertEquals(Destination.HOME, navigation.current)
        assertFalse(navigation.back())
    }

    @Test fun backReturnsToActualCallerIncludingLinksAcrossTabs() {
        val navigation = AppNavigation()
        navigation.selectTab(Destination.MORE)
        navigation.open(Destination.MOBILE)
        navigation.open(Destination.OFFLINE_BOLUS)
        assertEquals(Destination.OFFLINE_BOLUS, navigation.current)
        assertTrue(navigation.back())
        assertEquals(Destination.MOBILE, navigation.current)
        assertTrue(navigation.back())
        assertEquals(Destination.MORE, navigation.current)
        assertTrue(navigation.back())
        assertEquals(Destination.HOME, navigation.current)
    }

    @Test fun tabSelectionResetsOldPathAndRepeatedSelectionDoesNotGrowHistory() {
        val navigation = AppNavigation()
        navigation.open(Destination.FAVORITES)
        repeat(5) { navigation.selectTab(Destination.BOLUS) }
        assertEquals(listOf("/", "/bolus"), navigation.save())
        navigation.selectTab(Destination.HOME)
        assertFalse(navigation.canGoBack)
    }

    @Test fun savedPathRestoresScreenAndItsBackHistory() {
        val navigation = AppNavigation()
        navigation.selectTab(Destination.MORE)
        navigation.open(Destination.SETTINGS)
        navigation.open(Destination.NIGHTSCOUT)
        val restored = AppNavigation(navigation.save())
        assertEquals(Destination.NIGHTSCOUT, restored.current)
        restored.back()
        assertEquals(Destination.SETTINGS, restored.current)
        restored.back()
        assertEquals(Destination.MORE, restored.current)
    }

    @Test fun unknownOrMalformedSavedPathFallsBackToHome() {
        listOf(listOf("/", "unknown"), listOf("/bolus"), emptyList()).forEach {
            assertEquals(listOf("/"), AppNavigation(it).save())
        }
    }

    @Test fun duplicateChildDoesNotGrowStackAndHomeClearsIt() {
        val navigation = AppNavigation()
        repeat(5) { navigation.open(Destination.FAVORITES) }
        assertEquals(listOf("/", "/favorites"), navigation.save())
        navigation.open(Destination.HOME)
        assertFalse(navigation.canGoBack)
    }

    @Test fun secondaryScreensSelectTheirCorrespondingLegacyTab() {
        assertEquals(Destination.COMPANION, Destination.FORECAST.tab)
        assertEquals(Destination.COMPANION, Destination.BASAL.tab)
        assertEquals(Destination.SCAN, Destination.SCALE.tab)
        assertEquals(Destination.MORE, Destination.PROFILE.tab)
        assertEquals(Destination.MORE, Destination.MANUAL.tab)
        assertEquals(5, Destination.primary.size)
        assertEquals(Destination.entries.size, Destination.entries.map { it.route }.distinct().size)
    }
}
