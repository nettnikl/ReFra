/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.cloud

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dot.gallery.cloud.core.CloudServerConfig
import com.dot.gallery.cloud.core.ConnectionState
import com.dot.gallery.cloud.core.ProviderType
import com.dot.gallery.cloud.core.ThumbnailSize
import com.dot.gallery.cloud.data.dao.CloudMediaDao
import com.dot.gallery.cloud.photoprism.PhotoPrismProvider
import com.dot.gallery.cloud.photoprism.data.api.PhotoPrismAuthInterceptor
import com.dot.gallery.core.Resource
import com.dot.gallery.feature_node.data.data_source.InternalDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PhotoPrismProviderMockServerTest {

    private lateinit var server: MockWebServer
    private lateinit var db: InternalDatabase
    private lateinit var dao: CloudMediaDao
    private lateinit var provider: PhotoPrismProvider

    private val photoJson = """
        [{
          "UID": "photo-1",
          "Type": "image",
          "Title": "A",
          "FileName": "a.jpg",
          "Hash": "hash1",
          "Width": 100,
          "Height": 80,
          "TakenAt": "2024-01-15T10:30:00Z",
          "Favorite": true,
          "Files": [{
            "UID": "file-1", "Hash": "hash1", "Name": "a.jpg", "Size": 1234,
            "Primary": true, "Mime": "image/jpeg", "Width": 100, "Height": 80
          }]
        }]
    """.trimIndent()

    private val albumsJson = """
        [{ "UID": "album-1", "Title": "Trip", "PhotoCount": 5, "CreatedAt": "2024-01-01T00:00:00Z" }]
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.getCloudMediaDao()
        provider = PhotoPrismProvider(context, PhotoPrismAuthInterceptor(), dao)
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    private fun dispatcher(block: (RecordedRequest) -> MockResponse?) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
            block(request) ?: MockResponse().setResponseCode(404)
    }

    private fun json(body: String, headers: Map<String, String> = emptyMap()) =
        MockResponse().setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .also { resp -> headers.forEach { (k, v) -> resp.setHeader(k, v) } }
            .setBody(body)

    @Test
    fun authenticateWithStaticTokenSucceeds() = runBlocking {
        server.dispatcher = dispatcher { req ->
            when {
                req.path?.endsWith("/api/v1/config") == true ->
                    json("""{ "version": "240711", "name": "PP", "previewToken": "pt", "downloadToken": "dt" }""")
                else -> null
            }
        }
        val config = CloudServerConfig(
            id = 1, providerType = ProviderType.PHOTOPRISM, serverUrl = baseUrl(), apiKey = "APP-PASS"
        )
        provider.configure(config)

        val result = provider.authenticate(config)

        assertTrue("auth should succeed: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(ConnectionState.CONNECTED, provider.connectionState.value)
        val requests = generateSequence { server.takeRequest(1, TimeUnit.SECONDS) }.toList()
        assertTrue(requests.isNotEmpty())
        assertTrue(
            requests.any {
                it.getHeader("Authorization") == "Bearer APP-PASS" ||
                    it.getHeader("X-Auth-Token") == "APP-PASS"
            }
        )
    }

    @Test
    fun authenticateWithUsernamePasswordUsesSession() = runBlocking {
        server.dispatcher = dispatcher { req ->
            when {
                req.path?.endsWith("/api/v1/session") == true && req.method == "POST" ->
                    json(
                        """{
                          "access_token": "SESS1",
                          "config": { "previewToken": "pt", "downloadToken": "dt", "version": "1" },
                          "user": { "UID": "u1", "Email": "a@b.c", "Admin": true }
                        }"""
                    )
                else -> null
            }
        }
        val config = CloudServerConfig(
            id = 1, providerType = ProviderType.PHOTOPRISM, serverUrl = baseUrl(),
            username = "admin", password = "secret"
        )
        provider.configure(config)

        val result = provider.authenticate(config)

        assertTrue("session should succeed: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals("SESS1", result.getOrNull()?.accessToken)
        assertEquals(ConnectionState.CONNECTED, provider.connectionState.value)
    }

    @Test
    fun authenticateFallsBackToOAuthClientCredentials() = runBlocking {
        server.dispatcher = dispatcher { req ->
            when {
                req.path?.endsWith("/api/v1/session") == true && req.method == "POST" ->
                    MockResponse().setResponseCode(401).setBody("""{"error":"Invalid credentials"}""")
                req.path?.endsWith("/api/v1/oauth/token") == true ->
                    json("""{ "access_token": "OAUTH1", "token_type": "Bearer", "expires_in": 3600 }""")
                req.path?.endsWith("/api/v1/config") == true ->
                    json("""{ "previewToken": "pt", "downloadToken": "dt", "version": "1" }""")
                else -> null
            }
        }
        val config = CloudServerConfig(
            id = 1, providerType = ProviderType.PHOTOPRISM, serverUrl = baseUrl(),
            username = "csce0w2joodmirvi", password = "client-secret"
        )
        provider.configure(config)

        val result = provider.authenticate(config)

        assertTrue("oauth fallback should succeed: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals("OAUTH1", result.getOrNull()?.accessToken)
    }

    @Test
    fun authenticateRejectsTwoFactorWithAppPasswordHint() = runBlocking {
        server.dispatcher = dispatcher { req ->
            when {
                req.path?.endsWith("/api/v1/session") == true ->
                    MockResponse().setResponseCode(401)
                        .setBody("""{"code":32,"error":"Passcode required","messageId":"ErrPasscodeRequired"}""")
                else -> null
            }
        }
        val config = CloudServerConfig(
            id = 1, providerType = ProviderType.PHOTOPRISM, serverUrl = baseUrl(),
            username = "admin", password = "pw"
        )
        provider.configure(config)

        val result = provider.authenticate(config)

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message?.contains("app password", ignoreCase = true) == true
        )
    }

    @Test
    fun fetchAssetsAndAlbums() = runBlocking {
        server.dispatcher = dispatcher { req ->
            when {
                req.path?.endsWith("/api/v1/config") == true ->
                    json("""{ "previewToken": "pt", "downloadToken": "dt", "version": "1" }""")
                req.path?.contains("/api/v1/photos") == true ->
                    json(photoJson, mapOf("X-Preview-Token" to "pt", "X-Download-Token" to "dt"))
                req.path?.contains("/api/v1/albums") == true -> json(albumsJson)
                else -> null
            }
        }
        val config = CloudServerConfig(
            id = 3, providerType = ProviderType.PHOTOPRISM, serverUrl = baseUrl(), apiKey = "KEY"
        )
        provider.configure(config)
        assertTrue(provider.authenticate(config).isSuccess)

        val assets = provider.getRemoteAssets(0, 50).first()
        assertTrue(assets is Resource.Success)
        val list = (assets as Resource.Success).data!!
        assertEquals(1, list.size)
        assertEquals("photo-1", list[0].remoteId)
        assertEquals("hash1", list[0].fileId)
        assertTrue(list[0].favorite)

        val thumb = provider.getThumbnailUrl("photo-1", ThumbnailSize.PREVIEW, "hash1")
        assertTrue(thumb.contains("/api/v1/t/hash1/pt/fit_720"))

        val albums = provider.getRemoteAlbums().first()
        assertTrue(albums is Resource.Success)
        assertEquals("Trip", (albums as Resource.Success).data!![0].name)

        val albumRequests = generateSequence { server.takeRequest(1, TimeUnit.SECONDS) }.toList()
            .filter { it.path?.contains("/api/v1/albums") == true }
        assertTrue(albumRequests.isNotEmpty())
        assertTrue(
            "default albums fetch should request type=album",
            albumRequests.any { it.requestUrl?.queryParameter("type") == "album" }
        )
        assertTrue(
            "month albums must not be fetched when setting is off",
            albumRequests.none { it.requestUrl?.queryParameter("type") == "month" }
        )
    }

    @Test
    fun getRemoteAlbumsIncludesMonthAlbumsWhenEnabled() = runBlocking {
        val monthAlbumsJson = """
            [{ "UID": "month-1", "Title": "January 2024", "PhotoCount": 2, "CreatedAt": "2024-01-01T00:00:00Z" }]
        """.trimIndent()
        server.dispatcher = dispatcher { req ->
            when {
                req.path?.endsWith("/api/v1/config") == true ->
                    json("""{ "previewToken": "pt", "downloadToken": "dt", "version": "1" }""")
                req.path?.contains("/api/v1/albums") == true -> {
                    val type = req.requestUrl?.queryParameter("type")
                    when (type) {
                        "album", null -> json(albumsJson)
                        "month" -> json(monthAlbumsJson)
                        else -> MockResponse().setResponseCode(400)
                    }
                }
                else -> null
            }
        }
        val config = CloudServerConfig(
            id = 5,
            providerType = ProviderType.PHOTOPRISM,
            serverUrl = baseUrl(),
            apiKey = "KEY",
            includeMonthAlbums = true
        )
        provider.configure(config)
        assertTrue(provider.authenticate(config).isSuccess)

        val albums = provider.getRemoteAlbums().first()
        assertTrue(albums is Resource.Success)
        val names = (albums as Resource.Success).data!!.map { it.name }
        assertEquals(listOf("Trip", "January 2024"), names)

        val albumRequests = generateSequence { server.takeRequest(1, TimeUnit.SECONDS) }.toList()
            .filter { it.path?.contains("/api/v1/albums") == true }
        assertTrue(albumRequests.any { it.requestUrl?.queryParameter("type") == "album" })
        assertTrue(albumRequests.any { it.requestUrl?.queryParameter("type") == "month" })
    }

    @Test
    fun toggleFavoriteLikeAndUnlikeUpdatesRoom() = runBlocking {
        server.dispatcher = dispatcher { req ->
            when {
                req.path?.endsWith("/api/v1/config") == true ->
                    json("""{ "previewToken": "pt", "downloadToken": "dt", "version": "1" }""")
                req.path?.contains("/api/v1/photos") == true && req.method == "GET" ->
                    json(photoJson, mapOf("X-Preview-Token" to "pt", "X-Download-Token" to "dt"))
                req.path?.endsWith("/api/v1/photos/photo-1/like") == true && req.method == "POST" ->
                    MockResponse().setResponseCode(200)
                req.path?.endsWith("/api/v1/photos/photo-1/like") == true && req.method == "DELETE" ->
                    MockResponse().setResponseCode(200)
                else -> null
            }
        }
        val config = CloudServerConfig(
            id = 5, providerType = ProviderType.PHOTOPRISM, serverUrl = baseUrl(), apiKey = "KEY"
        )
        provider.configure(config)
        assertTrue(provider.authenticate(config).isSuccess)

        // Seed Room from a fetch so updateFavorite has a row to update.
        val assets = provider.getRemoteAssets(0, 10).first()
        assertTrue(assets is Resource.Success)
        dao.insertAll((assets as Resource.Success).data!!)

        assertTrue(provider.toggleFavorite("photo-1", true).isSuccess)
        assertTrue(dao.getFavoritesAsync().any { it.remoteId == "photo-1" && it.favorite })

        assertTrue(provider.toggleFavorite("photo-1", false).isSuccess)
        assertTrue(dao.getFavoritesAsync().none { it.remoteId == "photo-1" })
    }

    @Test
    fun toggleFavoriteFailureDoesNotFlipRoom() = runBlocking {
        server.dispatcher = dispatcher { req ->
            when {
                req.path?.endsWith("/api/v1/config") == true ->
                    json("""{ "previewToken": "pt", "downloadToken": "dt", "version": "1" }""")
                req.path?.contains("/api/v1/photos") == true && req.method == "GET" ->
                    json(photoJson)
                req.path?.endsWith("/api/v1/photos/photo-1/like") == true ->
                    MockResponse().setResponseCode(403)
                else -> null
            }
        }
        val config = CloudServerConfig(
            id = 6, providerType = ProviderType.PHOTOPRISM, serverUrl = baseUrl(), apiKey = "KEY"
        )
        provider.configure(config)
        assertTrue(provider.authenticate(config).isSuccess)
        val assets = (provider.getRemoteAssets(0, 10).first() as Resource.Success).data!!
        // Seed as not favorite so a failed like must not mark it favorite.
        dao.insertAll(assets.map { it.copy(favorite = false) })

        assertTrue(provider.toggleFavorite("photo-1", true).isFailure)
        assertTrue(dao.getFavoritesAsync().none { it.remoteId == "photo-1" })
    }

    @Test
    fun sessionReauthOn401() = runBlocking {
        var photosHits = 0
        server.dispatcher = dispatcher { req ->
            when {
                req.path?.endsWith("/api/v1/session") == true && req.method == "POST" ->
                    json(
                        """{
                          "access_token": "SESS-NEW",
                          "config": { "previewToken": "pt", "downloadToken": "dt" }
                        }"""
                    )
                req.path?.contains("/api/v1/photos") == true -> {
                    photosHits++
                    if (photosHits == 1) {
                        MockResponse().setResponseCode(401).setBody("""{"error":"Unauthorized"}""")
                    } else {
                        json(photoJson, mapOf("X-Preview-Token" to "pt", "X-Download-Token" to "dt"))
                    }
                }
                else -> null
            }
        }
        val config = CloudServerConfig(
            id = 4, providerType = ProviderType.PHOTOPRISM, serverUrl = baseUrl(),
            username = "admin", password = "secret"
        )
        provider.configure(config)
        assertTrue(provider.authenticate(config).isSuccess)

        // Drain session login request(s) already taken during authenticate.
        while (server.takeRequest(100, TimeUnit.MILLISECONDS) != null) { /* drain */ }

        val assets = provider.getRemoteAssets(0, 10).first()
        assertTrue(
            "expected success after reauth, got ${assets}: hits=$photosHits",
            assets is Resource.Success
        )
        assertTrue("photos endpoint should be hit at least twice (401 then retry)", photosHits >= 2)
    }

    @Test
    fun capabilitiesIncludeMap() {
        val caps = provider.capabilities.map { it.name }.toSet()
        assertTrue(caps.containsAll(setOf(
            "REMOTE_ASSETS", "REMOTE_ALBUMS", "FAVORITE", "TEXT_SEARCH", "MAP"
        )))
    }

    @Test
    fun getMapMarkersFromGeoJson() = runBlocking {
        val geoJson = """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "type": "Feature",
                  "geometry": { "type": "Point", "coordinates": [13.405, 52.52] },
                  "properties": { "UID": "photo-geo-1", "Hash": "h1", "Title": "Berlin" }
                },
                {
                  "type": "Feature",
                  "geometry": { "type": "Point", "coordinates": [2.3522, 48.8566] },
                  "properties": { "UID": "photo-geo-2", "Title": "Paris" }
                }
              ]
            }
        """.trimIndent()
        server.dispatcher = dispatcher { req ->
            when {
                req.path?.endsWith("/api/v1/config") == true ->
                    json("""{ "previewToken": "pt", "downloadToken": "dt", "version": "1" }""")
                req.path?.contains("/api/v1/geo") == true -> json(geoJson)
                else -> null
            }
        }
        val config = CloudServerConfig(
            id = 5, providerType = ProviderType.PHOTOPRISM, serverUrl = baseUrl(), apiKey = "KEY"
        )
        provider.configure(config)
        assertTrue(provider.authenticate(config).isSuccess)

        val result = provider.getMapMarkers().first()
        assertTrue(result is Resource.Success)
        val markers = (result as Resource.Success).data!!
        assertEquals(2, markers.size)
        assertEquals("photo-geo-1", markers[0].assetId)
        assertEquals(52.52, markers[0].latitude, 0.0001)
        assertEquals(13.405, markers[0].longitude, 0.0001)
        assertEquals(ProviderType.PHOTOPRISM, markers[0].providerType)
        assertEquals("photo-geo-2", markers[1].assetId)
        assertEquals(48.8566, markers[1].latitude, 0.0001)
    }

    @Test
    fun getMapMarkersFallsBackToGeotaggedPhotos() = runBlocking {
        val photosWithGeo = """
            [{
              "UID": "photo-latlng",
              "Type": "image",
              "Title": "Somewhere",
              "Hash": "hashg",
              "Lat": 40.7128,
              "Lng": -74.006,
              "PlaceCity": "New York",
              "PlaceCountry": "US",
              "Files": [{
                "UID": "f1", "Hash": "hashg", "Name": "a.jpg", "Size": 1,
                "Primary": true, "Mime": "image/jpeg"
              }]
            }]
        """.trimIndent()
        server.dispatcher = dispatcher { req ->
            when {
                req.path?.endsWith("/api/v1/config") == true ->
                    json("""{ "previewToken": "pt", "downloadToken": "dt", "version": "1" }""")
                req.path?.contains("/api/v1/geo") == true ->
                    MockResponse().setResponseCode(404)
                req.path?.contains("/api/v1/photos") == true -> json(photosWithGeo)
                else -> null
            }
        }
        val config = CloudServerConfig(
            id = 6, providerType = ProviderType.PHOTOPRISM, serverUrl = baseUrl(), apiKey = "KEY"
        )
        provider.configure(config)
        assertTrue(provider.authenticate(config).isSuccess)

        val result = provider.getMapMarkers().first()
        assertTrue("expected success, got $result", result is Resource.Success)
        val markers = (result as Resource.Success).data!!
        assertEquals(1, markers.size)
        assertEquals("photo-latlng", markers[0].assetId)
        assertEquals(40.7128, markers[0].latitude, 0.0001)
        assertEquals(-74.006, markers[0].longitude, 0.0001)
        assertEquals("New York", markers[0].city)
        assertEquals("US", markers[0].country)
    }
}
