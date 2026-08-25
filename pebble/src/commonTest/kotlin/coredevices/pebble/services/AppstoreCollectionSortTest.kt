package coredevices.pebble.services

import coredevices.database.AppstoreSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppstoreCollectionSortTest {
    private val rebbleSource = AppstoreSource(
        url = REBBLE_FEED_URL,
        title = "Rebble App Store",
    )

    @Test
    fun defaultSortDoesNotAddParameter() {
        assertNull(rebbleSource.collectionSortParameter(AppstoreCollectionSort.Default))
    }

    @Test
    fun mostLikedSortUsesHeartsForRebble() {
        assertEquals(
            "hearts",
            rebbleSource.collectionSortParameter(AppstoreCollectionSort.MostLiked),
        )
    }

    @Test
    fun mostLikedSortIsNotSentToUnsupportedSources() {
        val source = AppstoreSource(
            url = PEBBLE_FEED_URL,
            title = "Pebble App Store",
        )

        assertNull(source.collectionSortParameter(AppstoreCollectionSort.MostLiked))
    }
}
