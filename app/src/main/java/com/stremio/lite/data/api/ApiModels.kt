package com.stremio.lite.data.api

import com.stremio.lite.data.addon.Manifest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Types for the official Stremio API (https://api.strem.io/api/<method>), mirroring
 * Stremio/stremio-core `src/types/api`. Every call is a POST with a JSON body and the
 * response is an envelope holding exactly one of `result` / `error`.
 */

@Serializable
data class ApiError(val code: Long = 0, val message: String = "Unknown API error")

class StremioApiException(val error: ApiError) : Exception(error.message)

@Serializable
data class ApiEnvelope<T>(val result: T? = null, val error: ApiError? = null)

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class GdprConsent(
    val tos: Boolean = true,
    val privacy: Boolean = true,
    val marketing: Boolean = false,
    val from: String = "stremio-lite-android"
)

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    val gdprConsent: GdprConsent = GdprConsent()
)

@Serializable
data class AuthKeyRequest(val authKey: String)

@Serializable
data class AuthResponse(val authKey: String, val user: User? = null)

@Serializable
data class User(
    @kotlinx.serialization.SerialName("_id") val id: String? = null,
    val email: String? = null,
    val avatar: String? = null,
    val premiumExpire: String? = null
) {
    val isPremium: Boolean
        get() = premiumExpire != null
}

@Serializable
data class AddonCollectionGetRequest(val authKey: String, val update: Boolean = true)

@Serializable
data class AddonCollectionSetRequest(val authKey: String, val addons: List<ApiAddonDescriptor>)

@Serializable
data class AddonCollectionResponse(
    val addons: List<ApiAddonDescriptor> = emptyList(),
    val lastModified: String? = null
)

@Serializable
data class ApiAddonDescriptor(
    val transportUrl: String,
    val transportName: String? = null,
    val manifest: Manifest,
    val flags: AddonFlags? = null
)

@Serializable
data class AddonFlags(
    val official: Boolean? = null,
    val protected: Boolean? = null
)

@Serializable
data class DatastoreGetRequest(
    val authKey: String,
    val collection: String = COLLECTION_LIBRARY,
    val ids: List<String> = emptyList(),
    val all: Boolean = true
)

@Serializable
data class DatastorePutRequest(
    val authKey: String,
    val collection: String = COLLECTION_LIBRARY,
    val changes: List<JsonObject>
)

const val COLLECTION_LIBRARY = "libraryItem"

@Serializable
data class LibraryItem(
    @kotlinx.serialization.SerialName("_id") val id: String = "",
    val name: String = "",
    val type: String = "other",
    val poster: String? = null,
    val posterShape: String? = null,
    val removed: Boolean = false,
    val temp: Boolean = false,
    @kotlinx.serialization.SerialName("_ctime") val ctime: String? = null,
    @kotlinx.serialization.SerialName("_mtime") val mtime: String? = null,
    val state: LibraryItemState? = null
) {
    /** Items the user actually added to their library (not just transient "continue watching" rows). */
    val isInLibrary: Boolean get() = !removed && !temp
}

@Serializable
data class LibraryItemState(
    val lastWatched: String? = null,
    val timeWatched: Long = 0,
    val timeOffset: Long = 0,
    val duration: Long = 0,
    val video_id: String? = null,
    val season: Int = 0,
    val episode: Int = 0
) {
    val progress: Float
        get() = if (duration > 0) (timeOffset.toFloat() / duration).coerceIn(0f, 1f) else 0f
}

/** Raw JSON kept around for calls we only pass through. */
typealias RawJson = JsonElement
