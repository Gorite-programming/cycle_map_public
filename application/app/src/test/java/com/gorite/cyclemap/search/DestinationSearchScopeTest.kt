package com.gorite.cyclemap.search

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class DestinationSearchScopeTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun scopesChooseTheirOriginWithoutPretendingMapCenterIsCurrentLocation() {
        val current = 34.0 to 132.0
        val center = 35.0 to 133.0
        assertEquals(current, destinationSearchOrigin(DestinationSearchScope.NEARBY, current, center))
        assertEquals(current, destinationSearchOrigin(DestinationSearchScope.INSTALLED, current, center))
        assertEquals(center, destinationSearchOrigin(DestinationSearchScope.MAP, current, center))
        assertEquals(center, destinationSearchOrigin(DestinationSearchScope.MAP, null, center))
        assertNull(destinationSearchOrigin(DestinationSearchScope.MAP, current, null))
        assertNull(destinationSearchOrigin(DestinationSearchScope.INSTALLED, null, center))
    }

    @Test fun onlyInstalledScopeExpandsDatabaseSelection() {
        val nearby = listOf(SearchDatabase(File("Hiroshima.search.db"), "広島県"))
        val installed = nearby + SearchDatabase(File("Tokyo.search.db"), "東京都")
        assertEquals(nearby, destinationSearchDatabases(DestinationSearchScope.NEARBY, nearby, installed))
        assertEquals(nearby, destinationSearchDatabases(DestinationSearchScope.MAP, nearby, installed))
        assertEquals(installed, destinationSearchDatabases(DestinationSearchScope.INSTALLED, nearby, installed + nearby))
    }

    @Test fun discoveryFindsDistantPrefecturesAndDeduplicatesLegacyYamaguchi() {
        folder.newFile("Tokyo.search.db")
        folder.newFile("hiroshima.search.db")
        folder.newFile("Yamaguchi.search.db")
        folder.newFile("search.db")
        folder.newFile("routing.graph")
        folder.newFolder("Osaka.search.db")
        val found = installedSearchDatabases(folder.root)
        assertEquals(setOf("東京都", "広島県", "山口県"), found.map { it.prefectureName }.toSet())
        assertEquals(3, found.size)
    }

    @Test fun scopeInvalidatesCandidatesEvenForIdenticalOriginAndDatabases() {
        val request = Any()
        val completed = ScopedDestinationSearchRequest(request, DestinationSearchScope.NEARBY)
        assertNotEquals(completed, completed.copy(scope = DestinationSearchScope.MAP))
        assertNotEquals(completed, completed.copy(scope = DestinationSearchScope.INSTALLED))
        assertEquals(completed, completed.copy())
    }
}
