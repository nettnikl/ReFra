/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.cloud.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dot.gallery.cloud.core.ProviderRegistry
import com.dot.gallery.cloud.core.ThumbnailSize
import com.dot.gallery.cloud.core.capabilities.RemoteMediaProvider
import com.dot.gallery.cloud.data.dao.CloudMediaDao
import com.dot.gallery.cloud.image.CloudFetcherRegistryHolder
import com.dot.gallery.cloud.offline.CloudMediaCache
import com.dot.gallery.cloud.offline.OfflineModeManager
import com.dot.gallery.feature_node.presentation.util.printDebug
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import okhttp3.Request

/**
 * Pins full-resolution originals for every cloud favorite when the user enables
 * [OfflineModeManager.downloadFavoritesFullRes]. Complements [CloudOfflineDownloadWorker]
 * (thumb + preview for whole accounts) with original-sized favorites only.
 */
@HiltWorker
class CloudFavoriteOfflineWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val registry: ProviderRegistry,
    private val cloudMediaDao: CloudMediaDao,
    private val cache: CloudMediaCache,
    private val offlineModeManager: OfflineModeManager
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!offlineModeManager.downloadFavoritesFullResNow) return Result.success()

        val favorites = cloudMediaDao.getFavoritesAsync()
        if (favorites.isEmpty()) return Result.success()

        val client = CloudFetcherRegistryHolder.okHttpClient ?: return Result.retry()
        val total = favorites.size
        var done = 0
        var failures = 0

        for (asset in favorites) {
            val key = cache.keyFor(asset.providerType, asset.serverConfigId, asset.remoteId, SIZE_ORIGINAL)
            if (!cache.isPinned(key)) {
                val provider = registry.getByConfigId(asset.serverConfigId) as? RemoteMediaProvider
                    ?: registry.get(asset.providerType) as? RemoteMediaProvider
                if (provider == null) {
                    failures++
                } else {
                    // Seed PhotoPrism's in-memory hash map from Room's fileId when needed.
                    if (asset.fileId.isNotBlank()) {
                        provider.getThumbnailUrl(asset.remoteId, ThumbnailSize.THUMBNAIL, asset.fileId)
                    }
                    val url = provider.getOriginalUrl(asset.remoteId)
                        .ifBlank { asset.originalUrl }
                    val ok = downloadOriginal(url, key, provider.getAuthHeaders(), client)
                    if (!ok) failures++
                }
            }
            done++
            setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
        }

        printDebug("CloudFavoriteOfflineWorker: pinned originals $done/$total (failures=$failures)")
        return if (failures > 0 && done == failures) Result.retry() else Result.success()
    }

    private fun downloadOriginal(
        url: String,
        key: String,
        authHeaders: Map<String, String>,
        client: okhttp3.OkHttpClient
    ): Boolean = runCatching {
        if (!url.startsWith("http", ignoreCase = true)) return false
        val builder = Request.Builder().url(url)
        authHeaders.forEach { (k, v) -> builder.addHeader(k, v) }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) return false
            val body = response.body
            val bytes = body.bytes()
            if (bytes.isEmpty()) return false
            cache.storePinned(key, bytes, body.contentType()?.toString())
        }
    }.onFailure {
        printDebug("CloudFavoriteOfflineWorker: failed $url: ${it.message}")
    }.isSuccess

    companion object {
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val WORK_NAME = "cloud_favorite_offline_download"
        const val SIZE_ORIGINAL = "original"

        fun triggerNow(workManager: WorkManager, wifiOnly: Boolean) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<CloudFavoriteOfflineWorker>()
                .setConstraints(constraints)
                .build()
            workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
