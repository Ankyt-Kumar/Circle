package com.circle.app.presentation.theme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circle.app.domain.model.Appearance
import com.circle.app.domain.repository.AppearanceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class AppearanceViewModel(private val repository: AppearanceRepository) : ViewModel() {
    val appearance = repository.appearance.stateIn(viewModelScope, SharingStarted.Eagerly, Appearance())
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    fun save(value: Appearance) { viewModelScope.launch {
        try { repository.save(value); mutableError.value = null }
        catch(e: CancellationException) { throw e }
        catch(_: Exception) { mutableError.value = "Could not save appearance. Please try again." }
    } }
}
