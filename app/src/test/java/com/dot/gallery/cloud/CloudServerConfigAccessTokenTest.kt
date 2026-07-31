/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.cloud

import com.dot.gallery.cloud.core.CloudServerConfig
import com.dot.gallery.cloud.core.ProviderType
import com.dot.gallery.cloud.data.entity.CloudServerConfigEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloudServerConfigAccessTokenTest {

    @Test
    fun entityMapsEncryptedAccessTokenToConfigAccessToken() {
        val entity = CloudServerConfigEntity(
            id = 1L,
            providerType = ProviderType.PHOTOPRISM,
            serverUrl = "https://pp.example",
            encryptedAccessToken = "enc-token",
            username = "user"
        )
        val config = entity.toCloudServerConfig()
        assertEquals("enc-token", config.accessToken)
        assertNull(config.password)
    }

    @Test
    fun fromCloudServerConfigStoresEncryptedAccessTokenSeparately() {
        val config = CloudServerConfig(
            id = 2L,
            providerType = ProviderType.PHOTOPRISM,
            serverUrl = "https://pp.example",
            accessToken = "plain-should-not-be-copied-raw",
            username = "user"
        )
        val entity = CloudServerConfigEntity.fromCloudServerConfig(
            config,
            encryptedPwd = null,
            encryptedAccessToken = "enc-token"
        )
        assertEquals("enc-token", entity.encryptedAccessToken)
        assertNull(entity.encryptedPassword)
        // apiKey path for long-lived tokens remains independent
        assertNull(entity.apiKey)
    }

    @Test
    fun tokenOnlyConfigHasNoPassword() {
        val config = CloudServerConfig(
            id = 3L,
            providerType = ProviderType.PHOTOPRISM,
            serverUrl = "https://pp.example",
            accessToken = "sess",
            username = "oidc-user",
            password = null
        )
        assertEquals("sess", config.accessToken)
        assertNull(config.password)
        assertNull(config.apiKey)
    }
}

