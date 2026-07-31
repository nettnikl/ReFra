/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.cloud.photoprism.di

import android.content.Context
import com.dot.gallery.cloud.core.MediaCapabilityProvider
import com.dot.gallery.cloud.core.ProviderInstanceFactory
import com.dot.gallery.cloud.core.ProviderType
import com.dot.gallery.cloud.data.dao.CloudAlbumMemberDao
import com.dot.gallery.cloud.data.dao.CloudMediaDao
import com.dot.gallery.cloud.photoprism.PhotoPrismProvider
import com.dot.gallery.cloud.photoprism.data.api.PhotoPrismAuthInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PhotoPrismModule {

    @Provides
    @Singleton
    @IntoSet
    fun providePhotoPrismProviderFactory(
        @ApplicationContext context: Context,
        cloudMediaDao: CloudMediaDao,
        cloudAlbumMemberDao: CloudAlbumMemberDao
    ): ProviderInstanceFactory = object : ProviderInstanceFactory {
        override val providerType = ProviderType.PHOTOPRISM
        override fun create(): MediaCapabilityProvider =
            PhotoPrismProvider(context, PhotoPrismAuthInterceptor(), cloudMediaDao, cloudAlbumMemberDao)
    }
}
