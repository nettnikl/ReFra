/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.cloud.photoprism.data.api

import com.dot.gallery.cloud.photoprism.data.dto.PhotoPrismAlbumDto
import com.dot.gallery.cloud.photoprism.data.dto.PhotoPrismConfigDto
import com.dot.gallery.cloud.photoprism.data.dto.PhotoPrismOAuthTokenDto
import com.dot.gallery.cloud.photoprism.data.dto.PhotoPrismPhotoDto
import com.dot.gallery.cloud.photoprism.data.dto.PhotoPrismSessionDto
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface PhotoPrismApiService {

    @POST("api/v1/session")
    suspend fun createSession(@Body body: RequestBody): Response<PhotoPrismSessionDto>

    @FormUrlEncoded
    @POST("api/v1/oauth/token")
    suspend fun oauthToken(
        @Field("grant_type") grantType: String,
        @Field("client_id") clientId: String,
        @Field("client_secret") clientSecret: String
    ): Response<PhotoPrismOAuthTokenDto>

    @GET("api/v1/config")
    suspend fun getConfig(): Response<PhotoPrismConfigDto>

    @GET("api/v1/session")
    suspend fun getSession(): Response<PhotoPrismSessionDto>

    @GET("api/v1/photos")
    suspend fun getPhotos(
        @Query("count") count: Int,
        @Query("offset") offset: Int,
        @Query("merged") merged: Boolean = true,
        @Query("primary") primary: Boolean = true,
        @Query("order") order: String = "newest",
        @Query("q") query: String? = null,
        @Query("s") albumUid: String? = null,
        @Query("favorite") favorite: Boolean? = null,
        @Query("archived") archived: Boolean? = null,
        @Query("private") private: Boolean? = null
    ): Response<List<PhotoPrismPhotoDto>>

    @GET("api/v1/albums")
    suspend fun getAlbums(
        @Query("count") count: Int = 1000,
        @Query("offset") offset: Int = 0,
        @Query("type") type: String = "album"
    ): Response<List<PhotoPrismAlbumDto>>

    @POST("api/v1/albums")
    suspend fun createAlbum(@Body body: Map<String, @JvmSuppressWildcards Any>): Response<PhotoPrismAlbumDto>

    @POST("api/v1/albums/{uid}/photos")
    suspend fun addPhotosToAlbum(
        @Path("uid") albumUid: String,
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): Response<Unit>

    @POST("api/v1/photos/{uid}/like")
    suspend fun likePhoto(@Path("uid") uid: String): Response<Unit>

    @DELETE("api/v1/photos/{uid}/like")
    suspend fun unlikePhoto(@Path("uid") uid: String): Response<Unit>

    /** Soft-delete photos (sets deleted_at). Recoverable via [batchRestorePhotos]. */
    @POST("api/v1/batch/photos/archive")
    suspend fun batchArchivePhotos(
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): Response<Unit>

    /** Restore soft-deleted photos. */
    @POST("api/v1/batch/photos/restore")
    suspend fun batchRestorePhotos(
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): Response<Unit>

    /** Toggle the Private flag on photos (not an absolute set). */
    @POST("api/v1/batch/photos/private")
    suspend fun batchPrivatePhotos(
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): Response<Unit>
}
