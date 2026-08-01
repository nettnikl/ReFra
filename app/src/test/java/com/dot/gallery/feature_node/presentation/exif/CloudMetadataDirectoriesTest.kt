/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.presentation.exif

import com.dot.gallery.cloud.core.ProviderType
import com.dot.gallery.cloud.data.entity.CloudMediaEntity
import com.dot.gallery.feature_node.domain.model.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudMetadataDirectoriesTest {

    @Test
    fun emptyWhenNoEntityOrMetadata() {
        assertTrue(buildCloudMetadataDirectories(null, null).isEmpty())
    }

    @Test
    fun buildsExifAndGpsFromEntity() {
        val entity = CloudMediaEntity(
            remoteId = "abc",
            providerType = ProviderType.PHOTOPRISM,
            serverConfigId = 1L,
            label = "IMG_001.jpg",
            path = "2024/Trip/IMG_001.jpg",
            mimeType = "image/jpeg",
            size = 2048L,
            width = 4000,
            height = 3000,
            cameraMake = "Canon",
            cameraModel = "EOS R5",
            aperture = "f/2.8",
            exposureTime = "1/125 sec",
            iso = 200,
            focalLength = 50.0,
            dateTimeOriginal = "2024:06:01 12:00:00",
            latitude = 48.137154,
            longitude = 11.576124,
            city = "Munich",
            country = "Germany"
        )

        val dirs = buildCloudMetadataDirectories(entity, null)
        assertTrue(dirs.isNotEmpty())
        assertTrue(dirs.any { it.name == "File" })
        assertTrue(dirs.any { it.name == "Image" })
        assertTrue(dirs.any { it.name == "Exif" })
        assertTrue(dirs.any { it.name == "GPS" })

        val exif = dirs.first { it.name == "Exif" }.tags.associate { it.name to it.description }
        assertEquals("Canon", exif["Make"])
        assertEquals("EOS R5", exif["Model"])
        assertEquals("f/2.8", exif["F-Number"])
        assertEquals("200", exif["ISO"])

        val file = dirs.first { it.name == "File" }.tags.associate { it.name to it.description }
        assertEquals("PhotoPrism", file["Provider"])
        assertEquals("IMG_001.jpg", file["File Name"])

        val gps = dirs.first { it.name == "GPS" }.tags.associate { it.name to it.description }
        assertEquals("48.137154", gps["Latitude"])
        assertEquals("11.576124", gps["Longitude"])
        assertEquals("Munich", gps["City"])
        assertEquals("Germany", gps["Country"])
    }

    @Test
    fun buildsVideoDirectoryFromEntity() {
        val entity = CloudMediaEntity(
            remoteId = "vid",
            providerType = ProviderType.PHOTOPRISM,
            serverConfigId = 1L,
            mimeType = "video/mp4",
            width = 1920,
            height = 1080,
            duration = "0:01:05"
        )
        val dirs = buildCloudMetadataDirectories(entity, null)
        assertTrue(dirs.any { it.name == "Video" })
        assertTrue(dirs.none { it.name == "Image" })
        val video = dirs.first { it.name == "Video" }.tags.associate { it.name to it.description }
        assertEquals("1920 pixels", video["Video Width"])
        assertEquals("1080 pixels", video["Video Height"])
        assertEquals("0:01:05", video["Duration"])
    }

    @Test
    fun fallsBackToMediaMetadataWhenEntitySparse() {
        val metadata = MediaMetadata(
            mediaId = -1L,
            imageDescription = null,
            dateTimeOriginal = "2024:01:01 00:00:00",
            manufacturerName = "Sony",
            modelName = "A7IV",
            aperture = "f/1.8",
            exposureTime = null,
            iso = "100",
            focalLength = 35.0,
            gpsLatitude = null,
            gpsLongitude = null,
            gpsLocationName = null,
            gpsLocationNameCountry = null,
            gpsLocationNameCity = null,
            imageWidth = 6000,
            imageHeight = 4000,
            imageResolutionX = null,
            imageResolutionY = null,
            resolutionUnit = null,
            durationMs = null,
            videoWidth = null,
            videoHeight = null,
            frameRate = null,
            bitRate = null,
            isNightMode = false,
            isPanorama = false,
            isPhotosphere = false,
            isLongExposure = false,
            isMotionPhoto = false
        )

        val dirs = buildCloudMetadataDirectories(null, metadata)
        val exif = dirs.first { it.name == "Exif" }.tags.associate { it.name to it.description }
        assertEquals("Sony", exif["Make"])
        assertEquals("A7IV", exif["Model"])
        val image = dirs.first { it.name == "Image" }.tags.associate { it.name to it.description }
        assertEquals("6000 pixels", image["Image Width"])
    }

    @Test
    fun prefersEntityFieldsOverMetadata() {
        val entity = CloudMediaEntity(
            remoteId = "x",
            providerType = ProviderType.IMMICH,
            serverConfigId = 2L,
            cameraMake = "FromEntity",
            width = 100,
            height = 100,
            mimeType = "image/jpeg"
        )
        val metadata = MediaMetadata(
            mediaId = -2L,
            imageDescription = null,
            dateTimeOriginal = null,
            manufacturerName = "FromMetadata",
            modelName = null,
            aperture = null,
            exposureTime = null,
            iso = null,
            gpsLatitude = null,
            gpsLongitude = null,
            gpsLocationName = null,
            gpsLocationNameCountry = null,
            gpsLocationNameCity = null,
            imageWidth = 200,
            imageHeight = 200,
            imageResolutionX = null,
            imageResolutionY = null,
            resolutionUnit = null,
            durationMs = null,
            videoWidth = null,
            videoHeight = null,
            frameRate = null,
            bitRate = null,
            isNightMode = false,
            isPanorama = false,
            isPhotosphere = false,
            isLongExposure = false,
            isMotionPhoto = false
        )

        val dirs = buildCloudMetadataDirectories(entity, metadata)
        val exif = dirs.first { it.name == "Exif" }.tags.associate { it.name to it.description }
        assertEquals("FromEntity", exif["Make"])
        val image = dirs.first { it.name == "Image" }.tags.associate { it.name to it.description }
        assertEquals("100 pixels", image["Image Width"])
    }
}
