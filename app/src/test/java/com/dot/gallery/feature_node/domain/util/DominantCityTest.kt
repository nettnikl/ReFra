/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DominantCityTest {

    @Test
    fun returnsNullWhenNoGeo() {
        assertNull(dominantCity(listOf(null, null, "", "  ")))
    }

    @Test
    fun picksMostFrequentCity() {
        assertEquals(
            "München",
            dominantCity(
                listOf("München", "Budapest", "München", null, "Berlin", "München")
            )
        )
    }

    @Test
    fun trimsAndIgnoresBlank() {
        assertEquals(
            "Paris",
            dominantCity(listOf(" Paris ", "", null, "Paris", "Lyon"))
        )
    }

    @Test
    fun tieBreaksLexicographically() {
        assertEquals(
            "Berlin",
            dominantCity(listOf("München", "Berlin", "München", "Berlin"))
        )
    }

    @Test
    fun singleCityWinsAmongMissing() {
        assertEquals(
            "Tokyo",
            dominantCity(listOf(null, null, "Tokyo", null))
        )
    }
}
