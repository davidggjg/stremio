package com.stremio.lite.data.api

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Session(val authKey: String, val user: User?)

/**
 * Persists the Stremio authKey. The key is a long-lived credential for the user's whole
 * account, so it is kept in EncryptedSharedPreferences rather than plain prefs.
 */
class SessionStore(context: Context) {

    private val prefs = runCatching {
        EncryptedSharedPreferences.create(
            context,
            "stremio_session",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrElse {
        // Some devices have a broken keystore; fall back so the app stays usable.
        context.getSharedPreferences("stremio_session_plain", Context.MODE_PRIVATE)
    }

    private val _session = MutableStateFlow(load())
    val session: StateFlow<Session?> = _session.asStateFlow()

    val authKey: String? get() = _session.value?.authKey

    private fun load(): Session? {
        val key = prefs.getString(KEY_AUTH, null)?.takeIf { it.isNotBlank() } ?: return null
        val user = prefs.getString(KEY_USER, null)?.let {
            runCatching { com.stremio.lite.data.addon.AddonJson.json.decodeFromString(User.serializer(), it) }
                .getOrNull()
        }
        return Session(key, user)
    }

    fun save(session: Session) {
        prefs.edit()
            .putString(KEY_AUTH, session.authKey)
            .putString(
                KEY_USER,
                session.user?.let {
                    com.stremio.lite.data.addon.AddonJson.json.encodeToString(User.serializer(), it)
                }
            )
            .apply()
        _session.value = session
    }

    fun clear() {
        prefs.edit().remove(KEY_AUTH).remove(KEY_USER).apply()
        _session.value = null
    }

    private companion object {
        const val KEY_AUTH = "auth_key"
        const val KEY_USER = "user"
    }
}
