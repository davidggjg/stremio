package com.stremio.lite.data.addon

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

object AddonJson {
    @OptIn(ExperimentalSerializationApi::class)
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = true
    }
}
