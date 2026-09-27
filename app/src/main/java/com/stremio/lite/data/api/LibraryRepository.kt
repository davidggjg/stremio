package com.stremio.lite.data.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The user's Stremio library ("My Library" / Continue watching), pulled from the account. */
class LibraryRepository(
    private val api: StremioApi,
    private val session: SessionStore
) {
    private val _items = MutableStateFlow<List<LibraryItem>>(emptyList())
    val items: StateFlow<List<LibraryItem>> = _items.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    suspend fun refresh() {
        val authKey = session.authKey
        if (authKey == null) {
            _items.value = emptyList()
            return
        }
        runCatching { api.libraryGet(authKey) }
            .onSuccess { items ->
                _items.value = items.filter { it.isInLibrary }.sortedByDescending { it.mtime }
                _error.value = null
            }
            .onFailure { _error.value = it.message }
    }

    fun clear() {
        _items.value = emptyList()
    }

    /** Items with watch progress, newest first — the "Continue watching" row. */
    val continueWatching: List<LibraryItem>
        get() = _items.value.filter { (it.state?.progress ?: 0f) > 0.01f && it.state!!.progress < 0.95f }
}
