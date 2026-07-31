/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.presentation.exif

import android.content.ContentUris
import android.content.Context
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dot.gallery.cloud.core.CloudUri
import com.dot.gallery.cloud.core.cloudMediaId
import com.dot.gallery.cloud.data.dao.CloudMediaDao
import com.dot.gallery.core.Settings
import com.dot.gallery.core.sandbox.IsolatedMetadataParser
import com.dot.gallery.feature_node.data.data_source.InternalDatabase
import com.dot.gallery.feature_node.domain.model.toMediaMetadata
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MetadataDirectory(
    val name: String,
    val tags: List<MetadataTag>
)

data class MetadataTag(
    val name: String,
    val description: String
)

data class MetadataViewState(
    val isLoading: Boolean = true,
    val directories: List<MetadataDirectory> = emptyList()
)

@HiltViewModel
class MetadataViewViewModel @Inject constructor(
    private val isolatedParser: IsolatedMetadataParser,
    private val cloudMediaDao: CloudMediaDao,
    private val database: InternalDatabase,
    @param:ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _state = MutableStateFlow(MetadataViewState())
    val state: StateFlow<MetadataViewState> = _state

    fun loadMetadata(mediaUri: String, isVideo: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = MetadataViewState(isLoading = true)
            val uri = mediaUri.toUri()
            val directories = if (uri.scheme == CloudUri.SCHEME) {
                loadCloudDirectories(mediaUri)
            } else {
                runCatching {
                    val mode = Settings.Security.getMetadataIsolationMode(appContext)
                        .firstOrNull() ?: Settings.Security.METADATA_ISOLATION_SHARED
                    val usePerFile = mode != Settings.Security.METADATA_ISOLATION_SHARED
                    if (usePerFile) {
                        val mediaId = ContentUris.parseId(uri)
                        isolatedParser.parseRawMetadataPerFile(uri, isVideo, mediaId)
                    } else {
                        isolatedParser.parseRawMetadata(uri, isVideo)
                    }
                }.getOrElse { emptyList() }
            }
            _state.value = MetadataViewState(
                isLoading = false,
                directories = directories
            )
        }
    }

    /**
     * Cloud media has no seekable FD for metadata-extractor. Prefer Room fields already
     * synced from PhotoPrism/Immich (entity EXIF + collected [MediaMetadata]).
     */
    private suspend fun loadCloudDirectories(mediaUri: String): List<MetadataDirectory> {
        val cloudUri = CloudUri.parse(mediaUri) ?: return emptyList()
        val entity = if (cloudUri.configId > 0L) {
            cloudMediaDao.getByRemoteIdAndConfig(
                cloudUri.remoteId,
                cloudUri.providerType,
                cloudUri.configId
            ) ?: cloudMediaDao.getByRemoteId(cloudUri.remoteId, cloudUri.providerType)
        } else {
            cloudMediaDao.getByRemoteId(cloudUri.remoteId, cloudUri.providerType)
        }

        val mediaId = when {
            entity != null -> cloudMediaId(
                entity.providerType,
                entity.serverConfigId,
                entity.remoteId
            )
            cloudUri.configId > 0L -> cloudMediaId(
                cloudUri.providerType,
                cloudUri.configId,
                cloudUri.remoteId
            )
            else -> null
        }

        val metadata = mediaId?.let { id ->
            database.getMetadataDao().getFullMetadataOnce(id)?.toMediaMetadata()
        }

        return buildCloudMetadataDirectories(entity, metadata)
    }
}
