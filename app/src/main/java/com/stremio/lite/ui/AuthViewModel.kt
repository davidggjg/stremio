package com.stremio.lite.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stremio.lite.AppContainer
import com.stremio.lite.data.api.Session
import com.stremio.lite.data.api.StremioApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AuthUiState(
    val loading: Boolean = false,
    val error: String? = null
)

class AuthViewModel(app: Application) : AndroidViewModel(app) {
    private val container = AppContainer.get(app)

    val session: StateFlow<Session?> = container.session.session

    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    fun login(email: String, password: String) = run {
        if (email.isBlank() || password.isBlank()) {
            _state.value = AuthUiState(error = "צריך אימייל וסיסמה")
            return@run
        }
        launchAuth { container.api.login(email.trim(), password) }
    }

    fun register(email: String, password: String) = launchAuth {
        container.api.register(email.trim(), password)
    }

    /** For users who prefer not to type their password: the authKey from web.stremio.com. */
    fun loginWithAuthKey(authKey: String) {
        val key = authKey.trim()
        if (key.isBlank()) {
            _state.value = AuthUiState(error = "הדבק קודם authKey")
            return
        }
        _state.value = AuthUiState(loading = true)
        viewModelScope.launch {
            runCatching { container.api.getUser(key) }
                .onSuccess { user ->
                    container.session.save(Session(key, user))
                    onAuthenticated(key)
                    _state.value = AuthUiState()
                }
                .onFailure { _state.value = AuthUiState(error = it.readableMessage()) }
        }
    }

    private fun launchAuth(block: suspend () -> com.stremio.lite.data.api.AuthResponse) {
        _state.value = AuthUiState(loading = true)
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { response ->
                    container.session.save(Session(response.authKey, response.user))
                    onAuthenticated(response.authKey)
                    _state.value = AuthUiState()
                }
                .onFailure { _state.value = AuthUiState(error = it.readableMessage()) }
        }
    }

    private suspend fun onAuthenticated(authKey: String) {
        container.addons.onSignedIn(authKey)
        container.library.refresh()
    }

    fun logout() {
        val authKey = container.session.authKey
        container.session.clear()
        container.library.clear()
        viewModelScope.launch {
            container.addons.onSignedOut()
            authKey?.let { container.api.logout(it) }
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }
}

internal fun Throwable.readableMessage(): String = when (this) {
    is StremioApiException -> error.message
    else -> message ?: "משהו השתבש"
}
