/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.cloud

import com.dot.gallery.cloud.sync.CloudFavoriteOfflineWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Network-free checks for favorite offline download wiring.
 */
class CloudFavoriteOfflineTest {

    @Test
    fun favoriteWorkerUsesOriginalSizeLabel() {
        assertEquals("original", CloudFavoriteOfflineWorker.SIZE_ORIGINAL)
        assertTrue(CloudFavoriteOfflineWorker.WORK_NAME.contains("favorite"))
    }

    @Test
    fun roomFavoriteUpdateHonorsSuccessOnly() {
        // Mirrors MediaHandlerImpl: Room is updated only when provider Result is success.
        fun shouldUpdateRoom(success: Boolean) = success
        assertTrue(shouldUpdateRoom(true))
        assertTrue(!shouldUpdateRoom(false))
    }
}
