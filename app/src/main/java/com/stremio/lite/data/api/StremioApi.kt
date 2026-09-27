package com.stremio.lite.data.api

import com.stremio.lite.data.addon.AddonJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Thin client for the official Stremio API. Only the endpoints this app needs are
 * implemented: authentication, the user's addon collection and the library datastore.
 */
class StremioApi(
    private val client: OkHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL
) {
    private val json = AddonJson.json
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun login(email: String, password: String): AuthResponse =
        post("login", LoginRequest.serializer(), LoginRequest(email, password), AuthResponse.serializer())

    suspend fun register(email: String, password: String): AuthResponse =
        post("register", RegisterRequest.serializer(), RegisterRequest(email, password), AuthResponse.serializer())

    /** Validates a pasted authKey (taken from web.stremio.com) and returns the account it belongs to. */
    suspend fun getUser(authKey: String): User =
        post("getUser", AuthKeyRequest.serializer(), AuthKeyRequest(authKey), User.serializer())

    suspend fun logout(authKey: String) {
        runCatching {
            post("logout", AuthKeyRequest.serializer(), AuthKeyRequest(authKey), JsonObject.serializer())
        }
    }

    suspend fun addonCollectionGet(authKey: String): AddonCollectionResponse =
        post(
            "addonCollectionGet",
            AddonCollectionGetRequest.serializer(),
            AddonCollectionGetRequest(authKey),
            AddonCollectionResponse.serializer()
        )

    suspend fun addonCollectionSet(authKey: String, addons: List<ApiAddonDescriptor>) {
        post(
            "addonCollectionSet",
            AddonCollectionSetRequest.serializer(),
            AddonCollectionSetRequest(authKey, addons),
            JsonObject.serializer()
        )
    }

    suspend fun libraryGet(authKey: String): List<LibraryItem> =
        post(
            "datastoreGet",
            DatastoreGetRequest.serializer(),
            DatastoreGetRequest(authKey),
            ListSerializer(LibraryItem.serializer())
        )

    suspend fun libraryPut(authKey: String, changes: List<JsonObject>) {
        post(
            "datastorePut",
            DatastorePutRequest.serializer(),
            DatastorePutRequest(authKey, changes = changes),
            JsonObject.serializer()
        )
    }

    private suspend fun <Req, Res> post(
        method: String,
        requestSerializer: KSerializer<Req>,
        request: Req,
        responseSerializer: KSerializer<Res>
    ): Res = withContext(Dispatchers.IO) {
        val body = json.encodeToString(requestSerializer, request).toRequestBody(mediaType)
        val httpRequest = Request.Builder()
            .url("$baseUrl/api/$method")
            .post(body)
            .header("Content-Type", "application/json")
            .build()

        client.newCall(httpRequest).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (text.isBlank()) {
                throw StremioApiException(ApiError(response.code.toLong(), "Empty response from $method"))
            }
            val envelope = json.decodeFromString(
                ApiEnvelope.serializer(responseSerializer),
                text
            )
            envelope.error?.let { throw StremioApiException(it) }
            envelope.result
                ?: throw StremioApiException(ApiError(response.code.toLong(), "Missing result for $method"))
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.strem.io"
    }
}
