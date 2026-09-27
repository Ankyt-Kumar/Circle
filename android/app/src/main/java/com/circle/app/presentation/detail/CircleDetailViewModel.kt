package com.circle.app.presentation.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circle.app.domain.error.CircleException
import com.circle.app.domain.error.FailureReason
import com.circle.app.domain.usecase.GetCircle
import com.circle.app.domain.usecase.UpdateCircleMembership
import com.circle.app.presentation.common.toUiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CircleDetailViewModel(
    private val circleId: String,
    private val getCircle: GetCircle,
    private val updateMembership: UpdateCircleMembership,
    private val events: com.circle.app.domain.usecase.EventActions? = null,
) : ViewModel() {
    private val mutable = MutableStateFlow(CircleDetailUiState())
    val state = mutable.asStateFlow()
    private var loadJob: Job? = null

    fun refresh() {
        if (state.value.joining || state.value.deleted) return
        loadJob?.cancel()
        loadJob =
            viewModelScope.launch {
                mutable.update { it.copy(loading = true, error = null) }
                try {
                    val circle = getCircle(circleId)
                    mutable.update {
                        it.copy(circle = circle, loading = false, refreshRequired = false)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e.isGone()) markDeleted()
                    else mutable.update { it.copy(loading = false, error = e.toUiMessage()) }
                }
            }
    }

    fun toggleJoin() {
        val current = state.value
        val circle = current.circle ?: return
        if (current.joining || current.loading || current.refreshRequired || !circle.joined && !circle.eligible) return
        mutable.update { it.copy(joining = true, error = null) }
        viewModelScope.launch {
            var confirmed = false
            try {
                updateMembership(circle.id, !circle.joined)
                confirmed = true
                val latest = getCircle(circleId)
                mutable.update {
                    it.copy(circle = latest, joining = false, refreshRequired = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e.isGone()) {
                    // A leave can delete the circle. A 404 here is its final state,
                    // including when a response was lost and the user retries.
                    markDeleted()
                    return@launch
                }
                // A network failure may happen after the server commits. Reload before another
                // toggle.
                mutable.update {
                    it.copy(
                        joining = false,
                        refreshRequired = true,
                        error =
                            if (confirmed)
                                "Your change was saved. Reload to see the latest membership."
                            else e.toUiMessage() + " Reload before trying again.",
                    )
                }
            }
        }
    }

    fun cancelEvent() {
        val circle = state.value.circle ?: return
        val service = events ?: return
        if (state.value.joining) return
        mutable.update { it.copy(joining = true) }
        viewModelScope.launch {
            try {
                service.cancel(circle.id, circle.revision)
                mutable.update { it.copy(joining = false) }
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update {
                    it.copy(joining = false, error = e.toUiMessage(), refreshRequired = true)
                }
            }
        }
    }

    fun feedback(attended: Boolean, rating: String) {
        val service = events ?: return
        viewModelScope.launch {
            try {
                service.feedback(circleId, attended, rating)
                mutable.update {
                    it.copy(notice = "Thanks. Your attendance and feedback are saved.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(error = e.toUiMessage()) }
            }
        }
    }

    private fun markDeleted() {
        mutable.value = CircleDetailUiState(loading = false, deleted = true)
    }

    private fun Exception.isGone() = this is CircleException && reason == FailureReason.NOT_FOUND
}
