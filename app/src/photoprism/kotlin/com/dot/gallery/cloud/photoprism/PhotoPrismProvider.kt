/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.cloud.photoprism

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.dot.gallery.cloud.core.CloudAlbum
import com.dot.gallery.cloud.core.CloudAuthToken
import com.dot.gallery.cloud.core.CloudServerConfig
import com.dot.gallery.cloud.core.CloudServerInfo
import com.dot.gallery.cloud.core.CloudStorageInfo
import com.dot.gallery.cloud.core.ConnectionState
import com.dot.gallery.cloud.core.Disconnectable
import com.dot.gallery.cloud.core.ProviderCapability
import com.dot.gallery.cloud.core.ProviderType
import com.dot.gallery.cloud.core.SyncState
import com.dot.gallery.cloud.core.ThumbnailSize
import com.dot.gallery.cloud.core.capabilities.RemoteMediaProvider
import com.dot.gallery.cloud.core.capabilities.SyncCapableProvider
import com.dot.gallery.cloud.data.dao.CloudMediaDao
import com.dot.gallery.cloud.data.entity.CloudMediaEntity
import com.dot.gallery.cloud.image.CloudFetcherRegistryHolder
import com.dot.gallery.cloud.network.LanBindingSocketFactory
import com.dot.gallery.cloud.photoprism.data.api.PhotoPrismApiService
import com.dot.gallery.cloud.photoprism.data.api.PhotoPrismAuthInterceptor
import com.dot.gallery.cloud.photoprism.data.api.PhotoPrismAuthMode
import com.dot.gallery.cloud.photoprism.data.api.PhotoPrismAuthenticator
import com.dot.gallery.cloud.photoprism.data.dto.PhotoPrismImportOptionsDto
import com.dot.gallery.cloud.photoprism.data.dto.PhotoPrismPhotoDto
import com.dot.gallery.cloud.photoprism.data.dto.PhotoPrismUploadOptionsDto
import com.dot.gallery.core.Resource
import com.dot.gallery.feature_node.domain.model.Media
import com.dot.gallery.feature_node.domain.util.getUri
import com.dot.gallery.feature_node.presentation.util.printDebug
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

class PhotoPrismProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val authInterceptor: PhotoPrismAuthInterceptor,
    private val cloudMediaDao: CloudMediaDao
) : RemoteMediaProvider, SyncCapableProvider, Disconnectable {

    override val providerType = ProviderType.PHOTOPRISM
    override val displayName = "PhotoPrism"

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var currentConfig: CloudServerConfig? = null
    private var baseUrl: String = ""
    private var apiService: PhotoPrismApiService? = null
    private val hashByRemoteId = ConcurrentHashMap<String, String>()

    /** PhotoPrism user UID required by the upload API path. */
    @Volatile
    private var userUid: String? = null

    /**
     * Client-side token that groups a backup-worker batch of multipart uploads.
     * PhotoPrism stages files under users/{uid}/upload/{sessionRef+token}.
     */
    @Volatile
    private var uploadBatchToken: String? = null

    private val pendingUploadCount = AtomicInteger(0)

    private val lanSocketFactory = LanBindingSocketFactory(context)

    override val isAvailable: Boolean
        get() = currentConfig != null && _connectionState.value == ConnectionState.CONNECTED

    override val capabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.REMOTE_ASSETS,
        ProviderCapability.REMOTE_ALBUMS,
        ProviderCapability.FAVORITE,
        ProviderCapability.TEXT_SEARCH,
        ProviderCapability.SYNC
    )

    override fun disconnect() {
        _connectionState.value = ConnectionState.DISCONNECTED
        currentConfig = null
        apiService = null
        authInterceptor.clear()
        hashByRemoteId.clear()
        baseUrl = ""
        userUid = null
        uploadBatchToken = null
        pendingUploadCount.set(0)
    }

    override fun configure(config: CloudServerConfig) {
        currentConfig = config
        baseUrl = config.serverUrl.trimEnd('/')
        authInterceptor.baseUrl = baseUrl
        authInterceptor.username = config.username
        authInterceptor.password = config.password
        apiService = createApiService(baseUrl)
        printDebug("PhotoPrismProvider: Configured with server ${config.serverUrl}")
    }

    private fun createApiService(serverUrl: String): PhotoPrismApiService {
        val url = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .authenticator(PhotoPrismAuthenticator(authInterceptor))
            .addInterceptor(logging)
            .socketFactory(lanSocketFactory)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(url)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PhotoPrismApiService::class.java)
    }

    private fun requireApi(): PhotoPrismApiService = apiService
        ?: throw IllegalStateException("PhotoPrismProvider not configured. Call configure() first.")

    private fun createIsolatedApiService(
        serverUrl: String,
        token: String? = null
    ): PhotoPrismApiService {
        val url = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val tempInterceptor = PhotoPrismAuthInterceptor().apply {
            accessToken = token
            baseUrl = serverUrl.trimEnd('/')
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(tempInterceptor)
            .socketFactory(lanSocketFactory)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl(url)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PhotoPrismApiService::class.java)
    }

    private fun applyConfigTokens(preview: String?, download: String?) {
        if (!preview.isNullOrBlank()) authInterceptor.previewToken = preview
        if (!download.isNullOrBlank()) authInterceptor.downloadToken = download
    }

    private fun rememberHashes(entities: List<CloudMediaEntity>) {
        for (entity in entities) {
            if (entity.fileId.isNotBlank()) {
                hashByRemoteId[entity.remoteId] = entity.fileId
            }
        }
    }

    private fun mapPhotos(photos: List<PhotoPrismPhotoDto>): List<CloudMediaEntity> {
        val configId = currentConfig?.id ?: 0L
        val entities = photos.map {
            it.toCloudMediaEntity(
                serverConfigId = configId,
                baseUrl = baseUrl,
                previewToken = authInterceptor.previewToken,
                downloadToken = authInterceptor.downloadToken
            )
        }
        rememberHashes(entities)
        return entities
    }

    private fun captureTokensFromHeaders(headers: okhttp3.Headers) {
        headers["X-Preview-Token"]?.takeIf { it.isNotBlank() }?.let {
            authInterceptor.previewToken = it
        }
        headers["X-Download-Token"]?.takeIf { it.isNotBlank() }?.let {
            authInterceptor.downloadToken = it
        }
    }

    // === Auth ===

    override suspend fun testConnection(config: CloudServerConfig): Result<CloudServerInfo> {
        return try {
            val tempUrl = config.serverUrl.trimEnd('/')
            val auth = obtainAccess(tempUrl, config.apiKey, config.username, config.password)
                .getOrElse { return Result.failure(it) }
            val tempApi = createIsolatedApiService(tempUrl, auth.accessToken)
            val configResp = tempApi.getConfig()
            if (configResp.isSuccessful) {
                val body = configResp.body()
                Result.success(
                    CloudServerInfo(
                        version = body?.version.orEmpty().ifBlank { "PhotoPrism" },
                        serverName = body?.name?.takeIf { it.isNotBlank() }
                            ?: "PhotoPrism ${body?.version.orEmpty()}".trim()
                    )
                )
            } else {
                Result.failure(Exception("Connection failed: ${configResp.code()} ${configResp.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun authenticate(config: CloudServerConfig): Result<CloudAuthToken> {
        return try {
            val auth = obtainAccess(baseUrl, config.apiKey, config.username, config.password)
                .getOrElse {
                    _connectionState.value = ConnectionState.ERROR
                    return Result.failure(it)
                }
            authInterceptor.accessToken = auth.accessToken
            authInterceptor.authMode = auth.mode
            authInterceptor.username = config.username
            authInterceptor.password = config.password
            applyConfigTokens(auth.previewToken, auth.downloadToken)
            userUid = auth.userId?.takeIf { it.isNotBlank() }

            // Ensure preview/download tokens exist (static token path may only get them from /config).
            if (authInterceptor.previewToken.isNullOrBlank() || authInterceptor.downloadToken.isNullOrBlank()) {
                val cfg = requireApi().getConfig()
                if (cfg.isSuccessful) {
                    applyConfigTokens(cfg.body()?.previewToken, cfg.body()?.downloadToken)
                }
            }

            // Upload API needs the user UID; static app passwords often omit it on /config.
            if (userUid.isNullOrBlank()) {
                val session = requireApi().getSession()
                if (session.isSuccessful) {
                    userUid = session.body()?.user?.uid?.takeIf { it.isNotBlank() }
                }
            }

            // Warm hash cache from Room so originals/thumbs work after process restart
            // before the next full asset prefetch completes.
            val configId = config.id
            cloudMediaDao.getAllCachedAsync()
                .asSequence()
                .filter {
                    it.providerType == ProviderType.PHOTOPRISM &&
                        it.serverConfigId == configId &&
                        it.fileId.isNotBlank()
                }
                .forEach { hashByRemoteId[it.remoteId] = it.fileId }

            _connectionState.value = ConnectionState.CONNECTED
            Result.success(
                CloudAuthToken(
                    accessToken = auth.accessToken,
                    userId = userUid ?: auth.userId,
                    userEmail = auth.userEmail,
                    isAdmin = auth.isAdmin
                )
            )
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.ERROR
            Result.failure(e)
        }
    }

    private data class ObtainedAuth(
        val accessToken: String,
        val mode: PhotoPrismAuthMode,
        val previewToken: String? = null,
        val downloadToken: String? = null,
        val userId: String? = null,
        val userEmail: String? = null,
        val isAdmin: Boolean = false
    )

    private suspend fun obtainAccess(
        serverUrl: String,
        apiKey: String?,
        username: String?,
        password: String?
    ): Result<ObtainedAuth> {
        if (!apiKey.isNullOrBlank()) {
            val tempApi = createIsolatedApiService(serverUrl, apiKey)
            val cfg = tempApi.getConfig()
            return if (cfg.isSuccessful) {
                Result.success(
                    ObtainedAuth(
                        accessToken = apiKey,
                        mode = PhotoPrismAuthMode.STATIC_TOKEN,
                        previewToken = cfg.body()?.previewToken,
                        downloadToken = cfg.body()?.downloadToken
                    )
                )
            } else {
                Result.failure(Exception("Invalid app password / access token: ${cfg.code()}"))
            }
        }

        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            return Result.failure(Exception("No credentials provided"))
        }

        val unauthenticated = createIsolatedApiService(serverUrl, token = null)
        val sessionBody = JSONObject()
            .put("username", username)
            .put("password", password)
            .toString()
            .toRequestBody("application/json".toMediaType())
        val sessionResp = unauthenticated.createSession(sessionBody)
        if (sessionResp.isSuccessful) {
            val body = sessionResp.body()
            if (body?.requiresPasscode() == true) {
                return Result.failure(
                    Exception(
                        "Two-factor authentication is required. Create an app password in " +
                            "PhotoPrism Settings → Account → Apps and Devices and use that instead."
                    )
                )
            }
            val token = body?.resolvedAccessToken()
            if (!token.isNullOrBlank()) {
                return Result.success(
                    ObtainedAuth(
                        accessToken = token,
                        mode = PhotoPrismAuthMode.SESSION,
                        previewToken = body.config?.previewToken,
                        downloadToken = body.config?.downloadToken,
                        userId = body.user?.uid,
                        userEmail = body.user?.email,
                        isAdmin = body.user?.admin == true
                    )
                )
            }
        } else {
            val err = runCatching { sessionResp.errorBody()?.string() }.getOrNull().orEmpty()
            if (err.contains("\"code\":32") || err.contains("passcode", ignoreCase = true)) {
                return Result.failure(
                    Exception(
                        "Two-factor authentication is required. Create an app password in " +
                            "PhotoPrism Settings → Account → Apps and Devices and use that instead."
                    )
                )
            }
            printDebug("PhotoPrismProvider: session failed ${sessionResp.code()} — $err")
        }

        // Fall back to OAuth2 client credentials (username=client_id, password=client_secret).
        val oauth = unauthenticated.oauthToken(
            grantType = "client_credentials",
            clientId = username,
            clientSecret = password
        )
        if (oauth.isSuccessful) {
            val token = oauth.body()?.accessToken
            if (!token.isNullOrBlank()) {
                val cfgApi = createIsolatedApiService(serverUrl, token)
                val cfg = cfgApi.getConfig()
                return Result.success(
                    ObtainedAuth(
                        accessToken = token,
                        mode = PhotoPrismAuthMode.OAUTH_CLIENT,
                        previewToken = cfg.body()?.previewToken,
                        downloadToken = cfg.body()?.downloadToken
                    )
                )
            }
        }

        val oauthErr = runCatching { oauth.errorBody()?.string() }.getOrNull()
        return Result.failure(
            Exception(
                "Authentication failed (session ${sessionResp.code()}, oauth ${oauth.code()})" +
                    (oauthErr?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
            )
        )
    }

    // === Remote Assets ===

    override fun getRemoteAssets(page: Int, pageSize: Int): Flow<Resource<List<CloudMediaEntity>>> = flow {
        try {
            val response = requireApi().getPhotos(
                count = pageSize,
                offset = page * pageSize
            )
            if (response.isSuccessful) {
                captureTokensFromHeaders(response.headers())
                val entities = mapPhotos(response.body().orEmpty())
                cloudMediaDao.insertAll(entities)
                emit(Resource.Success(entities))
            } else {
                emit(Resource.Error("Failed to fetch assets: ${response.code()}"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(Resource.Error(e.message ?: "Unknown error"))
        }
    }

    override fun getRemoteFavorites(): Flow<Resource<List<CloudMediaEntity>>> = flow {
        try {
            val response = requireApi().getPhotos(
                count = 1000,
                offset = 0,
                favorite = true
            )
            if (response.isSuccessful) {
                captureTokensFromHeaders(response.headers())
                emit(Resource.Success(mapPhotos(response.body().orEmpty())))
            } else {
                emit(Resource.Error("Failed to fetch favorites: ${response.code()}"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(Resource.Error(e.message ?: "Unknown error"))
        }
    }

    override fun getRemoteTrashed(): Flow<Resource<List<CloudMediaEntity>>> = flow {
        emit(Resource.Success(emptyList()))
    }

    override fun getRemoteArchived(): Flow<Resource<List<CloudMediaEntity>>> = flow {
        emit(Resource.Success(emptyList()))
    }

    // === Albums ===

    override fun getRemoteAlbums(): Flow<Resource<List<CloudAlbum>>> = flow {
        try {
            val configId = currentConfig?.id ?: 0L
            val response = requireApi().getAlbums()
            if (response.isSuccessful) {
                val albums = response.body()?.map { dto ->
                    CloudAlbum(
                        remoteId = dto.uid,
                        providerType = ProviderType.PHOTOPRISM,
                        serverConfigId = configId,
                        name = dto.title,
                        assetCount = dto.photoCount,
                        isShared = dto.linkCount > 0,
                        createdAt = PhotoPrismPhotoDto.parseIsoTimestamp(dto.createdAt ?: ""),
                        updatedAt = PhotoPrismPhotoDto.parseIsoTimestamp(dto.updatedAt ?: "")
                    )
                } ?: emptyList()
                emit(Resource.Success(albums))
            } else {
                emit(Resource.Error("Failed to fetch albums: ${response.code()}"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(Resource.Error(e.message ?: "Unknown error"))
        }
    }

    override fun getRemoteAlbumMedia(albumId: String): Flow<Resource<List<CloudMediaEntity>>> = flow {
        try {
            val response = requireApi().getPhotos(
                count = 1000,
                offset = 0,
                albumUid = albumId
            )
            if (response.isSuccessful) {
                captureTokensFromHeaders(response.headers())
                val entities = mapPhotos(response.body().orEmpty())
                cloudMediaDao.insertAll(entities)
                emit(Resource.Success(entities))
            } else {
                emit(Resource.Error("Failed to fetch album media: ${response.code()}"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(Resource.Error(e.message ?: "Unknown error"))
        }
    }

    override suspend fun createAlbum(name: String): Result<CloudAlbum> {
        return try {
            val configId = currentConfig?.id ?: 0L
            val response = requireApi().createAlbum(mapOf("Title" to name))
            if (response.isSuccessful) {
                val dto = response.body()!!
                Result.success(
                    CloudAlbum(
                        remoteId = dto.uid,
                        providerType = ProviderType.PHOTOPRISM,
                        serverConfigId = configId,
                        name = dto.title.ifBlank { name },
                        assetCount = 0
                    )
                )
            } else {
                Result.failure(Exception("Failed to create album: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun addToAlbum(albumId: String, assetIds: List<String>): Result<Unit> {
        return try {
            val response = requireApi().addPhotosToAlbum(albumId, mapOf("photos" to assetIds))
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(Exception("Failed to add to album: ${response.code()}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun toggleFavorite(remoteId: String, favorite: Boolean): Result<Unit> {
        return try {
            val response = if (favorite) {
                requireApi().likePhoto(remoteId)
            } else {
                requireApi().unlikePhoto(remoteId)
            }
            if (response.isSuccessful) {
                cloudMediaDao.updateFavorite(remoteId, ProviderType.PHOTOPRISM, favorite)
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to toggle favorite: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun toggleArchive(remoteId: String, archived: Boolean): Result<Unit> =
        Result.failure(UnsupportedOperationException("PhotoPrism archive/private is not in MVP"))

    override suspend fun trashAsset(remoteId: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("PhotoPrism trash is not in MVP"))

    override suspend fun restoreAsset(remoteId: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("PhotoPrism trash is not in MVP"))

    override suspend fun deleteAsset(remoteId: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("PhotoPrism delete is not in MVP"))

    override suspend fun emptyTrash(): Result<Unit> =
        Result.failure(UnsupportedOperationException("PhotoPrism trash is not in MVP"))

    override suspend fun restoreAllTrash(): Result<Unit> =
        Result.failure(UnsupportedOperationException("PhotoPrism trash is not in MVP"))

    override suspend fun search(query: String): Result<List<CloudMediaEntity>> {
        return try {
            val response = requireApi().getPhotos(
                count = 100,
                offset = 0,
                query = query
            )
            if (response.isSuccessful) {
                captureTokensFromHeaders(response.headers())
                Result.success(mapPhotos(response.body().orEmpty()))
            } else {
                Result.failure(Exception("Search failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getThumbnailUrl(remoteId: String, size: ThumbnailSize): String =
        getThumbnailUrl(remoteId, size, hashByRemoteId[remoteId])

    override fun getThumbnailUrl(remoteId: String, size: ThumbnailSize, fileId: String?): String {
        val hash = fileId?.takeIf { it.isNotBlank() }?.also { hashByRemoteId[remoteId] = it }
            ?: hashByRemoteId[remoteId]
            ?: return ""
        val token = authInterceptor.previewToken ?: return ""
        val thumb = when (size) {
            ThumbnailSize.THUMBNAIL -> "tile_224"
            ThumbnailSize.PREVIEW -> "fit_720"
        }
        return "$baseUrl/api/v1/t/$hash/$token/$thumb"
    }

    override fun getOriginalUrl(remoteId: String): String {
        val hash = hashByRemoteId[remoteId] ?: return ""
        val token = authInterceptor.downloadToken ?: return ""
        return "$baseUrl/api/v1/dl/$hash?t=$token"
    }

    override fun getAuthHeaders(): Map<String, String> = buildMap {
        authInterceptor.accessToken?.let { token ->
            put("Authorization", "Bearer $token")
            put("X-Auth-Token", token)
        }
    }

    override suspend fun getServerVersion(): Result<String> = try {
        val response = requireApi().getConfig()
        if (response.isSuccessful) {
            Result.success(response.body()?.version.orEmpty().ifBlank { "unknown" })
        } else {
            Result.failure(Exception("Failed to get server version: ${response.code()}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun getStorageInfo(): Result<CloudStorageInfo> =
        Result.failure(UnsupportedOperationException("PhotoPrism does not expose storage quota via this API"))

    // === Sync / Upload ===

    private fun ensureUploadBatchToken(): String {
        uploadBatchToken?.let { return it }
        val token = UUID.randomUUID().toString().replace("-", "").take(8)
        uploadBatchToken = token
        return token
    }

    private fun requireUserUid(): String =
        userUid?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException(
                "PhotoPrism user UID unknown — re-authenticate (session must include user.UID)"
            )

    /**
     * Relative import path for the current upload batch (`/upload/{token}`).
     * PhotoPrism remaps this with the session ref so staged user uploads are indexed.
     */
    fun currentUploadImportPath(): String? =
        uploadBatchToken?.let { "/upload/$it" }

    override suspend fun uploadAsset(localMedia: Media, targetPath: String?): Result<CloudMediaEntity> {
        return try {
            val configId = currentConfig?.id ?: 0L
            val uid = requireUserUid()
            val token = ensureUploadBatchToken()
            val mediaUri = localMedia.getUri()
            val inputStream = context.contentResolver.openInputStream(mediaUri)
                ?: return Result.failure(Exception("Cannot open media file"))
            val mimeType = localMedia.mimeType
            val fileName = localMedia.label
            val tempFile = File(context.cacheDir, "pp_upload_${System.currentTimeMillis()}_$fileName")
            try {
                inputStream.use { input -> tempFile.outputStream().use { output -> input.copyTo(output) } }
                val requestBody = tempFile.asRequestBody(mimeType.toMediaTypeOrNull())
                // PhotoPrism expects the multipart field name "files".
                val filePart = MultipartBody.Part.createFormData("files", fileName, requestBody)
                val response = requireApi().uploadUserFiles(uid, token, filePart)
                if (response.isSuccessful) {
                    pendingUploadCount.incrementAndGet()
                    val entity = CloudMediaEntity(
                        remoteId = "upload:$token:$fileName",
                        providerType = ProviderType.PHOTOPRISM,
                        serverConfigId = configId,
                        label = fileName,
                        path = currentUploadImportPath().orEmpty(),
                        relativePath = currentUploadImportPath().orEmpty(),
                        mimeType = mimeType,
                        timestamp = System.currentTimeMillis(),
                        size = tempFile.length(),
                        syncState = SyncState.SYNCED,
                        contentHash = null
                    )
                    cloudMediaDao.insert(entity)
                    Result.success(entity)
                } else {
                    Result.failure(
                        Exception("PhotoPrism upload failed: ${response.code()} ${response.message()}")
                    )
                }
            } finally {
                tempFile.delete()
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun downloadAsset(remoteId: String): Result<Uri> {
        return try {
            val url = getOriginalUrl(remoteId)
            if (url.isBlank()) {
                return Result.failure(Exception("No download URL for $remoteId"))
            }
            val authHeaders = getAuthHeaders()
            val requestBuilder = Request.Builder().url(url).get()
            authHeaders.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
            val client = CloudFetcherRegistryHolder.okHttpClient
                ?: return Result.failure(Exception("OkHttpClient not initialized"))
            val response = client.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful) {
                return Result.failure(Exception("Download failed: ${response.code}"))
            }
            val body = response.body ?: return Result.failure(Exception("Empty response"))
            val ext = when {
                body.contentType()?.subtype?.contains("jpeg") == true -> ".jpg"
                body.contentType()?.subtype?.contains("png") == true -> ".png"
                body.contentType()?.subtype?.contains("mp4") == true -> ".mp4"
                else -> ""
            }
            val cacheFile = File(context.cacheDir, "pp_download_${remoteId.take(12)}$ext")
            body.byteStream().use { input -> cacheFile.outputStream().use { output -> input.copyTo(output) } }
            Result.success(cacheFile.toUri())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getChangedSince(timestamp: Long): Result<List<CloudMediaEntity>> {
        // PhotoPrism has no updatedAfter filter on /photos; best-effort newest page.
        return try {
            val response = requireApi().getPhotos(count = 1000, offset = 0, order = "newest")
            if (response.isSuccessful) {
                captureTokensFromHeaders(response.headers())
                val entities = mapPhotos(response.body().orEmpty())
                    .filter { it.timestamp >= timestamp }
                Result.success(entities)
            } else {
                Result.failure(Exception("Failed to fetch changes: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun bulkUploadCheck(hashes: List<String>): Result<Map<String, Boolean>> {
        // No server-side bulk checksum API; best-effort against local contentHash cache.
        return try {
            val configId = currentConfig?.id ?: 0L
            val result = hashes.mapIndexed { index, hash ->
                val exists = if (hash.isBlank()) {
                    false
                } else {
                    val cached = cloudMediaDao.getByContentHash(hash)
                    cached != null &&
                        cached.providerType == ProviderType.PHOTOPRISM &&
                        (configId == 0L || cached.serverConfigId == configId)
                }
                index.toString() to exists
            }.toMap()
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Best-effort: treat as present when Room already has a PhotoPrism row for this
     * account with the same file name (and matching size when known).
     */
    override suspend fun remoteExists(localMedia: Media, targetPath: String?): Boolean {
        return try {
            val configId = currentConfig?.id ?: return false
            val label = localMedia.label
            val localSize = runCatching {
                context.contentResolver.openAssetFileDescriptor(localMedia.getUri(), "r")?.use { it.length }
            }.getOrNull()
            cloudMediaDao.getAllCachedAsync().any { entity ->
                entity.providerType == ProviderType.PHOTOPRISM &&
                    entity.serverConfigId == configId &&
                    entity.label.equals(label, ignoreCase = true) &&
                    (localSize == null || localSize <= 0L || entity.size <= 0L || entity.size == localSize)
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * After a backup batch: process staged user uploads (PUT), then trigger
     * `POST /api/v1/import/` for `/upload/{token}` so PhotoPrism indexes the folder.
     */
    override suspend fun afterUploadBatch(): Result<Unit> {
        val token = uploadBatchToken
        val uid = userUid
        if (token.isNullOrBlank() || uid.isNullOrBlank() || pendingUploadCount.get() <= 0) {
            uploadBatchToken = null
            pendingUploadCount.set(0)
            return Result.success(Unit)
        }
        val importPath = "/upload/$token"
        return try {
            // Native finalize used by PhotoPrism's own web UI (moves staged files → originals).
            val process = requireApi().processUserUpload(uid, token, PhotoPrismUploadOptionsDto())
            if (!process.isSuccessful) {
                printDebug(
                    "PhotoPrismProvider: process upload failed ${process.code()} ${process.message()}"
                )
            }

            // Explicit import trigger for the staged upload folder path.
            val importResp = requireApi().startImport(
                PhotoPrismImportOptionsDto(path = importPath, move = true)
            )
            if (importResp.isSuccessful) {
                printDebug("PhotoPrismProvider: import started for $importPath")
                Result.success(Unit)
            } else if (process.isSuccessful) {
                // PUT already indexed; import may 403 for limited roles — still OK.
                printDebug(
                    "PhotoPrismProvider: import returned ${importResp.code()} after successful process"
                )
                Result.success(Unit)
            } else {
                Result.failure(
                    Exception(
                        "PhotoPrism import failed: ${importResp.code()} ${importResp.message()}"
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            uploadBatchToken = null
            pendingUploadCount.set(0)
        }
    }
}
