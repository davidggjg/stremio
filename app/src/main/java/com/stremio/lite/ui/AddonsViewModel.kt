package com.stremio.lite.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stremio.lite.AppContainer
import com.stremio.lite.data.addon.InstalledAddon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AddonsUiState(
    val busy: Boolean = false,
    val message: String? = null
)

class AddonsViewModel(app: Application) : AndroidViewModel(app) {
    private val container = AppContainer.get(app)

    val addons: StateFlow<List<InstalledAddon>> = container.addons.addons
    val syncError: StateFlow<String?> = container.addons.syncError
    val signedIn: Boolean get() = container.session.authKey != null

    private val _state = MutableStateFlow(AddonsUiState())
    val state: StateFlow<AddonsUiState> = _state.asStateFlow()

    fun install(url: String) {
        if (url.isBlank()) return
        _state.value = AddonsUiState(busy = true)
        viewModelScope.launch {
            val result = container.addons.installFromUrl(url)
            _state.value = AddonsUiState(
                message = result.fold(
                    onSuccess = { "${it.name} הותקן" + if (signedIn) " וסונכרן לחשבון" else "" },
                    onFailure = { "לא ניתן להתקין: ${it.readableMessage()}" }
                )
            )
        }
    }

    fun uninstall(addon: InstalledAddon) {
        viewModelScope.launch {
            val result = container.addons.uninstall(addon)
            _state.value = AddonsUiState(
                message = result.fold(
                    onSuccess = { "${addon.name} הוסר" },
                    onFailure = { it.readableMessage() }
                )
            )
        }
    }

    fun syncNow() {
        val authKey = container.session.authKey ?: return
        _state.value = AddonsUiState(busy = true)
        viewModelScope.launch {
            val result = runCatching { container.addons.pullFromAccount(authKey) }
            _state.value = AddonsUiState(
                message = result.fold(
                    onSuccess = { "התוספים סונכרנו מחשבון ה-Stremio" },
                    onFailure = { "הסנכרון נכשל: ${it.readableMessage()}" }
                )
            )
        }
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }
}
